package com.nutomic.syncthingandroid.runtime;

import com.topjohnwu.superuser.Shell;
import com.google.common.io.BaseEncoding;

import com.nutomic.syncthingandroid.service.Constants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Root shell transport implemented with libsu core.
 *
 * <p>This class is the only place in the application that talks to libsu. It exposes semantic
 * operations to the root backend and keeps the direct shell mechanics, including the audited
 * command text and its parsing, inside the root transport boundary. Application callers never
 * receive this type or any libsu type.</p>
 *
 * <p>Helper operations use libsu jobs and stay valid because the shell keeps running as a shell.
 * The bundled Syncthing launch instead uses exactly one raw terminal task whose script replaces
 * the shell with the bundled binary; this shell must never be reused afterwards.</p>
 */
final class LibsuRootShell implements RootShell {
    /** Environment name that carries the private run token into the launched process. */
    static final String RUN_TOKEN_ENVIRONMENT = "STANDROID_RUN_TOKEN";
    /** Prefix of the line that announces one {@code /proc} entry. */
    static final String PID_MARKER = "__STANDROID_PID__";
    /** Prefix of the line that separates the executable link from the stat entry. */
    static final String STAT_MARKER = "__STANDROID_STAT__";
    /** Prefix line announces the shell's own process identifier. */
    static final String SHELL_PID_MARKER = "__STANDROID_SHELL_PID__";

    private static final String BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id";
    /** Fields before {@code starttime} in {@code /proc/<pid>/stat}, counted after the comm field. */
    private static final int START_TIME_FIELD_INDEX = 19;

    /** Field index of the parent process identifier inside one {@code /proc/<pid>/stat} line. */
    private static final int PARENT_PROCESS_FIELD_INDEX = 1;
    /** Source of the shell's own {@code /proc/<pid>/stat} line, read while the shell is alive. */
    private static final String SHELL_STAT_SOURCE = "/proc/$$/stat";
    /**
     * Upper bound for one helper operation, so a stalled shell can never hang a caller. This is
     * the bound every production transport uses; transports that need a tighter bound are
     * constructed with an explicit value.
     */
    static final long OPERATION_TIMEOUT_MILLIS = 15_000;

    /** Exit status the state commands use when the shell does not run as real UID 0. */
    static final int STATE_UID_GUARD_EXIT_CODE = 90;
    /** Exit status used when an operation name or the staging base is not usable. */
    static final int STATE_OPERATION_NAME_EXIT_CODE = 91;
    /** Exit status used when Managed State contains a symbolic link or a special file. */
    static final int STATE_UNSAFE_TREE_EXIT_CODE = 92;
    /** Exit status used when a copy could not be completed. */
    static final int STATE_COPY_EXIT_CODE = 93;
    /** Exit status used when the staged tree could not be handed to the application. */
    static final int STATE_HANDOFF_EXIT_CODE = 94;
    /** Exit status used when installation into Managed State failed. */
    static final int STATE_INSTALL_EXIT_CODE = 95;
    /** Exit status used when a repair could not be completed or verified. */
    static final int STATE_REPAIR_EXIT_CODE = 96;
    /** Exit status of a state read whose member is absent. */
    static final int STATE_MISSING_EXIT_CODE = 3;
    /** Exit status of a state operation whose member is a symbolic link or has the wrong kind. */
    static final int STATE_UNSAFE_MEMBER_EXIT_CODE = 4;
    /** Exit status used when a folder or folder member needed by an operation is absent. */
    static final int FOLDER_MISSING_EXIT_CODE = 3;
    /** Exit status used when a folder member has an unsafe kind, such as a symbolic link. */
    static final int FOLDER_UNSAFE_EXIT_CODE = 4;
    /** Exit status used when a probe proved that the folder is not writable. */
    static final int FOLDER_READ_ONLY_EXIT_CODE = 5;
    /** Exit status used when a probe could not decide whether the folder is writable. */
    static final int FOLDER_UNKNOWN_EXIT_CODE = 6;
    /** Exit status used when a bounded folder scan ran out of one of its budgets. */
    static final int FOLDER_LIMIT_EXIT_CODE = 7;
    /** Exit status used when a folder operation could not be completed. */
    static final int FOLDER_ACCESS_EXIT_CODE = 8;
    /** Exit status used when an optional tuning request cannot apply to this state. */
    static final int TUNING_NOT_APPLICABLE_EXIT_CODE = 9;
    /** Exit status used when a tuning command refuses an identifier it could not re-verify. */
    static final int TUNING_IDENTITY_MISMATCH_EXIT_CODE = 10;
    /**
     * Bound for one sync-completion script dispatch.
     *
     * <p>Deliberately independent of {@link #OPERATION_TIMEOUT_MILLIS}: a user script may
     * legitimately run for minutes, while a plain helper operation must not keep a caller waiting
     * that long.</p>
     */
    static final long SCRIPT_DISPATCH_TIMEOUT_MILLIS = 300_000;
    /** Byte budget for the ignore-list member one operation reads or writes. */
    static final int FOLDER_IGNORE_LIST_MAX_BYTES = 1_048_576;
    /**
     * Block size of the bounded reader one ignore-list read uses.
     *
     * <p>The reader stops after the size it checked plus at most two such blocks, so a member that
     * grows while it is read can never make the helper buffer more than the checked size plus one
     * block, however large the member becomes under the read.</p>
     */
    static final int FOLDER_IGNORE_LIST_READ_BLOCK_BYTES = 4_096;
    /** Entry budget for one privileged conflict scan. */
    static final int FOLDER_SCAN_MAX_ENTRIES = 200_000;
    /** Match budget for one privileged conflict scan. */
    static final int FOLDER_SCAN_MAX_MATCHES = 1_000;
    /** Output budget in characters for one privileged conflict scan. */
    static final int FOLDER_SCAN_MAX_OUTPUT_CHARS = 1_048_576;
    /** Scan time budget in milliseconds the privileged conflict scan enforces inside the shell. */
    static final int FOLDER_SCAN_BUDGET_MILLIS = 10_000;
    /**
     * Entries one privileged conflict scan processes before it reads the clock again.
     *
     * <p>Reading the clock runs a separate command, so the scan does not pay for it on every one
     * of up to {@link #FOLDER_SCAN_MAX_ENTRIES} entries while one wide directory still cannot
     * outrun the scan budget.</p>
     */
    static final int FOLDER_SCAN_TIME_CHECK_STRIDE = 2_048;
    /** Output budget in characters for the report one script dispatch may return. */
    static final int FOLDER_SCRIPT_MAX_OUTPUT_CHARS = 65_536;
    /** Background I/O priority class applied to an owned Syncthing process. */
    static final int IO_PRIORITY_CLASS = 2;
    /** Background I/O priority level applied to an owned Syncthing process. */
    static final int IO_PRIORITY_LEVEL = 7;
    /** Fixed ignore-list member name inside one configured folder root. */
    static final String FOLDER_IGNORE_FILE_NAME = Constants.FILENAME_STIGNORE;
    /** Fixed directory name inside one configured folder root that holds approved scripts. */
    static final String FOLDER_SCRIPT_DIRECTORY_NAME = Constants.FILENAME_STFOLDER;
    /** Versioning directory name a conflict scan never descends into. */
    static final String FOLDER_VERSIONING_DIRECTORY_NAME = Constants.FOLDER_NAME_STVERSIONS;
    /** Operation-directory names this transport accepts, as ManagedStateStaging generates them. */
    static final Pattern OPERATION_NAME_PATTERN = Pattern.compile(
            "op-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );
    /** Standard padded Base64 used to transport member content without altering any byte. */
    private static final BaseEncoding STATE_BASE64 = BaseEncoding.base64();
    /** Line separator used inside the fixed state commands. */
    private static final String STATE_LINE = "\n";
    /** Kernel switch that reports whether SELinux currently enforces labels. */
    private static final String SELINUX_ENFORCE_PATH = "/sys/fs/selinux/enforce";
    /** Fixed member names the state commands copy, replace and repair. */
    private static final String STATE_FILE_NAMES = "config.xml cert.pem key.pem https-cert.pem https-key.pem";
    private static final ExecutorService OPERATION_EXECUTOR =
            Executors.newCachedThreadPool(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "root-shell-operation");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private final Shell shell;
    private final Process process;
    private final long operationTimeoutMillis;
    private final ManagedStateLocations locations;
    private int shellPid;

    private boolean exitStatusProvenanceRecorded;
    private boolean exitStatusBelongsToLaunchedProcess;
    /** Set when a failed helper operation tore this transport down, such as on a timeout. */
    private volatile boolean transportInvalidatedByFailedOperation;

    LibsuRootShell(Shell shell, Process process, ManagedStateLocations locations) {
        this(shell, process, locations, OPERATION_TIMEOUT_MILLIS);
    }

    /**
     * Creates one transport over an acquired shell with an explicit helper-operation bound.
     *
     * <p>Production acquires transports with {@link #OPERATION_TIMEOUT_MILLIS}; the override
     * exists so the cleanup behavior of a timing-out helper operation can be exercised
     * deterministically.</p>
     */
    LibsuRootShell(
            Shell shell,
            Process process,
            ManagedStateLocations locations,
            long operationTimeoutMillis
    ) {
        this.shell = Objects.requireNonNull(shell);
        this.process = Objects.requireNonNull(process);
        this.locations = Objects.requireNonNull(locations);
        if (operationTimeoutMillis <= 0) {
            throw new IllegalArgumentException("The helper-operation timeout must be positive");
        }
        this.operationTimeoutMillis = operationTimeoutMillis;
    }

    @Override
    public List<ProcessEntry> listProcesses() throws IOException {
        return parseProcessList(runCommand(processListScript()));
    }

    @Override
    public String readRunToken(int pid) throws IOException {
        List<String> output = new ArrayList<>();
        Shell.Result result = runJob(runTokenScript(pid), output, new ArrayList<>());
        if (result.getCode() != 0) {
            return null;
        }
        return parseRunToken(output);
    }

    @Override
    public boolean sendSignal(int pid, int signal) throws IOException {
        if (pid <= 0 || signal <= 0) {
            throw new IOException("Unsupported signal target");
        }
        List<String> output = new ArrayList<>();
        Shell.Result result = runJob("kill -" + signal + " " + pid, output, new ArrayList<>());
        return result.getCode() == 0;
    }

    @Override
    public int pid() throws IOException {
        synchronized (this) {
            if (shellPid > 0) {
                return shellPid;
            }
            for (String line : runCommand("echo " + SHELL_PID_MARKER + "$$")) {
                if (!line.startsWith(SHELL_PID_MARKER)) {
                    continue;
                }
                int pid = parsePositiveInt(line.substring(SHELL_PID_MARKER.length()).trim());
                if (pid > 0) {
                    shellPid = pid;
                    return pid;
                }
            }
            throw new IOException("Could not read the root shell process identifier");
        }
    }

    /**
     * Determines once, while the shell is still alive, whether this transport's exit status can be
     * attributed to the process that the terminal {@code exec} creates.
     *
     * <p>The audited launch script replaces the shell process with the bundled binary, so the
     * status the transport reports is Syncthing's own status only while the local client process
     * is the shell itself. The shell reports its parent process: a shell whose parent is the
     * owning application process is that client, because a directly attached {@code su} client is
     * replaced by the shell it drives. Every other relationship, and every unreadable answer,
     * leaves the status unattributable, so callers fail closed instead of trusting a status that a
     * detached client may report on its own.</p>
     *
     * <p>The question can be answered only while the client is alive, so the answer is recorded
     * here and read later through {@link #exitStatusBelongsToLaunchedProcess()}. An unreadable
     * answer alone never fails the acquisition; it only means the status stays unattributable.
     * A probe whose failure left the transport unusable, because our own teardown closed it or
     * because the shell itself died while the probe ran, does fail the acquisition with the
     * probe's own failure: a dead transport must never be handed out as a verified shell.</p>
     *
     * @param ownerProcessId process identifier of the application process that owns this transport
     * @throws IOException when the failed probe left the shell transport unusable
     */
    void determineExitStatusProvenance(int ownerProcessId) throws IOException {
        synchronized (this) {
            if (exitStatusProvenanceRecorded) {
                return;
            }
            exitStatusProvenanceRecorded = true;
            try {
                int parentPid = parentProcessId();
                exitStatusBelongsToLaunchedProcess = parentPid > 0 && parentPid == ownerProcessId;
            } catch (IOException | RuntimeException unprovable) {
                if (!isTransportUsable()) {
                    throw unprovable;
                }
                exitStatusBelongsToLaunchedProcess = false;
            }
        }
    }

    @Override
    public boolean exitStatusBelongsToLaunchedProcess() {
        synchronized (this) {
            return exitStatusBelongsToLaunchedProcess;
        }
    }

    /**
     * Reads the parent process identifier of the shell process.
     *
     * <p>The answer identifies whether the shell is the direct child of the owning application
     * process, which is the case exactly when the local transport client was replaced by the shell
     * it drives.</p>
     *
     * @throws IOException when the shell cannot answer
     */
    int parentProcessId() throws IOException {
        List<String> output = runCommand("cat " + SHELL_STAT_SOURCE);
        if (output.isEmpty()) {
            throw new IOException("Could not read the shell status entry");
        }
        int parentPid = parseParentProcessId(output.get(0));
        if (parentPid <= 0) {
            throw new IOException("Could not read the shell parent process identifier");
        }
        return parentPid;
    }

    @Override
    public void execTerminalScript(String script) throws IOException {
        Objects.requireNonNull(script);
        final AtomicBoolean written = new AtomicBoolean(false);
        final AtomicBoolean shellDied = new AtomicBoolean(false);
        try {
            shell.execTask(new Shell.Task() {
                @Override
                public void run(OutputStream stdin, InputStream stdout, InputStream stderr)
                        throws IOException {
                    stdin.write(script.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                    written.set(true);
                }

                @Override
                public void shellDied() {
                    shellDied.set(true);
                }
            });
        } catch (IOException e) {
            throw new IOException("Could not write the root launch script", e);
        }
        if (shellDied.get() || !written.get()) {
            throw new IOException("The root shell terminated before the launch script was written");
        }
    }

    /**
     * Reports local {@code su} client termination only.
     *
     * <p>libsu may be backed by a root-manager daemon whose UID-0 child outlives this Java
     * {@link Process}; RootBackend therefore treats this only as the trigger for exact process
     * re-verification and never as cleanup authority by itself.</p>
     */
    @Override
    public boolean hasExited() {
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException stillRunning) {
            return false;
        } catch (RuntimeException uncertain) {
            return false;
        }
    }

    /**
     * Waits for the local {@code su} transport client and reports that client's exit status.
     *
     * <p>For a directly attached root shell this normally tracks the terminal {@code exec} and
     * preserves Syncthing's exit status. Daemon-backed root managers can decouple the client from
     * the UID-0 process, however, so RootBackend independently verifies the durable process
     * identity before it treats this status as the execution's terminal result.</p>
     */
    @Override
    public int awaitExit() throws InterruptedException {
        return process.waitFor();
    }

    @Override
    public String readBootId() throws IOException {
        List<String> output = runCommand("cat " + BOOT_ID_PATH);
        String bootId = output.isEmpty() ? "" : output.get(0).trim();
        if (bootId.isEmpty()) {
            throw new IOException("Could not read the kernel boot identifier");
        }
        return bootId;
    }

    @Override
    public byte[] readStateFile(ManagedStateMember member) throws IOException {
        requireFileMember(member);
        List<String> output = new ArrayList<>();
        Shell.Result result = runStateJob(readStateFileScript(member), output, "read " + member.fileName());
        if (result.getCode() == STATE_MISSING_EXIT_CODE) {
            return null;
        }
        requireSuccessfulStateResult(result, "read " + member.fileName());
        return decodeStateBase64(output);
    }

    @Override
    public void writeStateFile(ManagedStateMember member, byte[] contents) throws IOException {
        requireFileMember(member);
        Objects.requireNonNull(contents, "The member content is required");
        List<String> output = new ArrayList<>();
        Shell.Result result = runStateJob(
                writeStateFileScript(member, encodeStateBase64(contents)),
                output,
                "write " + member.fileName()
        );
        requireSuccessfulStateResult(result, "write " + member.fileName());
    }

    @Override
    public void removeStateMember(ManagedStateMember member) throws IOException {
        List<String> output = new ArrayList<>();
        Shell.Result result = runStateJob(
                removeStateMemberScript(member),
                output,
                "remove " + member.fileName()
        );
        requireSuccessfulStateResult(result, "remove " + member.fileName());
    }

    @Override
    public boolean stateMemberExists(ManagedStateMember member) throws IOException {
        List<String> output = new ArrayList<>();
        Shell.Result result = runStateJob(
                stateMemberExistsScript(member),
                output,
                "check " + member.fileName()
        );
        if (result.getCode() == 0) {
            return true;
        }
        if (result.getCode() == STATE_MISSING_EXIT_CODE
                || result.getCode() == STATE_UNSAFE_MEMBER_EXIT_CODE) {
            return false;
        }
        requireSuccessfulStateResult(result, "check " + member.fileName());
        return false;
    }

    @Override
    public void stageManagedStateForApp(String operationName) throws IOException {
        runStateScript(stageManagedStateScript(operationName), "stage Managed State");
    }

    @Override
    public void removeStagingDirectory(String operationName) throws IOException {
        runStateScript(
                removeStagingDirectoryScript(operationName),
                "remove the staging directory"
        );
    }

    @Override
    public void installStagedManagedState(String operationName) throws IOException {
        runStateScript(
                installStagedManagedStateScript(operationName),
                "install staged Managed State"
        );
    }

    @Override
    public void repairManagedStateAccess() throws IOException {
        runStateScript(repairManagedStateScript(), "repair Managed State access");
    }

    @Override
    public FolderWriteability probeFolderWriteability(String candidatePath) throws IOException {
        requireAbsoluteFolderPath(candidatePath);
        List<String> output = new ArrayList<>();
        Shell.Result result = runFolderJob(
                folderWriteabilityScript(candidatePath),
                output,
                "probe the writeability of a candidate folder"
        );
        switch (result.getCode()) {
            case 0:
                return FolderWriteability.WRITABLE;
            case FOLDER_READ_ONLY_EXIT_CODE:
                return FolderWriteability.READ_ONLY;
            case FOLDER_UNKNOWN_EXIT_CODE:
                return FolderWriteability.UNKNOWN;
            default:
                throw folderOperationFailure(result, "probe the writeability of a candidate folder");
        }
    }

    @Override
    public ConflictDiscoveryResult discoverConflictFiles(String folderRoot) throws IOException {
        requireAbsoluteFolderPath(folderRoot);
        List<String> output = new ArrayList<>();
        Shell.Result result = runFolderJob(
                conflictDiscoveryScript(folderRoot),
                output,
                "scan a configured folder for conflict files"
        );
        if (result.getCode() != 0) {
            if (result.getCode() == 124) {
                throw new RootFolderOperationException(
                        FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                        "The conflict scan exceeded its time budget"
                );
            }
            throw folderOperationFailure(result, "scan a configured folder for conflict files");
        }
        return decodeConflictDiscovery(output);
    }

    @Override
    public FolderIgnoreResult readFolderIgnoreList(String folderRoot) throws IOException {
        requireAbsoluteFolderPath(folderRoot);
        List<String> output = new ArrayList<>();
        Shell.Result result = runFolderJob(
                readFolderIgnoreListScript(folderRoot),
                output,
                "read a folder ignore list"
        );
        if (result.getCode() == FOLDER_MISSING_EXIT_CODE) {
            return FolderIgnoreResult.of(null);
        }
        if (result.getCode() != 0) {
            throw folderOperationFailure(result, "read a folder ignore list");
        }
        byte[] content = decodeFolderBase64(output, "read a folder ignore list");
        if (content.length > FOLDER_IGNORE_LIST_MAX_BYTES) {
            throw new RootFolderOperationException(
                    FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                    "The folder ignore list exceeds the byte budget"
            );
        }
        return FolderIgnoreResult.of(new String(content, StandardCharsets.UTF_8).split("\n"));
    }

    @Override
    public void writeFolderIgnoreList(String folderRoot, byte[] contents) throws IOException {
        requireAbsoluteFolderPath(folderRoot);
        Objects.requireNonNull(contents, "The ignore-list content is required");
        if (contents.length > FOLDER_IGNORE_LIST_MAX_BYTES) {
            throw new RootFolderOperationException(
                    FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                    "The folder ignore list exceeds the byte budget"
            );
        }
        List<String> output = new ArrayList<>();
        Shell.Result result = runFolderJob(
                writeFolderIgnoreListScript(folderRoot, STATE_BASE64.encode(contents)),
                output,
                "write a folder ignore list"
        );
        if (result.getCode() != 0) {
            throw folderOperationFailure(result, "write a folder ignore list");
        }
    }

    @Override
    public List<FolderScriptOutcome> runFolderScriptSet(String folderRoot, String eventArgument)
            throws IOException {
        requireAbsoluteFolderPath(folderRoot);
        Objects.requireNonNull(eventArgument, "The folder event argument is required");
        List<String> output = new ArrayList<>();
        Shell.Result result = runBoundedJob(
                folderScriptSetScript(folderRoot, eventArgument),
                output,
                SCRIPT_DISPATCH_TIMEOUT_MILLIS,
                "run the approved folder scripts"
        );
        if (result.getCode() == 124) {
            throw new RootFolderOperationException(
                    FolderOperationFailure.SCRIPT_DISPATCH_FAILED,
                    "The script dispatch exceeded its deadline"
            );
        }
        if (result.getCode() != 0) {
            throw folderOperationFailure(result, "run the approved folder scripts");
        }
        return decodeFolderScriptOutcomes(output);
    }

    @Override
    public TuningOutcome applyIoPriority(ExecutionIdentity identity) throws IOException {
        Objects.requireNonNull(identity, "The owned execution is required");
        List<String> output = new ArrayList<>();
        Shell.Result result = runFolderJob(
                ioPriorityScript(identity.pid(), identity.processStartTimeTicks()),
                output,
                "apply the background I/O priority"
        );
        if (result.getCode() == TUNING_NOT_APPLICABLE_EXIT_CODE) {
            return TuningOutcome.notApplicable("The device does not provide the ionice command");
        }
        if (result.getCode() == TUNING_IDENTITY_MISMATCH_EXIT_CODE) {
            return TuningOutcome.notApplicable(
                    "The recorded execution no longer owns that process identifier"
            );
        }
        if (result.getCode() != 0) {
            throw folderOperationFailure(result, "apply the background I/O priority");
        }
        return TuningOutcome.applied(
                "Applied I/O priority class " + IO_PRIORITY_CLASS
                        + " level " + IO_PRIORITY_LEVEL + " to process " + identity.pid()
        );
    }

    @Override
    public TuningOutcome applyInotifyWatchLimit(int watchLimit) throws IOException {
        if (watchLimit <= 0) {
            throw new IllegalArgumentException("The inotify watch limit must be positive");
        }
        List<String> output = new ArrayList<>();
        Shell.Result result = runFolderJob(
                inotifyWatchLimitScript(watchLimit),
                output,
                "raise the inotify watch limit"
        );
        if (result.getCode() == TUNING_NOT_APPLICABLE_EXIT_CODE) {
            return TuningOutcome.notApplicable("The kernel does not expose the inotify watch limit");
        }
        if (result.getCode() != 0) {
            throw folderOperationFailure(result, "raise the inotify watch limit");
        }
        return TuningOutcome.applied("The inotify watch limit is at least " + watchLimit);
    }

    /** Runs one state command and maps a failed transport to its own typed root failure. */
    private Shell.Result runStateJob(String command, List<String> stdout, String operation)
            throws IOException {
        return runBoundedJob(command, stdout, operationTimeoutMillis, operation);
    }

    /**
     * Runs one folder-operation command inside the plain helper-operation deadline.
     *
     * <p>Only a sync-completion script dispatch may run longer than this bound; every other
     * privileged folder operation keeps the same deadline as the rest of the root transport.</p>
     */
    private Shell.Result runFolderJob(String command, List<String> stdout, String operation)
            throws IOException {
        return runBoundedJob(command, stdout, operationTimeoutMillis, operation);
    }

    /**
     * Runs one privileged script inside a bounded child shell and maps transport problems to the
     * typed root transport failure.
     *
     * <p>Top-level exit commands stay inside a child shell so libsu's persistent shell survives
     * long enough to deliver its job framing and status. Android Toybox's {@code timeout} owns a
     * separate process group and waits for it after TERM/KILL, so a timed out script cannot keep
     * mutating files after this transport reports failure. The in-script supervisor fires before
     * the job deadline, so a script that runs out of time reports its own status instead of
     * tearing the transport down.</p>
     *
     * @param timeoutMillis deadline for the whole job, including result transport
     */
    private Shell.Result runBoundedJob(
            String command,
            List<String> stdout,
            long timeoutMillis,
            String operation
    ) throws IOException {
        try {
            // Keep top-level exit commands inside a child shell so libsu's persistent shell
            // survives long enough to deliver its job framing and status. Android Toybox's
            // timeout owns a separate process group and waits for it after TERM/KILL, so a timed
            // out state script cannot keep mutating files after this transport reports failure.
            Shell.Result result = runJob(
                    "(" + STATE_LINE + boundedStateCommand(command, timeoutMillis)
                            + STATE_LINE + ")",
                    stdout,
                    new ArrayList<>(),
                    timeoutMillis
            );
            if (result == null || result.getCode() == Shell.Result.JOB_NOT_EXECUTED) {
                closeAfterFailedOperation();
                throw new RootTransportException(
                        RootFailure.ROOT_TRANSPORT_FAILED,
                        "The root shell could not execute the command to " + operation
                );
            }
            return result;
        } catch (IOException e) {
            throw new RootTransportException(
                    RootFailure.ROOT_TRANSPORT_FAILED,
                    "The root shell transport failed while trying to " + operation,
                    e
            );
        }
    }

    /** Runs one state script and requires it to report success. */
    private void runStateScript(String script, String operation) throws IOException {
        List<String> output = new ArrayList<>();
        Shell.Result result = runStateJob(script, output, operation);
        requireSuccessfulStateResult(result, operation);
    }

    /** Bounds one privileged state script before the outer shell-transport deadline expires. */
    String boundedStateCommand(String script) {
        return boundedStateCommand(script, operationTimeoutMillis);
    }

    /**
     * Bounds one privileged script so the transport deadline still has room to report a result.
     *
     * @param script        privileged script text the supervisor runs
     * @param timeoutMillis outer deadline the caller applies to the whole job
     */
    String boundedStateCommand(String script, long timeoutMillis) {
        long killGraceMillis = Math.min(1_000, Math.max(1, timeoutMillis / 10));
        long transportReserveMillis = Math.min(1_000, Math.max(1, timeoutMillis / 10));
        long scriptTimeoutMillis = Math.max(
                1,
                timeoutMillis - killGraceMillis - transportReserveMillis
        );
        String delimiter;
        do {
            delimiter = "STANDROID_TIMEOUT_" + UUID.randomUUID().toString().replace("-", "");
        } while (script.contains(delimiter));
        long processGroupGraceMillis = Math.max(1, killGraceMillis / 2);
        String supervisor = "standroid_timeout_handler() {" + STATE_LINE
                + "trap '' TERM" + STATE_LINE
                + "kill -TERM 0 2>/dev/null || :" + STATE_LINE
                + "sleep " + seconds(processGroupGraceMillis) + STATE_LINE
                + "kill -KILL 0 2>/dev/null || :" + STATE_LINE
                + "exit 124" + STATE_LINE
                + "}" + STATE_LINE
                + "trap 'standroid_timeout_handler' TERM" + STATE_LINE
                + script + STATE_LINE;
        return "command -v timeout >/dev/null 2>&1 || exit 127" + STATE_LINE
                + "timeout -k " + seconds(killGraceMillis) + " " + seconds(scriptTimeoutMillis)
                + " sh <<'" + delimiter + "'" + STATE_LINE
                + supervisor
                + delimiter + STATE_LINE;
    }

    /** Formats a positive millisecond duration using the decimal syntax accepted by Toybox. */
    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", millis / 1_000.0);
    }

    /**
     * Requires one state command to have reported success.
     *
     * <p>A non-zero status is a semantic failure of the state operation and is reported with the
     * exit status, so the backend can map it to the stable state failure vocabulary.</p>
     */
    private static void requireSuccessfulStateResult(Shell.Result result, String operation)
            throws IOException {
        if (result.getCode() != 0) {
            throw new IOException(
                    "The root shell could not " + operation + " (exit " + result.getCode() + ")"
            );
        }
    }

    /** Returns the fixed absolute path of one approved member of Managed State. */
    String stateMemberPath(ManagedStateMember member) {
        return locations.member(member).getAbsolutePath();
    }

    /** Returns the fixed absolute path of the staging base. */
    String stagingBasePath() {
        return locations.stagingBase().getAbsolutePath();
    }

    /** Returns the fixed absolute path of the Managed State root. */
    String managedStateRootPath() {
        return locations.stateRoot().getAbsolutePath();
    }

    /** Returns the ownership every staged and repaired entry has to carry. */
    private String ownerSpec() {
        return locations.applicationUid() + ":" + locations.applicationGid();
    }

    /**
     * Builds the fixed command that reads one approved member with byte-exact content.
     *
     * <p>The content travels as base64, which preserves every byte of the file including its final
     * newline. A symbolic link or a member of the wrong kind is refused instead of being read
     * through.</p>
     */
    String readStateFileScript(ManagedStateMember member) {
        requireFileMember(member);
        return stateUidGuard()
                + stateRootGuard()
                + "standroid_state=" + quote(stateMemberPath(member)) + STATE_LINE
                + "if [ ! -e \"$standroid_state\" ] && [ ! -L \"$standroid_state\" ]; then exit "
                + STATE_MISSING_EXIT_CODE + "; fi" + STATE_LINE
                + "if [ -L \"$standroid_state\" ] || [ ! -f \"$standroid_state\" ]; then exit "
                + STATE_UNSAFE_MEMBER_EXIT_CODE + "; fi" + STATE_LINE
                + "command -v base64 >/dev/null 2>&1 || exit "
                + STATE_UNSAFE_MEMBER_EXIT_CODE + STATE_LINE
                + "base64 \"$standroid_state\" || exit $?" + STATE_LINE;
    }

    /**
     * Builds the fixed command that replaces one approved member atomically.
     *
     * <p>The content is decoded inside a root-private work directory under the Managed State root,
     * then the completed file is renamed over the member on the same filesystem. Scratch paths
     * never live in the application-writable staging directory.</p>
     */
    String writeStateFileScript(ManagedStateMember member, String encodedContent) {
        requireFileMember(member);
        Objects.requireNonNull(encodedContent, "The encoded member content is required");
        String operationName = ManagedStateStaging.OPERATION_PREFIX + UUID.randomUUID();
        requireOperationName(operationName);
        String workDirectoryName = stateWorkDirectoryName(operationName);
        String memberName = member.fileName();
        String contentDelimiter;
        do {
            contentDelimiter = "STANDROID_STATE_" + UUID.randomUUID().toString().replace("-", "");
        } while (encodedContent.contains(contentDelimiter));
        return stateUidGuard()
                + stateRootWorkingDirectoryGuard()
                + "standroid_state=." + STATE_LINE
                + "if [ -e \"$standroid_state/" + memberName + "\" ]"
                + " || [ -L \"$standroid_state/" + memberName + "\" ]; then" + STATE_LINE
                + "[ -f \"$standroid_state/" + memberName + "\" ]"
                + " && [ ! -L \"$standroid_state/" + memberName + "\" ] || exit "
                + STATE_UNSAFE_MEMBER_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + rootPrivateWorkDirectoryGuard(workDirectoryName)
                + "standroid_cleanup() {" + STATE_LINE
                + "standroid_status=$?" + STATE_LINE
                + "trap - EXIT" + STATE_LINE
                + "rm -f ./state" + STATE_LINE
                + "cd .. && rmdir \"" + workDirectoryName + "\" >/dev/null 2>&1 || :" + STATE_LINE
                + "exit \"$standroid_status\"" + STATE_LINE
                + "}" + STATE_LINE
                + "trap standroid_cleanup EXIT" + STATE_LINE
                + "command -v base64 >/dev/null 2>&1 || exit "
                + STATE_COPY_EXIT_CODE + STATE_LINE
                + "base64 -d > ./state <<'" + contentDelimiter + "' || exit "
                + STATE_COPY_EXIT_CODE + STATE_LINE
                + encodedContent + STATE_LINE + contentDelimiter + STATE_LINE
                + "chmod 600 ./state || exit " + STATE_COPY_EXIT_CODE + STATE_LINE
                + "chown " + ownerSpec() + " ./state || exit " + STATE_COPY_EXIT_CODE + STATE_LINE
                + "if [ -e \"../" + memberName + "\" ] || [ -L \"../" + memberName + "\" ]; then"
                + STATE_LINE
                + "[ -f \"../" + memberName + "\" ] && [ ! -L \"../" + memberName
                + "\" ] || exit " + STATE_UNSAFE_MEMBER_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "mv -f ./state \"../" + memberName + "\" || exit "
                + STATE_COPY_EXIT_CODE + STATE_LINE
                + "exit 0" + STATE_LINE;
    }

    /** Builds the fixed command that removes one approved member without following links. */
    String removeStateMemberScript(ManagedStateMember member) {
        return stateUidGuard()
                + stateRootGuard()
                + "standroid_state=" + quote(stateMemberPath(member)) + STATE_LINE
                + "if [ ! -e \"$standroid_state\" ] && [ ! -L \"$standroid_state\" ]; then exit 0; fi"
                + STATE_LINE
                + "rm -rf \"$standroid_state\" || exit " + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + "exit 0" + STATE_LINE;
    }

    /** Builds the fixed command that checks one approved member's presence and kind. */
    String stateMemberExistsScript(ManagedStateMember member) {
        String kindTest = member.isDirectory() ? "-d" : "-f";
        return stateUidGuard()
                + stateRootGuard()
                + "standroid_state=" + quote(stateMemberPath(member)) + STATE_LINE
                + "if [ -L \"$standroid_state\" ]; then exit "
                + STATE_UNSAFE_MEMBER_EXIT_CODE + "; fi" + STATE_LINE
                + "if [ " + kindTest + " \"$standroid_state\" ]; then exit 0; fi" + STATE_LINE
                + "exit " + STATE_MISSING_EXIT_CODE + STATE_LINE;
    }

    /**
     * Builds the fixed command that snapshots Managed State into one operation-owned staging
     * directory and hands that directory to the application.
     *
     * <p>Only approved members are copied, symbolic links and special files fail the operation,
     * ownership and mode are changed only inside the staging tree, the label is established when
     * the kernel enforces SELinux, and success is reported only after ownership and kind of every
     * staged entry have been verified. The application then proves from its own process that it
     * can read and remove the tree.</p>
     */
    String stageManagedStateScript(String operationName) {
        requireOperationName(operationName);
        String indexName = ManagedStateMember.INDEX.fileName();
        return stateUidGuard()
                + stateRootGuard()
                + "standroid_state=" + quote(managedStateRootPath()) + STATE_LINE
                + "standroid_base=" + quote(stagingBasePath()) + STATE_LINE
                + stagingBaseWorkingDirectoryGuard()
                + stagingOperationWorkingDirectoryGuard(operationName)
                + "standroid_stage=." + STATE_LINE
                + "standroid_fail() {" + STATE_LINE
                + "standroid_failure=$1" + STATE_LINE
                + "rm -rf ./config.xml ./cert.pem ./key.pem ./https-cert.pem"
                + " ./https-key.pem ./index-v2" + STATE_LINE
                + "standroid_failed_stage=$(pwd -P) || exit \"$standroid_failure\"" + STATE_LINE
                + "cd .. || exit \"$standroid_failure\"" + STATE_LINE
                + "standroid_failed_parent=$(pwd -P) || exit \"$standroid_failure\"" + STATE_LINE
                + "if [ \"$standroid_failed_stage\" ="
                + " \"$standroid_base_physical/$standroid_stage_name\" ]"
                + " && [ \"$standroid_failed_parent\" = \"$standroid_base_physical\" ]"
                + " && [ ! -L \"$standroid_stage_name\" ]; then"
                + " rmdir \"$standroid_stage_name\" >/dev/null 2>&1 || :; fi" + STATE_LINE
                + "exit \"$standroid_failure\"" + STATE_LINE
                + "}" + STATE_LINE
                + "chmod 700 . || standroid_fail "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "for standroid_member in " + STATE_FILE_NAMES + "; do" + STATE_LINE
                + "standroid_source=\"$standroid_state/$standroid_member\"" + STATE_LINE
                + "if [ -L \"$standroid_source\" ]; then standroid_fail "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "if [ -e \"$standroid_source\" ]; then" + STATE_LINE
                + "[ -f \"$standroid_source\" ] || standroid_fail "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "cp \"$standroid_source\" \"./$standroid_member\" || standroid_fail "
                + STATE_COPY_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "done" + STATE_LINE
                + "standroid_index=\"$standroid_state/" + indexName + "\"" + STATE_LINE
                + "if [ -L \"$standroid_index\" ]; then standroid_fail "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "if [ -e \"$standroid_index\" ]; then" + STATE_LINE
                + "[ -d \"$standroid_index\" ] || standroid_fail "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "standroid_unsafe=$(find \"$standroid_index\" "
                + "\\( -type l -o \\( ! -type f -a ! -type d \\) \\) -print 2>/dev/null | head -n 1)"
                + STATE_LINE
                + "[ -z \"$standroid_unsafe\" ] || standroid_fail "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "cp -R \"$standroid_index\" \"./" + indexName + "\" || standroid_fail "
                + STATE_COPY_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "standroid_unsafe=$(find . \\( -type l -o \\( ! -type f -a ! -type d \\) \\)"
                + " -print 2>/dev/null | head -n 1)" + STATE_LINE
                + "[ -z \"$standroid_unsafe\" ] || standroid_fail "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "find . -type d -exec chmod 700 {} + || standroid_fail "
                + STATE_HANDOFF_EXIT_CODE + STATE_LINE
                + "find . -type f -exec chmod 600 {} + || standroid_fail "
                + STATE_HANDOFF_EXIT_CODE + STATE_LINE
                + "if [ \"$(cat " + SELINUX_ENFORCE_PATH + " 2>/dev/null)\" = \"1\" ]; then"
                + STATE_LINE
                + "command -v restorecon >/dev/null 2>&1 || standroid_fail "
                + STATE_HANDOFF_EXIT_CODE + STATE_LINE
                + "restorecon -R . >/dev/null 2>&1 || standroid_fail "
                + STATE_HANDOFF_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "chown -R " + ownerSpec() + " . || standroid_fail "
                + STATE_HANDOFF_EXIT_CODE + STATE_LINE
                + "standroid_foreign=$(find . \\( ! -user "
                + locations.applicationUid() + " -o ! -group " + locations.applicationGid()
                + " \\) -print 2>/dev/null | head -n 1)" + STATE_LINE
                + "[ -z \"$standroid_foreign\" ] || standroid_fail "
                + STATE_HANDOFF_EXIT_CODE + STATE_LINE
                + "exit 0" + STATE_LINE;
    }

    /** Builds the fixed command that removes one operation-owned staging directory. */
    String removeStagingDirectoryScript(String operationName) {
        requireOperationName(operationName);
        String stagePath = stagingBasePath() + "/" + operationName;
        return stateUidGuard()
                + "standroid_base=" + quote(stagingBasePath()) + STATE_LINE
                + "standroid_stage=" + quote(stagePath) + STATE_LINE
                + "if [ -L \"$standroid_base\" ] || [ -L \"$standroid_stage\" ]; then exit "
                + STATE_OPERATION_NAME_EXIT_CODE + "; fi" + STATE_LINE
                + "rm -rf \"$standroid_stage\" || exit " + STATE_HANDOFF_EXIT_CODE + STATE_LINE
                + "exit 0" + STATE_LINE;
    }

    /**
     * Builds the fixed command that installs the staged members into Managed State.
     *
     * <p>Only approved members are installed, the required members must be staged as regular
     * files, and a staged tree containing a symbolic link or a special file fails before live
     * state is touched. An optional regular {@code sharedpreferences.dat} file is accepted for
     * archive compatibility but is never copied to or used to modify live preferences. When the
     * index directory was not staged, the live index database is removed so stale database state
     * cannot survive a legacy archive.</p>
     */
    String installStagedManagedStateScript(String operationName) {
        requireOperationName(operationName);
        String stagePath = stagingBasePath() + "/" + operationName;
        String indexName = ManagedStateMember.INDEX.fileName();
        String uid = String.valueOf(locations.applicationUid());
        String gid = String.valueOf(locations.applicationGid());
        String workDirectoryName = stateWorkDirectoryName(operationName);
        return stateUidGuard()
                + stateRootWorkingDirectoryGuard()
                + "standroid_state=." + STATE_LINE
                + "standroid_base=" + quote(stagingBasePath()) + STATE_LINE
                + stagingBaseGuard()
                + "standroid_base_gid=$(stat -c %g \"$standroid_base\" 2>/dev/null) || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "case \"$standroid_base_gid\" in ''|*[!0-9]*) exit "
                + STATE_OPERATION_NAME_EXIT_CODE + ";; esac" + STATE_LINE
                + stagingBasePhysicalPathGuard()
                + "if [ -L \"$standroid_state\" ] || [ ! -d \"$standroid_state\" ]; then exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "standroid_stage=" + quote(stagePath) + STATE_LINE
                + "if [ -L \"$standroid_stage\" ] || [ ! -d \"$standroid_stage\" ]; then exit "
                + STATE_OPERATION_NAME_EXIT_CODE + "; fi" + STATE_LINE
                + "if ! standroid_find_empty \"$standroid_stage\" -maxdepth 0 \\( ! -user "
                + uid + " -o \\( ! -group " + gid + " -a ! -group \"$standroid_base_gid\" \\)"
                + " \\); then exit " + STATE_OPERATION_NAME_EXIT_CODE + "; fi" + STATE_LINE
                + "if ! standroid_find_empty \"$standroid_stage\" -mindepth 1 \\( ! -user "
                + uid + " -o \\( ! -group " + gid + " -a ! -group \"$standroid_base_gid\" \\)"
                + " -o -type l"
                + " -o \\( ! -type f -a ! -type d \\) \\) ; then exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "if ! standroid_find_empty \"$standroid_stage\" -mindepth 1 -maxdepth 1 \\("
                + " ! -name config.xml -a ! -name cert.pem -a ! -name key.pem"
                + " -a ! -name https-cert.pem -a ! -name https-key.pem"
                + " -a ! -name sharedpreferences.dat -a ! -name "
                + indexName + " \\) ; then exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "if [ -e \"$standroid_stage/sharedpreferences.dat\" ]"
                + " || [ -L \"$standroid_stage/sharedpreferences.dat\" ]; then" + STATE_LINE
                + "[ -f \"$standroid_stage/sharedpreferences.dat\" ]"
                + " && [ ! -L \"$standroid_stage/sharedpreferences.dat\" ]"
                + " || exit " + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "for standroid_validate_member in " + STATE_FILE_NAMES + "; do" + STATE_LINE
                + "if [ -e \"$standroid_stage/$standroid_validate_member\" ]"
                + " || [ -L \"$standroid_stage/$standroid_validate_member\" ]; then" + STATE_LINE
                + "[ -f \"$standroid_stage/$standroid_validate_member\" ]"
                + " && [ ! -L \"$standroid_stage/$standroid_validate_member\" ]"
                + " || exit " + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "done" + STATE_LINE
                + "standroid_index=\"$standroid_stage/" + indexName + "\"" + STATE_LINE
                + "if [ -e \"$standroid_index\" ] || [ -L \"$standroid_index\" ]; then" + STATE_LINE
                + "[ -d \"$standroid_index\" ] && [ ! -L \"$standroid_index\" ] || exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "if ! standroid_find_empty \"$standroid_index\" \\( -type l -o"
                + " \\( ! -type f -a ! -type d \\) \\); then exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "fi" + STATE_LINE
                + "for standroid_required in config.xml cert.pem key.pem; do" + STATE_LINE
                + "[ -f \"$standroid_stage/$standroid_required\" ]"
                + " && [ ! -L \"$standroid_stage/$standroid_required\" ]"
                + " || exit " + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "done" + STATE_LINE
                + "standroid_live_index=\"$standroid_state/" + indexName + "\"" + STATE_LINE
                + "if [ -e \"$standroid_live_index\" ] || [ -L \"$standroid_live_index\" ]; then"
                + STATE_LINE
                + "[ -d \"$standroid_live_index\" ] && [ ! -L \"$standroid_live_index\" ]"
                + " || exit " + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "for standroid_live_member in " + STATE_FILE_NAMES + "; do" + STATE_LINE
                + "standroid_live_target=\"$standroid_state/$standroid_live_member\"" + STATE_LINE
                + "if [ -e \"$standroid_live_target\" ] || [ -L \"$standroid_live_target\" ]; then"
                + STATE_LINE
                + "[ -f \"$standroid_live_target\" ] && [ ! -L \"$standroid_live_target\" ]"
                + " || exit " + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "done" + STATE_LINE
                + rootPrivateWorkDirectoryGuard(workDirectoryName)
                + "standroid_state=.." + STATE_LINE
                + "standroid_live_index=\"$standroid_state/" + indexName + "\"" + STATE_LINE
                + "standroid_index_work=\"$standroid_work/index-work\"" + STATE_LINE
                + "standroid_index_backup=\"$standroid_work/index-backup\"" + STATE_LINE
                + "standroid_cleanup() {" + STATE_LINE
                + "standroid_status=$?" + STATE_LINE
                + "trap - EXIT" + STATE_LINE
                + "rm -rf ./index-work ./stage-import ./stage-import.tar ./.install-*"
                + STATE_LINE
                + "if [ ! -e ./index-backup ] && [ ! -L ./index-backup ]; then" + STATE_LINE
                + "cd .. && rmdir \"$standroid_work_name\" >/dev/null 2>&1 || :" + STATE_LINE
                + "fi" + STATE_LINE
                + "exit \"$standroid_status\"" + STATE_LINE
                + "}" + STATE_LINE
                + "trap standroid_cleanup EXIT" + STATE_LINE
                + stagedImportSnapshotScript(operationName, indexName, uid, gid)
                + "for standroid_member in " + STATE_FILE_NAMES + "; do" + STATE_LINE
                + "if [ -e \"$standroid_stage/$standroid_member\" ]"
                + " || [ -L \"$standroid_stage/$standroid_member\" ]; then" + STATE_LINE
                + "[ -f \"$standroid_stage/$standroid_member\" ]"
                + " && [ ! -L \"$standroid_stage/$standroid_member\" ] || exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "standroid_install_temp=\"$standroid_work/.install-$standroid_member\""
                + STATE_LINE
                + "cp -P \"$standroid_stage/$standroid_member\" \"$standroid_install_temp\""
                + " || exit " + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + "[ -f \"$standroid_install_temp\" ] && [ ! -L \"$standroid_install_temp\" ]"
                + " || exit " + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "chmod 600 \"$standroid_install_temp\" || exit "
                + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + "chown " + uid + ":" + gid + " \"$standroid_install_temp\" || exit "
                + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "done" + STATE_LINE
                + "if [ -e \"$standroid_index\" ] || [ -L \"$standroid_index\" ]; then" + STATE_LINE
                + "cp -R \"$standroid_index\" \"$standroid_index_work\" || exit "
                + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + "if ! standroid_find_empty \"$standroid_index_work\" \\( -type l -o"
                + " \\( ! -type f -a ! -type d \\) \\); then exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "find \"$standroid_index_work\" -type d -exec chmod 700 {} + || exit "
                + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + "find \"$standroid_index_work\" -type f -exec chmod 600 {} + || exit "
                + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + "chown -R " + uid + ":" + gid + " \"$standroid_index_work\" || exit "
                + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "for standroid_member in " + STATE_FILE_NAMES + "; do" + STATE_LINE
                + "standroid_install_temp=\"$standroid_work/.install-$standroid_member\""
                + STATE_LINE
                + "if [ -e \"$standroid_install_temp\" ] || [ -L \"$standroid_install_temp\" ]; then"
                + STATE_LINE
                + "[ -f \"$standroid_install_temp\" ] && [ ! -L \"$standroid_install_temp\" ] || exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "standroid_live_target=\"$standroid_state/$standroid_member\"" + STATE_LINE
                + "if [ -e \"$standroid_live_target\" ] || [ -L \"$standroid_live_target\" ]; then"
                + STATE_LINE
                + "[ -f \"$standroid_live_target\" ] && [ ! -L \"$standroid_live_target\" ]"
                + " || exit " + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "mv -f \"$standroid_install_temp\""
                + " \"$standroid_live_target\" || exit " + STATE_INSTALL_EXIT_CODE
                + STATE_LINE
                + "fi" + STATE_LINE
                + "done" + STATE_LINE
                + "standroid_index=\"$standroid_stage/" + indexName + "\"" + STATE_LINE
                + "if [ -e \"$standroid_index\" ] || [ -L \"$standroid_index\" ]; then" + STATE_LINE
                + "[ -d \"$standroid_index\" ] && [ ! -L \"$standroid_index\" ] || exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "if ! standroid_find_empty \"$standroid_index\" \\( -type l -o"
                + " \\( ! -type f -a ! -type d \\) \\); then exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "fi" + STATE_LINE
                + "if [ -e \"$standroid_live_index\" ] || [ -L \"$standroid_live_index\" ]; then"
                + STATE_LINE
                + "mv \"$standroid_live_index\" \"$standroid_index_backup\" || exit "
                + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "if [ -e \"$standroid_index_work\" ] || [ -L \"$standroid_index_work\" ]; then"
                + STATE_LINE
                + "mv \"$standroid_index_work\" \"$standroid_live_index\" || {"
                + " if [ -e \"$standroid_index_backup\" ]; then"
                + " mv \"$standroid_index_backup\" \"$standroid_live_index\"; fi; exit "
                + STATE_INSTALL_EXIT_CODE + "; }" + STATE_LINE
                + "fi" + STATE_LINE
                + "for standroid_required in config.xml cert.pem key.pem; do" + STATE_LINE
                + "[ -f \"$standroid_state/$standroid_required\" ] || exit "
                + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + "done" + STATE_LINE
                + "rm -rf \"$standroid_index_backup\" || exit "
                + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + "exit 0" + STATE_LINE;
    }

    /**
     * Builds the fixed command that repairs application access to the approved members.
     *
     * <p>The command touches exactly the approved members, repairs ownership and mode, and
     * re-applies the security context on every attempt, even when ownership already matches. It
     * verifies ownership, kind, and access-relevant mode bits after repair. Every step is safe to
     * repeat, so a repair interrupted after its ownership step can be completed by a later call.</p>
     */
    String repairManagedStateScript() {
        String uid = String.valueOf(locations.applicationUid());
        String gid = String.valueOf(locations.applicationGid());
        String indexName = ManagedStateMember.INDEX.fileName();
        return stateUidGuard()
                + stateRootGuard()
                + "standroid_state=" + quote(managedStateRootPath()) + STATE_LINE
                + "if [ ! -d \"$standroid_state\" ]; then exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "for standroid_member in " + STATE_FILE_NAMES + "; do" + STATE_LINE
                + "standroid_target=\"$standroid_state/$standroid_member\"" + STATE_LINE
                + "if [ -L \"$standroid_target\" ]; then exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "if [ -e \"$standroid_target\" ]; then" + STATE_LINE
                + "[ -f \"$standroid_target\" ] || exit " + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "chown " + uid + ":" + gid + " \"$standroid_target\" || exit "
                + STATE_REPAIR_EXIT_CODE + STATE_LINE
                + "chmod 600 \"$standroid_target\" || exit " + STATE_REPAIR_EXIT_CODE + STATE_LINE
                + "command -v restorecon >/dev/null 2>&1 || exit " + STATE_REPAIR_EXIT_CODE + STATE_LINE
                + "restorecon \"$standroid_target\" >/dev/null 2>&1 || exit "
                + STATE_REPAIR_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "done" + STATE_LINE
                + "standroid_foreign=$(find \"$standroid_state\" -maxdepth 1 \\( -name config.xml"
                + " -o -name cert.pem -o -name key.pem -o -name https-cert.pem"
                + " -o -name https-key.pem \\) \\( ! -user " + uid + " -o ! -group " + gid
                + " -o ! -perm 0600 \\) -print 2>/dev/null | head -n 1)" + STATE_LINE
                + "[ -z \"$standroid_foreign\" ] || exit " + STATE_REPAIR_EXIT_CODE + STATE_LINE
                + "standroid_index=\"$standroid_state/" + indexName + "\"" + STATE_LINE
                + "if [ -L \"$standroid_index\" ]; then exit " + STATE_UNSAFE_TREE_EXIT_CODE + "; fi"
                + STATE_LINE
                + "if [ -e \"$standroid_index\" ]; then" + STATE_LINE
                + "[ -d \"$standroid_index\" ] || exit " + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "standroid_unsafe=$(find \"$standroid_index\" \\( -type l -o \\( ! -type f -a ! -type d \\) \\)"
                + " -print 2>/dev/null | head -n 1)" + STATE_LINE
                + "[ -z \"$standroid_unsafe\" ] || exit " + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "chown -R " + uid + ":" + gid + " \"$standroid_index\" || exit "
                + STATE_REPAIR_EXIT_CODE + STATE_LINE
                + "find \"$standroid_index\" -type d -exec chmod 700 {} + || exit "
                + STATE_REPAIR_EXIT_CODE + STATE_LINE
                + "find \"$standroid_index\" -type f -exec chmod 600 {} + || exit "
                + STATE_REPAIR_EXIT_CODE + STATE_LINE
                + "command -v restorecon >/dev/null 2>&1 || exit " + STATE_REPAIR_EXIT_CODE + STATE_LINE
                + "restorecon -R \"$standroid_index\" >/dev/null 2>&1 || exit "
                + STATE_REPAIR_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "standroid_foreign=$(find \"$standroid_index\" \\( -type l"
                + " -o \\( ! -type f -a ! -type d \\) -o ! -user " + uid
                + " -o ! -group " + gid + " -o \\( -type d ! -perm 0700 \\)"
                + " -o \\( -type f ! -perm 0600 \\) \\) -print 2>/dev/null | head -n 1)"
                + STATE_LINE
                + "[ -z \"$standroid_foreign\" ] || exit " + STATE_REPAIR_EXIT_CODE + STATE_LINE
                + "exit 0" + STATE_LINE;
    }

    /** Builds the shell prologue that refuses to run a state command without real UID 0. */
    private static String stateUidGuard() {
        return "[ \"$(id -u)\" != \"0\" ] && exit " + STATE_UID_GUARD_EXIT_CODE + STATE_LINE;
    }

    /** Rejects redirected or non-directory state roots before a privileged member operation. */
    private String stateRootGuard() {
        return "standroid_state_root=" + quote(managedStateRootPath()) + STATE_LINE
                + "if [ -L \"$standroid_state_root\" ] || { [ -e \"$standroid_state_root\" ]"
                + " && [ ! -d \"$standroid_state_root\" ]; }; then exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE;
    }

    /** Pins privileged state work to the checked Managed State directory. */
    private String stateRootWorkingDirectoryGuard() {
        File stateRoot = locations.stateRoot();
        File parent = stateRoot.getParentFile();
        if (parent == null) {
            throw new IllegalStateException("The Managed State root must have a parent directory");
        }
        String name = stateRoot.getName();
        return stateRootGuard()
                + "standroid_state_parent=" + quote(parent.getAbsolutePath()) + STATE_LINE
                + "standroid_state_name=" + quote(name) + STATE_LINE
                + "cd \"$standroid_state_parent\" || exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "standroid_state_parent_physical=$(pwd -P) || exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "if [ -L \"$standroid_state_name\" ]"
                + " || [ ! -d \"$standroid_state_name\" ]; then exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "cd \"$standroid_state_name\" || exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "standroid_state_physical=$(pwd -P) || exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_state_physical\" ="
                + " \"$standroid_state_parent_physical/$standroid_state_name\" ] || exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "standroid_state=." + STATE_LINE;
    }

    /** Creates and pins one root-owned, private work directory on the state filesystem. */
    private String rootPrivateWorkDirectoryGuard(String workDirectoryName) {
        return "standroid_work_name=" + quote(workDirectoryName) + STATE_LINE
                + "if [ -e \"$standroid_work_name\" ]"
                + " || [ -L \"$standroid_work_name\" ]; then exit "
                + STATE_OPERATION_NAME_EXIT_CODE + "; fi" + STATE_LINE
                + "(umask 077 && mkdir \"$standroid_work_name\") || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "if [ -L \"$standroid_work_name\" ]"
                + " || [ ! -d \"$standroid_work_name\" ]; then exit "
                + STATE_OPERATION_NAME_EXIT_CODE + "; fi" + STATE_LINE
                + "cd \"$standroid_work_name\" || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "standroid_work_physical=$(pwd -P) || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_work_physical\" ="
                + " \"$standroid_state_physical/$standroid_work_name\" ] || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "standroid_work_metadata=$(stat -c %u:%a . 2>/dev/null) || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_work_metadata\" = 0:700 ] || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "standroid_work=." + STATE_LINE
                + "standroid_state=.." + STATE_LINE;
    }

    /** Resolves the checked staging base so an operation can be pinned by its physical path. */
    private String stagingBasePhysicalPathGuard() {
        File stagingBase = locations.stagingBase();
        File parent = stagingBase.getParentFile();
        if (parent == null) {
            throw new IllegalStateException("The Managed State staging base must have a parent");
        }
        return "standroid_base_parent=" + quote(parent.getAbsolutePath()) + STATE_LINE
                + "standroid_base_name=" + quote(stagingBase.getName()) + STATE_LINE
                + "standroid_base_parent_physical=$(cd \"$standroid_base_parent\""
                + " && pwd -P) || exit " + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "if [ -L \"$standroid_base_name\" ]; then exit "
                + STATE_OPERATION_NAME_EXIT_CODE + "; fi" + STATE_LINE
                + "standroid_base_physical=$(cd \"$standroid_base\" && pwd -P) || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_base_physical\" ="
                + " \"$standroid_base_parent_physical/$standroid_base_name\" ] || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE;
    }

    /** Copies only approved staged members from a directory pinned by the shell's CWD. */
    private String stagedImportSnapshotScript(
            String operationName,
            String indexName,
            String uid,
            String gid
    ) {
        return "mkdir ./stage-import || exit " + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "standroid_stage_expected=\"$standroid_base_physical/" + operationName + "\""
                + STATE_LINE
                + "( cd \"$standroid_stage\" || exit " + STATE_OPERATION_NAME_EXIT_CODE
                + STATE_LINE
                + "standroid_stage_pinned=$(pwd -P) || exit " + STATE_OPERATION_NAME_EXIT_CODE
                + STATE_LINE
                + "[ \"$standroid_stage_pinned\" = \"$standroid_stage_expected\" ]"
                + " || exit " + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "if ! standroid_find_empty . -maxdepth 0 \\( ! -user " + uid
                + " -o \\( ! -group " + gid + " -a ! -group \"$standroid_base_gid\" \\)"
                + " \\); then exit " + STATE_OPERATION_NAME_EXIT_CODE + "; fi" + STATE_LINE
                + "chmod 700 . || exit " + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "if ! standroid_find_empty . -mindepth 1 \\( ! -user " + uid
                + " -o \\( ! -group " + gid + " -a ! -group \"$standroid_base_gid\" \\)"
                + " -o -type l -o \\( ! -type f -a ! -type d \\) \\); then exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "if ! standroid_find_empty . -mindepth 1 -maxdepth 1 \\("
                + " ! -name config.xml -a ! -name cert.pem -a ! -name key.pem"
                + " -a ! -name https-cert.pem -a ! -name https-key.pem"
                + " -a ! -name sharedpreferences.dat -a ! -name " + indexName
                + " \\); then exit " + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "if [ -e ./sharedpreferences.dat ] || [ -L ./sharedpreferences.dat ]; then"
                + STATE_LINE
                + "[ -f ./sharedpreferences.dat ] && [ ! -L ./sharedpreferences.dat ]"
                + " || exit " + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "for standroid_validate_member in " + STATE_FILE_NAMES + "; do" + STATE_LINE
                + "if [ -e \"./$standroid_validate_member\" ]"
                + " || [ -L \"./$standroid_validate_member\" ]; then" + STATE_LINE
                + "[ -f \"./$standroid_validate_member\" ]"
                + " && [ ! -L \"./$standroid_validate_member\" ] || exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "done" + STATE_LINE
                + "if [ -e ./" + indexName + " ] || [ -L ./" + indexName + " ]; then"
                + STATE_LINE
                + "[ -d ./" + indexName + " ] && [ ! -L ./" + indexName + " ] || exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "if ! standroid_find_empty ./" + indexName
                + " \\( -type l -o \\( ! -type f -a ! -type d \\) \\); then exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + "; fi" + STATE_LINE
                + "fi" + STATE_LINE
                + "for standroid_required in config.xml cert.pem key.pem; do" + STATE_LINE
                + "[ -f \"./$standroid_required\" ]"
                + " && [ ! -L \"./$standroid_required\" ] || exit "
                + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "done" + STATE_LINE
                + "set --" + STATE_LINE
                + "for standroid_member in " + STATE_FILE_NAMES + "; do" + STATE_LINE
                + "if [ -e \"./$standroid_member\" ] || [ -L \"./$standroid_member\" ]; then"
                + STATE_LINE
                + "set -- \"$@\" \"./$standroid_member\"" + STATE_LINE
                + "fi" + STATE_LINE
                + "done" + STATE_LINE
                + "if [ -e ./" + indexName + " ] || [ -L ./" + indexName + " ]; then"
                + STATE_LINE + "set -- \"$@\" \"./" + indexName + "\"" + STATE_LINE
                + "fi" + STATE_LINE
                + "command -v tar >/dev/null 2>&1 || exit " + STATE_INSTALL_EXIT_CODE
                + STATE_LINE
                + "tar -cf - \"$@\" || exit " + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + ") > ./stage-import.tar || exit " + STATE_UNSAFE_TREE_EXIT_CODE + STATE_LINE
                + "tar -xf ./stage-import.tar -C ./stage-import || exit "
                + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + "rm -f ./stage-import.tar || exit " + STATE_INSTALL_EXIT_CODE + STATE_LINE
                + "standroid_stage=./stage-import" + STATE_LINE
                + "standroid_index=\"$standroid_stage/" + indexName + "\"" + STATE_LINE;
    }

    /** Pins the shared staging base before any privileged staging output is created. */
    private String stagingBaseWorkingDirectoryGuard() {
        File stagingBase = locations.stagingBase();
        File parent = stagingBase.getParentFile();
        if (parent == null) {
            throw new IllegalStateException("The Managed State staging base must have a parent");
        }
        return stagingBaseGuard()
                + "standroid_base_parent=" + quote(parent.getAbsolutePath()) + STATE_LINE
                + "standroid_base_name=" + quote(stagingBase.getName()) + STATE_LINE
                + "cd \"$standroid_base_parent\" || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "standroid_base_parent_physical=$(pwd -P) || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "if [ -L \"$standroid_base_name\" ]"
                + " || [ ! -d \"$standroid_base_name\" ]; then exit "
                + STATE_OPERATION_NAME_EXIT_CODE + "; fi" + STATE_LINE
                + "cd \"$standroid_base_name\" || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "standroid_base_physical=$(pwd -P) || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_base_physical\" ="
                + " \"$standroid_base_parent_physical/$standroid_base_name\" ] || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "standroid_base=." + STATE_LINE;
    }

    /** Creates and pins one fresh operation directory below the checked staging base. */
    private String stagingOperationWorkingDirectoryGuard(String operationName) {
        return "standroid_stage_name=" + quote(operationName) + STATE_LINE
                + "if [ -e \"$standroid_stage_name\" ]"
                + " || [ -L \"$standroid_stage_name\" ]; then exit "
                + STATE_OPERATION_NAME_EXIT_CODE + "; fi" + STATE_LINE
                + "(umask 077 && mkdir \"$standroid_stage_name\") || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "if [ -L \"$standroid_stage_name\" ]"
                + " || [ ! -d \"$standroid_stage_name\" ]; then exit "
                + STATE_OPERATION_NAME_EXIT_CODE + "; fi" + STATE_LINE
                + "cd \"$standroid_stage_name\" || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "standroid_stage_physical=$(pwd -P) || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_stage_physical\" ="
                + " \"$standroid_base_physical/$standroid_stage_name\" ] || exit "
                + STATE_OPERATION_NAME_EXIT_CODE + STATE_LINE;
    }

    /** Names one non-colliding scratch directory within the Managed State root. */
    private String stateWorkDirectoryName(String operationName) {
        return ".standroid-managed-state-" + locations.applicationUid() + "-" + operationName;
    }

    /**
     * Requires an app-owned, private staging base. Android cache directories can inherit a
     * cache-specific GID and setgid bit, so the guard checks the owner and effective access bits
     * without requiring the application's primary GID.
     */
    private String stagingBaseGuard() {
        String uid = String.valueOf(locations.applicationUid());
        return "standroid_find_empty() { standroid_find_output=$(find \"$@\" -print 2>/dev/null)"
                + " || return 1; [ -z \"$standroid_find_output\" ]; }" + STATE_LINE
                + "if [ -L \"$standroid_base\" ] || [ ! -d \"$standroid_base\" ]; then exit "
                + STATE_OPERATION_NAME_EXIT_CODE + "; fi" + STATE_LINE
                + "if ! standroid_find_empty \"$standroid_base\" -maxdepth 0 \\( ! -user "
                + uid + " -o ! -perm -0700 \\); then exit "
                + STATE_OPERATION_NAME_EXIT_CODE + "; fi" + STATE_LINE;
    }

    private static String quote(String value) {
        return RootShellEncoder.quote(value);
    }

    /** Rejects a member that has no single-file representation. */
    private static void requireFileMember(ManagedStateMember member) {
        Objects.requireNonNull(member, "The managed state member is required");
        if (!member.isFile()) {
            throw new IllegalArgumentException(
                    "The member " + member.fileName() + " is not a state file"
            );
        }
    }

    /**
     * Rejects an operation name that this transport would not generate.
     *
     * <p>The check happens here as well as inside the shell command, so a caller can never smuggle
     * a path, a parent reference, or a symbolic link into a privileged command.</p>
     */
    private static void requireOperationName(String operationName) {
        Objects.requireNonNull(operationName, "The operation name is required");
        if (!OPERATION_NAME_PATTERN.matcher(operationName).matches()) {
            throw new IllegalArgumentException("Unsupported staging operation name");
        }
    }

    /**
     * Encodes raw member content as base64 for transport through the root shell.
     *
     * <p>The application supports API level 23, so Guava supplies the encoder instead of the
     * newer {@code java.util.Base64} API.</p>
     */
    static String encodeStateBase64(byte[] contents) {
        Objects.requireNonNull(contents, "The member content is required");
        return STATE_BASE64.encode(contents);
    }

    /**
     * Decodes the base64 lines one state read returned.
     *
     * <p>Line breaks and surrounding whitespace are transport framing, not content, so the lines
     * are joined before decoding and every other character has to belong to the alphabet. Malformed
     * transport reports a failure instead of returning partial content.</p>
     */
    static byte[] decodeStateBase64(List<String> lines) throws IOException {
        StringBuilder encoded = new StringBuilder();
        for (String line : lines) {
            if (line == null) {
                throw new IOException("The root shell returned malformed state content");
            }
            for (int index = 0; index < line.length(); index++) {
                char character = line.charAt(index);
                if (character == ' ' || character == '\t'
                        || character == '\r' || character == '\n') {
                    continue;
                }
                encoded.append(character);
            }
        }
        if ((encoded.length() & 3) != 0) {
            throw new IOException("The root shell returned truncated state content");
        }

        String encodedText = encoded.toString();
        try {
            byte[] decoded = STATE_BASE64.decode(encodedText);
            if (!encodedText.equals(STATE_BASE64.encode(decoded))) {
                throw new IOException("The root shell returned malformed state content");
            }
            return decoded;
        } catch (IllegalArgumentException malformed) {
            throw new IOException("The root shell returned malformed state content", malformed);
        }
    }

    @Override
    public void close() {
        try {
            shell.close();
        } catch (IOException | RuntimeException e) {
            // libsu could not flush its exit command, or it failed with an unchecked state
            // error; either way terminate the shell process directly so a failed close never
            // leaves a root shell alive behind the transport.
            process.destroy();
        }
    }

    /**
     * Closes the transport after a failed helper operation.
     *
     * <p>The cleanup runs through {@link #close()}, so a shell that libsu cannot close cleanly
     * still has its underlying root transport process destroyed instead of staying alive behind
     * an abandoned transport. A failure of that teardown itself is swallowed: the caller has to
     * receive the timeout or interruption that actually ended the operation, because the failure
     * of one helper operation must never be reported as something else.</p>
     *
     * <p>The transport is marked invalidated before the cleanup runs, so
     * {@link #determineExitStatusProvenance(int)} can tell an answer that merely stayed
     * unreadable apart from a probe whose failure closed the transport.</p>
     */
    private void closeAfterFailedOperation() {
        transportInvalidatedByFailedOperation = true;
        try {
            close();
        } catch (RuntimeException ignored) {
            // The primary timeout or interruption outcome outranks a teardown failure.
        }
    }

    /**
     * Reports whether this transport can still run helper operations after a failed probe.
     *
     * <p>A failed helper operation is only survivable while the shell behind the transport is
     * still usable. Our own teardown marks the transport invalidated once it has closed a shell
     * whose operation timed out or was interrupted. libsu additionally releases a shell whose
     * process died and then answers the running job with a failed result instead of throwing, so
     * the underlying shell's own liveness has to answer for that case. Asking {@link
     * Shell#isAlive()} keeps the verdict tied to the real transport state, and libsu may itself
     * observe and release a process it finds dead while answering.</p>
     */
    private boolean isTransportUsable() {
        if (transportInvalidatedByFailedOperation) {
            return false;
        }
        try {
            return shell.isAlive();
        } catch (RuntimeException unusable) {
            return false;
        }
    }

    /** Reads the effective user id of this shell, used to verify real UID-0 execution. */
    String currentUid() throws IOException {
        List<String> output = runCommand("id -u");
        String uid = output.isEmpty() ? "" : output.get(0).trim();
        if (uid.isEmpty()) {
            throw new IOException("Could not read the shell user id");
        }
        return uid;
    }

    private List<String> runCommand(String command) throws IOException {
        List<String> output = new ArrayList<>();
        Shell.Result result = runJob(command, output, new ArrayList<>());
        if (result.getCode() != 0) {
            throw new IOException("The root shell command failed");
        }
        return output;
    }

    private Shell.Result runJob(String command, List<String> stdout, List<String> stderr)
            throws IOException {
        return runJob(command, stdout, stderr, operationTimeoutMillis);
    }

    /**
     * Runs one job with an explicit deadline.
     *
     * <p>A job that exceeds its deadline invalidates the transport, because a shell that ignored
     * one deadline cannot be trusted to answer later operations in order.</p>
     *
     * @param timeoutMillis deadline for the job and for its result to arrive
     */
    private Shell.Result runJob(
            String command,
            List<String> stdout,
            List<String> stderr,
            long timeoutMillis
    ) throws IOException {
        Future<Shell.Result> future = OPERATION_EXECUTOR.submit(
                () -> shell.newJob().add(command).to(stdout, stderr).exec()
        );
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            closeAfterFailedOperation();
            throw new IOException("The root shell operation timed out", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("The root shell operation failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            closeAfterFailedOperation();
            throw new IOException("The root shell operation was interrupted", e);
        }
    }

    /** Builds the single audited command that lists readable process identity entries. */
    static String processListScript() {
        return "for standroid_proc in /proc/[0-9]*\n"
                + "do\n"
                + "echo " + PID_MARKER + "${standroid_proc#/proc/}\n"
                + "readlink \"$standroid_proc/exe\" 2>/dev/null\n"
                + "echo " + STAT_MARKER + "\n"
                + "cat \"$standroid_proc/stat\" 2>/dev/null\n"
                + "done\n"
                + "true\n";
    }

    /**
     * Parses the process list produced by {@link #processListScript()}.
     *
     * <p>Entries whose executable link or stat line cannot be read are still reported, with a
     * {@code null} executable path or a zero start time, so callers can tell "unreadable" apart
     * from "absent". An unreadable stat line is detected by the {@link #PID_MARKER} that starts
     * the next entry, so a missing stat entry can never consume the following process.</p>
     */
    static List<ProcessEntry> parseProcessList(List<String> lines) {
        List<ProcessEntry> entries = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (!line.startsWith(PID_MARKER)) {
                index++;
                continue;
            }
            int pid = parsePositiveInt(line.substring(PID_MARKER.length()).trim());
            index++;
            String executablePath = null;
            if (index < lines.size() && !lines.get(index).startsWith(STAT_MARKER)) {
                executablePath = emptyToNull(lines.get(index).trim());
                index++;
            }
            if (index < lines.size() && lines.get(index).startsWith(STAT_MARKER)) {
                index++;
            }
            String statLine = null;
            if (index < lines.size() && !lines.get(index).startsWith(PID_MARKER)) {
                statLine = lines.get(index);
                index++;
            }
            if (pid <= 0) {
                continue;
            }
            entries.add(new ProcessEntry(pid, executablePath, parseStartTimeTicks(statLine)));
        }
        return entries;
    }

    /**
     * Extracts the Linux process start time from one {@code /proc/<pid>/stat} line.
     *
     * <p>The second field is the executable name in parentheses and may contain spaces, so the
     * parse drops everything up to the last closing parenthesis before counting fields.</p>
     */
    static long parseStartTimeTicks(String statLine) {
        if (statLine == null) {
            return 0;
        }
        int closingParenthesis = statLine.lastIndexOf(')');
        if (closingParenthesis < 0) {
            return 0;
        }
        String[] fields = statLine.substring(closingParenthesis + 1).trim().split("\\s+");
        if (fields.length <= START_TIME_FIELD_INDEX) {
            return 0;
        }
        try {
            return Math.max(Long.parseLong(fields[START_TIME_FIELD_INDEX]), 0);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * Extracts the parent process identifier from one {@code /proc/<pid>/stat} line.
     *
     * <p>The second field is the executable name in parentheses and may contain spaces, so the
     * parse drops everything up to the last closing parenthesis before reading the parent field.
     * An unreadable or malformed line reports {@code 0}, which callers treat as unprovable.</p>
     */
    static int parseParentProcessId(String statLine) {
        if (statLine == null) {
            return 0;
        }
        int closingParenthesis = statLine.lastIndexOf(')');
        if (closingParenthesis < 0) {
            return 0;
        }
        String[] fields = statLine.substring(closingParenthesis + 1).trim().split("\s+");
        if (fields.length <= PARENT_PROCESS_FIELD_INDEX) {
            return 0;
        }
        return parsePositiveInt(fields[PARENT_PROCESS_FIELD_INDEX]);
    }

    /** Finds the private run token inside one NUL-separated environment listing. */
    /**
     * Requires one caller-supplied folder path to be usable as a privileged shell argument.
     *
     * <p>Only an absolute path names a folder the transport can work with, and a path carrying a
     * NUL byte has no shell representation at all. The path is single-quoted before it reaches the
     * shell, so no further content check is needed.</p>
     */
    private static void requireAbsoluteFolderPath(String path) {
        Objects.requireNonNull(path, "The folder path is required");
        if (path.isEmpty() || path.charAt(0) != '/' || path.indexOf(0) >= 0) {
            throw new IllegalArgumentException("The folder path must be an absolute path");
        }
    }

    /**
     * Maps one failed folder-operation status to its stable failure reason.
     *
     * <p>The status values come from the scripts this transport builds. Every other value -
     * including a shell that did not run as real UID 0 - reports an access failure, because the
     * operation produced no trustworthy verdict.</p>
     */
    private static RootFolderOperationException folderOperationFailure(
            Shell.Result result,
            String operation
    ) {
        int code = result == null ? Shell.Result.JOB_NOT_EXECUTED : result.getCode();
        FolderOperationFailure failure;
        switch (code) {
            case STATE_UID_GUARD_EXIT_CODE:
                failure = FolderOperationFailure.FOLDER_ACCESS_FAILED;
                break;
            case FOLDER_UNSAFE_EXIT_CODE:
                failure = FolderOperationFailure.FOLDER_MEMBER_UNSAFE;
                break;
            case FOLDER_LIMIT_EXIT_CODE:
                failure = FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED;
                break;
            case 124:
                failure = FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED;
                break;
            default:
                failure = FolderOperationFailure.FOLDER_ACCESS_FAILED;
        }
        return new RootFolderOperationException(
                failure,
                "The root shell could not " + operation + " (exit " + code + ")"
        );
    }

    /**
     * Decodes the base64 payload one folder operation returned.
     *
     * <p>Base64 keeps every byte of the member intact, including a missing final newline, and
     * keeps the transport line-oriented for any member content.</p>
     */
    private static byte[] decodeFolderBase64(List<String> output, String operation)
            throws IOException {
        StringBuilder encoded = new StringBuilder();
        for (String line : output) {
            encoded.append(line.trim());
        }
        try {
            return STATE_BASE64.decode(encoded);
        } catch (IllegalArgumentException malformed) {
            throw new RootFolderOperationException(
                    FolderOperationFailure.FOLDER_ACCESS_FAILED,
                    "The root shell returned an unreadable payload for " + operation,
                    malformed
            );
        }
    }

    /**
     * Decodes the relative conflict paths one privileged scan returned.
     *
     * <p>Each result is verified to stay inside the scanned folder and to name a real entry, so a
     * script that returned anything unexpected cannot widen what the caller reports. Paths beyond
     * the match or output budget fail the whole result instead of shortening it.</p>
     */
    private static ConflictDiscoveryResult decodeConflictDiscovery(List<String> output)
            throws IOException {
        List<String> relativePaths = new ArrayList<>();
        int totalCharacters = 0;
        for (String line : output) {
            String encoded = line.trim();
            if (encoded.isEmpty()) {
                continue;
            }
            totalCharacters += encoded.length();
            if (totalCharacters > FOLDER_SCAN_MAX_OUTPUT_CHARS) {
                throw new RootFolderOperationException(
                        FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                        "The conflict scan exceeded its output budget"
                );
            }
            String relativePath = new String(
                    decodeFolderBase64(Collections.singletonList(encoded), "scan for conflict files"),
                    StandardCharsets.UTF_8
            );
            if (!isSafeRelativePath(relativePath)) {
                throw new RootFolderOperationException(
                        FolderOperationFailure.FOLDER_MEMBER_UNSAFE,
                        "The conflict scan returned a path outside the configured folder"
                );
            }
            if (relativePaths.size() >= FOLDER_SCAN_MAX_MATCHES) {
                throw new RootFolderOperationException(
                        FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                        "The conflict scan exceeded its match budget"
                );
            }
            relativePaths.add(relativePath);
        }
        return ConflictDiscoveryResult.of(relativePaths);
    }

    /** Reports whether one scan result stays inside its folder and could name a real entry. */
    private static boolean isSafeRelativePath(String path) {
        if (path.isEmpty() || path.charAt(0) == '/' || path.endsWith("/")
                || path.indexOf(0) >= 0) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes the script report one dispatch returned.
     *
     * <p>Every line carries one base64-encoded script name and its exit status, so a name with a
     * space cannot be confused with the status and the caller can attribute a failure to the
     * script that produced it.</p>
     */
    private static List<FolderScriptOutcome> decodeFolderScriptOutcomes(List<String> output)
            throws IOException {
        List<FolderScriptOutcome> outcomes = new ArrayList<>();
        int totalCharacters = 0;
        for (String line : output) {
            String report = line.trim();
            if (report.isEmpty()) {
                continue;
            }
            totalCharacters += report.length();
            if (totalCharacters > FOLDER_SCRIPT_MAX_OUTPUT_CHARS) {
                throw new RootFolderOperationException(
                        FolderOperationFailure.SCRIPT_DISPATCH_FAILED,
                        "The script dispatch returned more output than its budget allows"
                );
            }
            int separator = report.indexOf(' ');
            if (separator <= 0) {
                throw new RootFolderOperationException(
                        FolderOperationFailure.SCRIPT_DISPATCH_FAILED,
                        "The script dispatch returned an unreadable result"
                );
            }
            String scriptName = new String(
                    decodeFolderBase64(
                            Collections.singletonList(report.substring(0, separator)),
                            "run the approved folder scripts"
                    ),
                    StandardCharsets.UTF_8
            );
            // A file name may legally contain a line break, and the report carries the name
            // base64-encoded, so one record still names exactly one script. Only a name that
            // could not have come from a direct child entry is refused.
            if (scriptName.isEmpty() || scriptName.indexOf('/') >= 0
                    || scriptName.indexOf(0) >= 0) {
                throw new RootFolderOperationException(
                        FolderOperationFailure.SCRIPT_DISPATCH_FAILED,
                        "The script dispatch returned an unusable script name"
                );
            }
            int exitStatus;
            try {
                exitStatus = Integer.parseInt(report.substring(separator + 1).trim());
            } catch (NumberFormatException notANumber) {
                throw new RootFolderOperationException(
                        FolderOperationFailure.SCRIPT_DISPATCH_FAILED,
                        "The script dispatch returned an unreadable exit status"
                );
            }
            outcomes.add(FolderScriptOutcome.of(scriptName, exitStatus));
        }
        return outcomes;
    }

    /**
     * Builds the read-only writeability probe for one candidate folder.
     *
     * <p>The probe creates nothing inside the candidate. A read-only mount still fails the write
     * test for the root identity, because the kernel refuses write access to a directory on a
     * read-only filesystem even for a privileged caller.</p>
     */
    String folderWriteabilityScript(String candidatePath) {
        return stateUidGuard()
                + "standroid_folder=" + quote(candidatePath) + STATE_LINE
                + "if [ ! -d \"$standroid_folder\" ]; then exit "
                + FOLDER_UNKNOWN_EXIT_CODE + "; fi" + STATE_LINE
                + "if [ ! -w \"$standroid_folder\" ] || [ ! -x \"$standroid_folder\" ]; then exit "
                + FOLDER_READ_ONLY_EXIT_CODE + "; fi" + STATE_LINE
                + "exit 0" + STATE_LINE;
    }

    /**
     * Builds the bounded conflict scan for one configured folder.
     *
     * <p>The walk uses shell pathname expansion instead of {@code find}, so it needs no command
     * beyond the compatibility floor of the supported Android versions. Every entry stays inside
     * the folder: symbolic links are neither followed nor reported, the versioning directory of the
     * folder root is skipped, and the entry, match, output, and time budgets end the whole scan
     * with a typed limit failure instead of a shorter list. Each budget is enforced while the walk
     * runs, so a directory holding millions of entries cannot make the transport collect more
     * output than the budget allows.</p>
     *
     * <p>Work is done relative to a directory identity rather than to a pathname the caller can
     * exchange. The walk records the identity of the object the configured pathname names, moves
     * its working directory into that pathname, and compares the identity of the working directory
     * the kernel then reports with the recorded one; the rest of the scan runs inside that pinned
     * directory and enters a nested directory only by its name inside an already pinned parent,
     * after recording the identity of the name it is about to use and comparing that identity with
     * the working directory it reached. A caller who controls the folder and exchanges an entry for
     * a symbolic link or a different object between those steps therefore ends the scan with the
     * typed unsafe failure instead of redirecting the walk out of the folder, and the comparison is
     * repeated after the walk returns from a directory, so a directory exchanged while the walk was
     * inside it cannot make the walk continue in its replacement. The configured folder is treated the same way: the name is read without following a link, a name that is not a plain directory (a symbolic link included) ends the scan with the typed unsafe failure, and the object the walk then enters by that name must be the object whose identity was recorded. A folder the caller configured as a link to its real location therefore cannot be scanned, because the walk cannot tell such a link apart from one a concurrent writer put there; the caller has to configure the real directory. Only the identity of an object is compared, so a directory that is renamed out of the folder and reached again through a link of the same name is indistinguishable from one that never moved; the walk guarantees that it only ever enters objects it saw under the folder's own names. Only the identity
     * of an object is compared, so a directory that is renamed out of the folder and reached again
     * through a link of the same name is indistinguishable from one that never moved; the walk
     * guarantees that it only ever enters objects it saw under the folder's own names.</p>
     *
     * <p>Matched paths travel as one base64 record per line, relative to the configured folder, so
     * names that contain spaces, quotes, or line breaks survive the transport unchanged. The
     * encoder removes the line breaks a Base64 implementation may insert, because the decoder reads
     * exactly one record per line and a wrapped record would arrive as several unusable ones.
     * Toybox Base64 wraps long input by default and its option set is not guaranteed across the
     * supported Android versions, so only {@code tr}, a command every Android tool environment
     * provides, performs the unwrapping. The kind of a matched member is checked once more
     * immediately before its record is emitted, so a member exchanged for a symbolic link while the
     * walk encoded it is dropped instead of being reported.</p>
     *
     * <p>Every directory spends the entry budget before its pathname expansion runs, so the names
     * one expansion builds are bounded by that budget, and the count that guards it reads the
     * listing through a pipe, which buffers no more than one line at a time. Two bounds are
     * reported rather than claimed: the wall-clock budget is checked before and after the count of
     * a directory but cannot interrupt it, so one directory can hold the walk for the duration of
     * one listing, and the expansion of that listing builds all of its names before the loop body
     * can stop, so the peak allocation of a directory is its entry budget worth of names. Closing
     * both needs an enumeration that reports entries in framed records as it reads them; the
     * compatibility floor has no approved command for that, and the commands that could stream a
     * listing cannot carry a name that contains a line break. The narrowest viable alternative is a
     * primitive that frames each name with a NUL byte, which no filename can contain, so a reader
     * could consume a listing incrementally without ambiguity; the approved transport has no such
     * primitive, and adding one is a scope extension rather than part of this walk.</p>
     */
    String conflictDiscoveryScript(String folderRoot) {
        long budgetSeconds = Math.max(1, FOLDER_SCAN_BUDGET_MILLIS / 1_000);
        String conflictName = "*.sync-conflict-"
                + "[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]"
                + "-[0-9][0-9][0-9][0-9][0-9][0-9]"
                + "-[0-9A-Za-z][0-9A-Za-z][0-9A-Za-z][0-9A-Za-z][0-9A-Za-z][0-9A-Za-z][0-9A-Za-z]*";
        return stateUidGuard()
                + "standroid_folder=" + quote(folderRoot) + STATE_LINE
                + "if [ ! -d \"$standroid_folder\" ]; then exit " + FOLDER_ACCESS_EXIT_CODE + "; fi" + STATE_LINE
                + "standroid_deadline=$(( $(date +%s) + " + budgetSeconds + " ))" + STATE_LINE
                + "command -v base64 >/dev/null 2>&1 || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "command -v tr >/dev/null 2>&1 || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "command -v ls >/dev/null 2>&1 || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "command -v wc >/dev/null 2>&1 || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_entries=0" + STATE_LINE
                + "standroid_matches=0" + STATE_LINE
                + "standroid_output=0" + STATE_LINE
                + "standroid_identity() {" + STATE_LINE
                + "set -- $(ls -lnid \"$1\" 2>/dev/null) || return 1" + STATE_LINE
                + "[ -n \"$1\" ] || return 1" + STATE_LINE
                + "echo \"$1 $2\"" + STATE_LINE
                + "}" + STATE_LINE
                + "standroid_folder_identity=$(standroid_identity \"$standroid_folder\") || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "case \"$standroid_folder_identity\" in *\" d\"*) : ;; *) exit " + FOLDER_UNSAFE_EXIT_CODE + " ;; esac" + STATE_LINE
                + "cd \"$standroid_folder\" || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_folder_here=$(standroid_identity .) || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_folder_here\" = \"$standroid_folder_identity\" ] || exit " + FOLDER_UNSAFE_EXIT_CODE + STATE_LINE
                + "standroid_listed=." + STATE_LINE
                + "standroid_scan() {" + STATE_LINE
                + "if [ \"$(date +%s)\" -gt \"$standroid_deadline\" ]; then exit " + FOLDER_LIMIT_EXIT_CODE + "; fi" + STATE_LINE
                + "standroid_count=$(ls -a . | wc -l) || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_count\" -ge 2 ] || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_count=$(( standroid_count - 2 ))" + STATE_LINE
                + "[ \"$(( standroid_entries + standroid_count ))\" -le " + FOLDER_SCAN_MAX_ENTRIES + " ] || exit " + FOLDER_LIMIT_EXIT_CODE + STATE_LINE
                + "if [ \"$(date +%s)\" -gt \"$standroid_deadline\" ]; then exit " + FOLDER_LIMIT_EXIT_CODE + "; fi" + STATE_LINE
                + "for standroid_entry in \"$standroid_listed\"/* \"$standroid_listed\"/.[!.]* \"$standroid_listed\"/..?*; do" + STATE_LINE
                + "[ -e \"$standroid_entry\" ] || [ -L \"$standroid_entry\" ] || continue" + STATE_LINE
                + "standroid_entries=$(( standroid_entries + 1 ))" + STATE_LINE
                + "[ \"$standroid_entries\" -le " + FOLDER_SCAN_MAX_ENTRIES + " ] || exit " + FOLDER_LIMIT_EXIT_CODE + STATE_LINE
                + "if [ \"$(( standroid_entries % " + FOLDER_SCAN_TIME_CHECK_STRIDE + " ))\" -eq 0 ] && [ \"$(date +%s)\" -gt \"$standroid_deadline\" ]; then exit " + FOLDER_LIMIT_EXIT_CODE + "; fi" + STATE_LINE
                + "[ -L \"$standroid_entry\" ] && continue" + STATE_LINE
                + "if [ -d \"$standroid_entry\" ]; then" + STATE_LINE
                + "if [ \"$1\" = \"1\" ]; then" + STATE_LINE
                + "case \"${standroid_entry##*/}\" in" + STATE_LINE
                + FOLDER_VERSIONING_DIRECTORY_NAME + ") continue ;;" + STATE_LINE
                + "esac" + STATE_LINE
                + "fi" + STATE_LINE
                + "standroid_child_identity=$(standroid_identity \"$standroid_entry\") || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "[ ! -L \"$standroid_entry\" ] || exit " + FOLDER_UNSAFE_EXIT_CODE + STATE_LINE
                + "[ -d \"$standroid_entry\" ] || continue" + STATE_LINE
                + "standroid_child_name=${standroid_entry#./}" + STATE_LINE
                + "cd \"$standroid_entry\" || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_child_here=$(standroid_identity .) || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_child_here\" = \"$standroid_child_identity\" ] || exit " + FOLDER_UNSAFE_EXIT_CODE + STATE_LINE
                + "standroid_scan 0 \"$standroid_child_identity\" \"$3$standroid_child_name/\"" + STATE_LINE
                + "cd .. || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_parent_here=$(standroid_identity .) || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_parent_here\" = \"$2\" ] || exit " + FOLDER_UNSAFE_EXIT_CODE + STATE_LINE
                + "continue" + STATE_LINE
                + "fi" + STATE_LINE
                + "[ -f \"$standroid_entry\" ] || continue" + STATE_LINE
                + "case \"${standroid_entry##*/}\" in" + STATE_LINE
                + "" + conflictName + ") ;;" + STATE_LINE
                + "*) continue ;;" + STATE_LINE
                + "esac" + STATE_LINE
                + "standroid_matches=$(( standroid_matches + 1 ))" + STATE_LINE
                + "[ \"$standroid_matches\" -le " + FOLDER_SCAN_MAX_MATCHES + " ] || exit " + FOLDER_LIMIT_EXIT_CODE + STATE_LINE
                + "standroid_encoded=$(printf '%s' \"$3${standroid_entry#./}\" | base64 | tr -d '\\n') || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_output=$(( standroid_output + ${#standroid_encoded} ))" + STATE_LINE
                + "[ \"$standroid_output\" -le " + FOLDER_SCAN_MAX_OUTPUT_CHARS + " ] || exit " + FOLDER_LIMIT_EXIT_CODE + STATE_LINE
                + "[ -f \"$standroid_entry\" ] && [ ! -L \"$standroid_entry\" ] || continue" + STATE_LINE
                + "printf '%s\\n' \"$standroid_encoded\"" + STATE_LINE
                + "done" + STATE_LINE
                + "}" + STATE_LINE
                + "standroid_scan 1 \"$standroid_folder_here\" \"\"" + STATE_LINE
                + "exit 0";
    }

    /**
     * Builds the byte-exact read of one folder's ignore list.
     *
     * <p>The configured folder is pinned before anything is read. A folder whose last path
     * component is not a real directory is refused instead of followed, and the shell then enters
     * the directory and re-reads the identity of its own working directory, so a caller who
     * controls the folder's parent directory cannot rename or replace that entry and have later
     * pathnames resolve somewhere else. The member is addressed relative to the pinned
     * directory.</p>
     *
     * <p>The member is opened by descriptor, and the inode that descriptor holds is compared with
     * the inode the member name holds before anything is read, so a member exchanged for a
     * symbolic link between the check and the read is refused instead of followed, and the bytes
     * that are reported come from the descriptor that was verified and never from the name.</p>
     *
     * <p>An absent member reports its own status, while an unreadable member, a symbolic link, or
     * a wrong entry kind fails the read. That keeps "this folder has no ignore list" separate
     * from "the ignore list could not be read". The identity of the member is captured again
     * around the read and the read fails closed when it changed or stopped being a regular file,
     * so a member replaced while the helper reads it is never reported as its content. The member
     * is read through a reader that stops after the size that was checked, and both the encoded
     * length and the size are compared against that check afterwards, so a member that grew while
     * it was read and a read that stopped early can neither buffer more than the checked size nor
     * be reported as content the size check never covered.</p>
     */
    String readFolderIgnoreListScript(String folderRoot) {
        return stateUidGuard()
                + "standroid_identity() {" + STATE_LINE
                + "set -- $(ls -lni \"$1\" 2>/dev/null) || return 1" + STATE_LINE
                + "echo \"$1 $2\"" + STATE_LINE
                + "}" + STATE_LINE
                + "standroid_directory_identity() {" + STATE_LINE
                + "set -- $(ls -lnid \"$1\" 2>/dev/null) || return 1" + STATE_LINE
                + "[ -n \"$1\" ] || return 1" + STATE_LINE
                + "echo \"$1 $2\"" + STATE_LINE
                + "}" + STATE_LINE
                + "standroid_folder=" + quote(folderRoot) + STATE_LINE
                + "if [ ! -d \"$standroid_folder\" ]; then exit "
                + FOLDER_ACCESS_EXIT_CODE + "; fi" + STATE_LINE
                + "standroid_folder_identity=$(standroid_directory_identity \"$standroid_folder\") || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "case \"$standroid_folder_identity\" in *\" d\"*) : ;; *) exit "
                + FOLDER_UNSAFE_EXIT_CODE + " ;; esac" + STATE_LINE
                + "cd \"$standroid_folder\" || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_folder_here=$(standroid_directory_identity .) || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_folder_here\" = \"$standroid_folder_identity\" ] || exit "
                + FOLDER_UNSAFE_EXIT_CODE + STATE_LINE
                + "standroid_member=\"./" + FOLDER_IGNORE_FILE_NAME + "\"" + STATE_LINE
                + "if [ ! -e \"$standroid_member\" ] && [ ! -L \"$standroid_member\" ]; then exit "
                + FOLDER_MISSING_EXIT_CODE + "; fi" + STATE_LINE
                + "if [ -L \"$standroid_member\" ] || [ ! -f \"$standroid_member\" ]; then exit "
                + FOLDER_UNSAFE_EXIT_CODE + "; fi" + STATE_LINE
                + "if [ ! -r \"$standroid_member\" ]; then exit "
                + FOLDER_ACCESS_EXIT_CODE + "; fi" + STATE_LINE
                + "standroid_size=$(wc -c < \"$standroid_member\" 2>/dev/null) || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_size\" -le " + FOLDER_IGNORE_LIST_MAX_BYTES + " ] || exit "
                + FOLDER_LIMIT_EXIT_CODE + STATE_LINE
                + "command -v base64 >/dev/null 2>&1 || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "command -v tr >/dev/null 2>&1 || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "command -v dd >/dev/null 2>&1 || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_identity_before=$(standroid_identity \"$standroid_member\") || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "case \"$standroid_identity_before\" in *\" l\"*) exit "
                + FOLDER_UNSAFE_EXIT_CODE + " ;; esac" + STATE_LINE
                + "standroid_fd_path=\"/dev/fd/3\"" + STATE_LINE
                + "[ -d /proc/self/fd ] && standroid_fd_path=\"/proc/self/fd/3\"" + STATE_LINE
                + "standroid_file_inode() { set -- $(ls -lni \"$1\" 2>/dev/null) || return 1;"
                + " [ $# -gt 0 ] || return 1; echo \"$1\"; }" + STATE_LINE
                + "standroid_member_inode=$(standroid_file_inode \"$standroid_member\") || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "exec 3< \"$standroid_member\" || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_opened_inode=$(set -- $(ls -lniL \"$standroid_fd_path\" 2>/dev/null 3<&3);"
                + " [ $# -gt 0 ] && echo \"$1\") || { exec 3<&-; exit "
                + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "[ \"$standroid_member_inode\" = \"$standroid_opened_inode\" ] || { exec 3<&-; exit "
                + FOLDER_UNSAFE_EXIT_CODE + "; }" + STATE_LINE
                // The member is read through a reader that stops after the size that was just
                // checked, so a member that grows while it is read can never buffer more than
                // the checked size plus the one block of slack the reader allows for.
                + "standroid_blocks=$(( standroid_size / " + FOLDER_IGNORE_LIST_READ_BLOCK_BYTES
                + " + 2 ))" + STATE_LINE
                + "standroid_encoded=$(dd if=\"$standroid_fd_path\" 3<&3 bs="
                        + FOLDER_IGNORE_LIST_READ_BLOCK_BYTES
                + " count=\"$standroid_blocks\""
                + " 2>/dev/null | base64 | tr -d '\\n') || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "exec 3<&-" + STATE_LINE
                // Base64 of the checked size is the only payload this read may report, so a longer
                // one means the member grew after the check and a shorter one means the read stopped
                // early; neither is reported as content.
                + "standroid_expected=$(( ( ( standroid_size + 2 ) / 3 ) * 4 ))" + STATE_LINE
                + "[ \"${#standroid_encoded}\" = \"$standroid_expected\" ] || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_size_after=$(wc -c < \"$standroid_member\" 2>/dev/null) || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_size_after\" = \"$standroid_size\" ] || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_identity_after=$(standroid_identity \"$standroid_member\") || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_identity_before\" = \"$standroid_identity_after\" ] || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "printf '%s\\n' \"$standroid_encoded\"" + STATE_LINE
                + "exit 0" + STATE_LINE;
    }

    /**
     * Builds the atomic replacement of one folder's ignore list.
     *
     * <p>The replacement is created directly in the configured folder with one exclusive
     * redirection: the shell turns on {@code noclobber} for the single {@code base64 -d > name}
     * command that builds the staged file, so that command can neither follow a symbolic link nor
     * overwrite an entry a caller put at that name. It fails closed when the name is taken, and the
     * staged file is therefore always a file this operation created itself inside the folder.</p>
     *
     * <p>The operation opens that file once and keeps the descriptor for the rest of the
     * replacement. The size, the mode, the ownership and the security context are then read,
     * applied and verified through that descriptor - {@code /proc/self/fd/3}, or {@code /dev/fd/3}
     * where {@code /proc} is not mounted - which the kernel resolves to the file this operation
     * created rather than to whatever the name may point at later. The entry name and the
     * descriptor are compared, and the entry has to be a regular file, before any of those
     * accesses happen; a caller that replaces the staged entry afterwards cannot redirect them,
     * because none of them resolves a path again. The shell this transport runs on does not hand a
     * descriptor it opened itself to the commands it starts, so every command that has to act on the
     * descriptor is handed that descriptor on its own command line; without it, those commands would
     * resolve a descriptor path they do not hold, and the replacement of an existing list would fail
     * instead of preserving its attributes.</p>
     *
     * <p>Mode and ownership of an existing member are read before the replacement is built and
     * applied to the staged file through the descriptor, so the member keeps the attributes the
     * folder gave it and the sync service can still read the list. The security context is
     * compared the same way and repaired with {@code chcon} when the file system did not inherit
     * it; a context that cannot be preserved fails the operation instead of silently relabelling
     * the member.</p>
     *
     * <p>The staged file is renamed over the member from inside the directory the shell entered,
     * so no reader observes a half-written list and the destination cannot leave the configured
     * folder. The member is checked once more after the rename, because {@code mv} moves the
     * replacement inside the member and still reports success when another writer replaced that
     * member with a directory while this operation ran; such a stray replacement is removed again
     * and the write fails closed.</p>
     *
     * <p>The residual exposure of this transport is the rename itself, which is the one remaining
     * pathname operation of the replacement: a caller that replaces the staged entry between the
     * last identity check and the rename can have an entry of its own renamed over the member.
     * That is detected right after the rename and reported as a typed refusal instead of a saved
     * list, and it grants the caller no write it could not already perform inside a folder it
     * controls. This shell transport offers no descriptor-relative rename, and the approved design
     * adds no such primitive.</p>
     */
    String writeFolderIgnoreListScript(String folderRoot, String encodedContent) {
        String delimiter;
        do {
            delimiter = "STANDROID_STIGNORE_" + UUID.randomUUID().toString().replace("-", "");
        } while (encodedContent.contains(delimiter));
        String stagedName = FOLDER_IGNORE_FILE_NAME
                + ".standroid-" + UUID.randomUUID().toString().replace("-", "");
        String bareContent = encodedContent.replaceAll("\\s", "");
        int stagedBytes = (bareContent.length() / 4) * 3;
        if (bareContent.endsWith("==")) {
            stagedBytes -= 2;
        } else if (bareContent.endsWith("=")) {
            stagedBytes -= 1;
        }
        return stateUidGuard()
                // The first ten characters of a mode field are the file type and the permission
                // letters; a file system or a listing tool may append its own flags after them, and
                // those flags must not make two equal permissions look different.
                + "standroid_mode_field() {" + STATE_LINE
                + "standroid_mode_text=$1" + STATE_LINE
                + "standroid_mode_tail=${standroid_mode_text#??????????}" + STATE_LINE
                + "echo \"${standroid_mode_text%$standroid_mode_tail}\"" + STATE_LINE
                + "}" + STATE_LINE
                + "standroid_owner() {" + STATE_LINE
                + "set -- $(ls -ln \"$1\" 2>/dev/null) || return 1" + STATE_LINE
                + "[ $# -gt 3 ] || return 1" + STATE_LINE
                + "echo \"$(standroid_mode_field \"$1\") $3 $4\"" + STATE_LINE
                + "}" + STATE_LINE
                + "standroid_identity() {" + STATE_LINE
                + "set -- $(ls -lni \"$1\" 2>/dev/null) || return 1" + STATE_LINE
                + "[ $# -gt 1 ] || return 1" + STATE_LINE
                + "echo \"$1 $(standroid_mode_field \"$2\")\"" + STATE_LINE
                + "}" + STATE_LINE
                + "standroid_directory_identity() {" + STATE_LINE
                + "set -- $(ls -lnid \"$1\" 2>/dev/null) || return 1" + STATE_LINE
                + "[ $# -gt 1 ] || return 1" + STATE_LINE
                + "echo \"$1 $(standroid_mode_field \"$2\")\"" + STATE_LINE
                + "}" + STATE_LINE
                // The inode of the file the shell holds a descriptor for. The descriptor is fd 3,
                // and the kernel resolves the path below to the object behind it rather than to
                // whatever name the operation used before.
                + "standroid_descriptor_inode() {" + STATE_LINE
                + "set -- $(ls -lniL \"$1\" 2>/dev/null 3<&3) || return 1" + STATE_LINE
                + "[ $# -gt 0 ] || return 1" + STATE_LINE
                + "echo \"$1\"" + STATE_LINE
                + "}" + STATE_LINE
                // Translates the permission letters of an "ls -l" mode field into the octal value
                // chmod takes, including the set-user-ID, set-group-ID and sticky bits.
                + "standroid_mode_octal() {" + STATE_LINE
                + "[ ${#1} -ge 10 ] || return 1" + STATE_LINE
                + "standroid_letters=${1#?}" + STATE_LINE
                + "standroid_octal=\"\"" + STATE_LINE
                + "standroid_special=0" + STATE_LINE
                + "standroid_group_number=0" + STATE_LINE
                + "while [ $standroid_group_number -lt 3 ]; do" + STATE_LINE
                + "standroid_group=${standroid_letters%\"${standroid_letters#???}\"}" + STATE_LINE
                + "standroid_letters=${standroid_letters#???}" + STATE_LINE
                + "standroid_value=0" + STATE_LINE
                + "case \"$standroid_group\" in *r*) standroid_value=4 ;; esac" + STATE_LINE
                + "case \"$standroid_group\" in *w*) standroid_value=$(( standroid_value + 2 )) ;; esac"
                + STATE_LINE
                + "case \"$standroid_group\" in *[xst]*) standroid_value=$(( standroid_value + 1 )) ;; esac"
                + STATE_LINE
                + "case \"$standroid_group_number\" in" + STATE_LINE
                + "0) case \"$standroid_group\" in *[sS]*) standroid_special=4 ;; esac ;;" + STATE_LINE
                + "1) case \"$standroid_group\" in *[sS]*) standroid_special=$(( standroid_special + 2 )) ;; esac ;;"
                + STATE_LINE
                + "*) case \"$standroid_group\" in *[tT]*) standroid_special=$(( standroid_special + 1 )) ;; esac ;;"
                + STATE_LINE
                + "esac" + STATE_LINE
                + "standroid_octal=\"$standroid_octal$standroid_value\"" + STATE_LINE
                + "standroid_group_number=$(( standroid_group_number + 1 ))" + STATE_LINE
                + "done" + STATE_LINE
                + "echo \"$standroid_special$standroid_octal\"" + STATE_LINE
                + "}" + STATE_LINE
                // The security context of an entry this script names. "?" stands for "no context could be
                // read here", which the caller compares rather than treating it as a match.
                + "standroid_context() {" + STATE_LINE
                + "[ -e /sys/fs/selinux/enforce ] || { echo \"?\"; return 0; }" + STATE_LINE
                + "for standroid_field in $(ls -Zd$2 \"$1\" 2>/dev/null); do" + STATE_LINE
                + "case \"$standroid_field\" in" + STATE_LINE
                + "*:object_r:*) echo \"$standroid_field\"; return 0 ;;" + STATE_LINE
                + "esac" + STATE_LINE
                + "done" + STATE_LINE
                + "echo \"?\"" + STATE_LINE
                + "}" + STATE_LINE
                // The same read for the file this operation opened: the command that lists the entry is
                // handed the descriptor explicitly, because the shell of the compatibility floor does not
                // hand descriptors it opened to the commands it starts, and it is asked to follow the
                // descriptor, so the context reported is that of the opened file rather than that of the
                // process file system entry standing for it.
                + "standroid_descriptor_context() {" + STATE_LINE
                + "[ -e /sys/fs/selinux/enforce ] || { echo \"?\"; return 0; }" + STATE_LINE
                + "for standroid_field in $(ls -ZdL \"$1\" 2>/dev/null 3<&3); do" + STATE_LINE
                + "case \"$standroid_field\" in" + STATE_LINE
                + "*:object_r:*) echo \"$standroid_field\"; return 0 ;;" + STATE_LINE
                + "esac" + STATE_LINE
                + "done" + STATE_LINE
                + "echo \"?\"" + STATE_LINE
                + "}" + STATE_LINE
                + "standroid_folder=" + quote(folderRoot) + STATE_LINE
                + "if [ ! -d \"$standroid_folder\" ]; then exit "
                + FOLDER_ACCESS_EXIT_CODE + "; fi" + STATE_LINE
                + "standroid_folder_identity=$(standroid_directory_identity \"$standroid_folder\") || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "case \"$standroid_folder_identity\" in *\" d\"*) : ;; *) exit "
                + FOLDER_UNSAFE_EXIT_CODE + " ;; esac" + STATE_LINE
                + "cd \"$standroid_folder\" || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_folder_here=$(standroid_directory_identity .) || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_folder_here\" = \"$standroid_folder_identity\" ] || exit "
                + FOLDER_UNSAFE_EXIT_CODE + STATE_LINE
                + "standroid_member=\"./" + FOLDER_IGNORE_FILE_NAME + "\"" + STATE_LINE
                + "standroid_staged=\"./" + stagedName + "\"" + STATE_LINE
                + "standroid_fd_path=\"/dev/fd/3\"" + STATE_LINE
                + "[ -d /proc/self/fd ] && standroid_fd_path=\"/proc/self/fd/3\"" + STATE_LINE
                + "standroid_descriptor_open=\"\"" + STATE_LINE
                // Every failure path leaves through this function: the staged entry this operation
                // created is removed again and the descriptor it holds is closed. Both address only
                // a name inside the pinned folder, which cannot follow a link or leave that folder.
                + "standroid_cleanup() {" + STATE_LINE
                + "rm -f \"$standroid_staged\" 2>/dev/null" + STATE_LINE
                + "[ -n \"$standroid_descriptor_open\" ] || return 0" + STATE_LINE
                + "exec 3<&-" + STATE_LINE
                + "return 0" + STATE_LINE
                + "}" + STATE_LINE
                + "standroid_check_folder() {" + STATE_LINE
                + "[ \"$(standroid_directory_identity .)\" = \"$standroid_folder_identity\" ]"
                + " || { standroid_cleanup; exit " + FOLDER_UNSAFE_EXIT_CODE + "; }" + STATE_LINE
                + "}" + STATE_LINE
                + "command -v base64 >/dev/null 2>&1 || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_member_present=0" + STATE_LINE
                + "standroid_member_identity=\"\"" + STATE_LINE
                + "standroid_member_owner=\"\"" + STATE_LINE
                + "standroid_member_context=\"\"" + STATE_LINE
                + "standroid_member_mode=\"\"" + STATE_LINE
                + "if [ -e \"$standroid_member\" ] || [ -L \"$standroid_member\" ]; then" + STATE_LINE
                + "[ -f \"$standroid_member\" ] && [ ! -L \"$standroid_member\" ] || { standroid_cleanup; exit "
                + FOLDER_UNSAFE_EXIT_CODE + "; }" + STATE_LINE
                + "standroid_size=$(wc -c < \"$standroid_member\" 2>/dev/null) || { standroid_cleanup; exit "
                + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "[ \"$standroid_size\" -le " + FOLDER_IGNORE_LIST_MAX_BYTES
                + " ] || { standroid_cleanup; exit " + FOLDER_LIMIT_EXIT_CODE + "; }" + STATE_LINE
                // The member is identified after its size was read, because that size came from
                // the member name: a member the caller replaced with a symbolic link in between
                // must not be measured through, which would read a file outside the folder.
                + "standroid_member_identity=$(standroid_identity \"$standroid_member\") || { standroid_cleanup; exit "
                + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "case \"$standroid_member_identity\" in *\" l\"*) standroid_cleanup; exit "
                + FOLDER_UNSAFE_EXIT_CODE + " ;; esac" + STATE_LINE
                + "standroid_member_owner=$(standroid_owner \"$standroid_member\") || { standroid_cleanup; exit "
                + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "standroid_member_context=$(standroid_context \"$standroid_member\")" + STATE_LINE
                + "set -- $standroid_member_identity" + STATE_LINE
                + "standroid_member_mode=$(standroid_mode_octal \"$2\") || { standroid_cleanup; exit "
                + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "standroid_member_present=1" + STATE_LINE
                + "fi" + STATE_LINE
                // The staged file is created by the one command that also writes it. Noclobber
                // makes that redirection exclusive, so it refuses a name the caller occupied - a
                // symbolic link included - instead of writing through it, and the file it creates
                // can only appear inside the pinned folder.
                + "(umask 077 && set -C && base64 -d > \"$standroid_staged\" <<'" + delimiter + "'"
                + STATE_LINE
                + encodedContent
                + (encodedContent.endsWith(STATE_LINE) ? "" : STATE_LINE)
                + delimiter + STATE_LINE
                + ") || { standroid_cleanup; exit " + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                // The descriptor is opened from the entry that was just created and compared with
                // it, so from here on this operation holds the file itself, not a name a caller
                // controlling the folder could repoint at another file. The type of the entry is
                // checked as well, so a link, a directory or a fifo left at that name is refused
                // before this operation opens or writes anything.
                + "standroid_staged_entry=$(standroid_identity \"$standroid_staged\") || { standroid_cleanup; exit "
                + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "case \"$standroid_staged_entry\" in *\" -\"*) : ;; *) standroid_cleanup; exit "
                + FOLDER_UNSAFE_EXIT_CODE + " ;; esac" + STATE_LINE
                + "exec 3< \"$standroid_staged\" || { standroid_cleanup; exit "
                + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "standroid_descriptor_open=1" + STATE_LINE
                + "set -- $standroid_staged_entry" + STATE_LINE
                + "standroid_staged_inode=$1" + STATE_LINE
                + "standroid_staged_now=$(standroid_descriptor_inode \"$standroid_fd_path\")"
                + " || { standroid_cleanup; exit " + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "[ \"$standroid_staged_now\" = \"$standroid_staged_inode\" ] || { standroid_cleanup; exit "
                + FOLDER_UNSAFE_EXIT_CODE + "; }" + STATE_LINE
                + "standroid_staged_size=$(wc -c 3<&3 < \"$standroid_fd_path\" 2>/dev/null)"
                + " || { standroid_cleanup; exit " + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "[ \"$standroid_staged_size\" -eq " + stagedBytes + " ] || { standroid_cleanup; exit "
                + FOLDER_UNSAFE_EXIT_CODE + "; }" + STATE_LINE
                + "if [ \"$standroid_member_present\" = 1 ]; then" + STATE_LINE
                + "command -v chmod >/dev/null 2>&1 || { standroid_cleanup; exit "
                + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                // The descriptor is named on every command below, because the shell of the compatibility
                // floor keeps a descriptor it opened itself to itself: without the forwarding, chmod,
                // chown and chcon would each resolve a descriptor path their own process does not hold,
                // and replacing an existing list would fail on those devices.
                + "chmod \"$standroid_member_mode\" \"$standroid_fd_path\" 3<&3"
                + " || { standroid_cleanup; exit "
                + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "command -v chown >/dev/null 2>&1 || { standroid_cleanup; exit "
                + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "set -- $standroid_member_owner" + STATE_LINE
                + "chown \"$2:$3\" \"$standroid_fd_path\" 3<&3 || { standroid_cleanup; exit "
                + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                // Mode and ownership are read back from the staged entry, which is the entry the
                // descriptor was identified against, so a replacement either carries the
                // attributes of the member or the write fails instead of relabelling the list.
                + "standroid_staged_owner=$(standroid_owner \"$standroid_staged\")"
                + " || { standroid_cleanup; exit " + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "[ \"$standroid_staged_owner\" = \"$standroid_member_owner\" ] || { standroid_cleanup; exit "
                + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "standroid_staged_context=$(standroid_descriptor_context"
                + " \"$standroid_fd_path\")" + STATE_LINE
                + "if [ \"$standroid_member_context\" != \"$standroid_staged_context\" ]; then" + STATE_LINE
                + "command -v chcon >/dev/null 2>&1"
                + " && chcon \"$standroid_member_context\" \"$standroid_fd_path\" 3<&3 2>/dev/null"
                + STATE_LINE
                + "standroid_staged_context=$(standroid_descriptor_context"
                + " \"$standroid_fd_path\")" + STATE_LINE
                + "[ \"$standroid_member_context\" = \"$standroid_staged_context\" ]"
                + " || { standroid_cleanup; exit " + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "fi" + STATE_LINE
                + "fi" + STATE_LINE
                // "mv" moves the replacement inside the member instead of over it when another
                // writer replaced the member with a directory while this operation ran, and that
                // still exits zero, so such a destination is refused before the rename and the
                // stray copy is removed again afterwards. The member is then checked to be the file
                // that was just renamed before the list counts as saved.
                + "standroid_check_folder" + STATE_LINE
                + "[ ! -d \"$standroid_member\" ] || { standroid_cleanup; exit "
                + FOLDER_UNSAFE_EXIT_CODE + "; }" + STATE_LINE
                + "if [ \"$standroid_member_present\" = 1 ]; then" + STATE_LINE
                + "[ \"$(standroid_identity \"$standroid_member\")\" = \"$standroid_member_identity\" ]"
                + " || { standroid_cleanup; exit " + FOLDER_UNSAFE_EXIT_CODE + "; }" + STATE_LINE
                + "fi" + STATE_LINE
                + "standroid_staged_entry=$(standroid_identity \"$standroid_staged\")"
                + " || { standroid_cleanup; exit " + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "set -- $standroid_staged_entry" + STATE_LINE
                + "[ \"$1\" = \"$standroid_staged_inode\" ] || { standroid_cleanup; exit "
                + FOLDER_UNSAFE_EXIT_CODE + "; }" + STATE_LINE
                + "standroid_staged_identity=\"$standroid_staged_entry\"" + STATE_LINE
                + "mv -f \"$standroid_staged\" \"$standroid_member\""
                + " || { standroid_cleanup; exit " + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "if [ -f \"$standroid_member\" ] && [ ! -L \"$standroid_member\" ]; then :; else" + STATE_LINE
                + "if [ -d \"$standroid_member\" ] && [ ! -L \"$standroid_member\" ]; then" + STATE_LINE
                + "rm -f \"$standroid_member/" + stagedName + "\" 2>/dev/null" + STATE_LINE
                + "fi" + STATE_LINE
                + "standroid_cleanup" + STATE_LINE
                + "exit " + FOLDER_UNSAFE_EXIT_CODE + STATE_LINE
                + "fi" + STATE_LINE
                + "standroid_cleanup" + STATE_LINE
                + "standroid_member_now=$(standroid_identity \"$standroid_member\")"
                + " || { standroid_cleanup; exit " + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "[ \"$standroid_member_now\" = \"$standroid_staged_identity\" ]"
                + " || { standroid_cleanup; exit " + FOLDER_UNSAFE_EXIT_CODE + "; }" + STATE_LINE
                + "standroid_member_size=$(wc -c < \"$standroid_member\" 2>/dev/null)"
                + " || { standroid_cleanup; exit " + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "[ \"$standroid_member_size\" -eq \"$standroid_staged_size\" ]"
                + " || { standroid_cleanup; exit " + FOLDER_UNSAFE_EXIT_CODE + "; }" + STATE_LINE
                + "exit 0" + STATE_LINE;
    }

    /**
     * Builds the approved sync-completion script dispatch for one configured folder.
     *
     * <p>Only regular files whose name ends in {@code .sh} directly inside the marker directory
     * run; symbolic links are never executed and other entry kinds are skipped. A marker directory
     * that is itself a symbolic link is refused, because running its scripts would execute code
     * from outside the configured folder. Each script starts through {@code /system/bin/sh} with
     * the folder root as its working directory, the script path as the interpreter name, and the
     * event name as its only argument, so a script sees the same {@code $0} and {@code $1} as it
     * does in Normal Mode, and its own
     * output is discarded so an unbounded script cannot grow the transport result. One report line
     * per script carries the base64-encoded name and the exit status, with the line breaks a Base64
     * implementation may insert removed, so one line always carries exactly one report. The size of
     * the reports is counted as they are produced and the dispatch stops with the limit status
     * before the transport result can grow past that budget.</p>
     * <p>The marker directory carries the identity it had when this dispatch enumerated it, and
     * that identity is checked again directly before every script runs. A caller that controls
     * the folder can rename that entry and leave a directory or a symbolic link of its own in its
     * place, and a name this dispatch already enumerated would then resolve to a program outside
     * the configured folder; the re-check refuses that swap instead of running it.</p>
     *
     * <p>Each script is then opened, and the identity of the opened descriptor is compared with
     * the identity recorded for the entry that was enumerated as a regular file. The interpreter
     * reads that descriptor rather than the name again, so a caller that replaces the entry
     * between the check and the execution can only make this dispatch open the file it put there;
     * the recorded identity does not match it, and the dispatch refuses instead of running a
     * program from outside the configured folder. The descriptor is followed when its identity is
     * read, because a process file system entry reports an inode of its own rather than the inode of
     * the file it stands for, and it is named explicitly on the identity read and on the interpreter
     * command, because the shell of the compatibility floor does not pass descriptors it opened on
     * to the commands it starts. The descriptor is reached through {@code /proc/self/fd/3} where the
     * kernel provides it and through {@code /dev/fd/3} otherwise.</p>
     *
     * <p>The interpreter is started with a constant command string that sources the opened
     * descriptor and with the script's own path as its name, so the commands come from the verified
     * file while the script still sees the path it was loaded from in {@code $0} and the event name
     * in {@code $1}, exactly as it does in Normal Mode.</p>
     *
     * <p>The configured folder is pinned before the marker directory is looked at. A folder whose
     * last path component is not a real directory is refused instead of followed, and the shell
     * then enters the directory and re-reads the identity of its own working directory, so a caller
     * who controls the folder's parent directory cannot rename or replace that entry and have the
     * marker, the enumerated scripts or the interpreter's working directory resolve somewhere else.
     * The interpreter itself is started without resolving the folder pathname again, because the
     * shell already stands in the pinned folder.</p>
     *
     */
    String folderScriptSetScript(String folderRoot, String eventArgument) {
        return stateUidGuard()
                + "standroid_directory_identity() {" + STATE_LINE
                + "set -- $(ls -lnid \"$1\" 2>/dev/null) || return 1" + STATE_LINE
                + "[ -n \"$1\" ] || return 1" + STATE_LINE
                + "echo \"$1 $2\"" + STATE_LINE
                + "}" + STATE_LINE
                + "standroid_folder=" + quote(folderRoot) + STATE_LINE
                + "standroid_folder_identity=$(standroid_directory_identity \"$standroid_folder\") || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "case \"$standroid_folder_identity\" in *\" d\"*) : ;; *) exit "
                + FOLDER_UNSAFE_EXIT_CODE + " ;; esac" + STATE_LINE
                + "cd \"$standroid_folder\" || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_folder_here=$(standroid_directory_identity .) || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_folder_here\" = \"$standroid_folder_identity\" ] || exit "
                + FOLDER_UNSAFE_EXIT_CODE + STATE_LINE
                + "standroid_marker=\"./" + FOLDER_SCRIPT_DIRECTORY_NAME + "\"" + STATE_LINE
                + "if [ -L \"$standroid_marker\" ]; then exit "
                + FOLDER_UNSAFE_EXIT_CODE + "; fi" + STATE_LINE
                + "command -v base64 >/dev/null 2>&1 || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "command -v tr >/dev/null 2>&1 || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_output=0" + STATE_LINE
                + "if [ ! -d \"$standroid_marker\" ]; then exit 0; fi" + STATE_LINE
                // The marker directory is enumerated by name below, so the identity it has now is
                // re-checked directly before every script runs: a caller that controls the folder can rename
                // this entry and leave a symbolic link in its place, and a script resolved through such a
                // link would be a program from outside the configured folder running with root privileges.
                + "standroid_fd_path=\"/dev/fd/3\"" + STATE_LINE
+ "[ -d /proc/self/fd ] && standroid_fd_path=\"/proc/self/fd/3\"" + STATE_LINE
+ "standroid_file_inode() { set -- $(ls -lni \"$1\" 2>/dev/null) || return 1; echo \"$1\"; }" + STATE_LINE
                // The command below is handed the descriptor explicitly and reads it by following
                // it. Following the descriptor reports the opened file rather than the process file
                // system entry that stands for it on the compatibility floor, which is the identity
                // that has to match the enumerated script, and the descriptor is passed explicitly
                // because that shell does not hand descriptors it opened to the commands it starts.
                + "standroid_opened_file_inode() { set -- $(ls -lniL \"$1\" 2>/dev/null 3<&3); [ $# -gt 0 ] || return 1; echo \"$1\"; }" + STATE_LINE
+ "standroid_marker_identity=$(standroid_directory_identity \"$standroid_marker\")"
                + " || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "case \"$standroid_marker_identity\" in *\" d\"*) : ;; *) exit "
                + FOLDER_UNSAFE_EXIT_CODE + " ;; esac" + STATE_LINE
                + "for standroid_script in \"$standroid_marker\"/*"
                + " \"$standroid_marker\"/.[!.]* \"$standroid_marker\"/..?*; do" + STATE_LINE
                + "[ -e \"$standroid_script\" ] || [ -L \"$standroid_script\" ] || continue" + STATE_LINE
                + "[ -L \"$standroid_script\" ] && continue" + STATE_LINE
                + "[ -f \"$standroid_script\" ] || continue" + STATE_LINE
                + "standroid_name=\"" + "${standroid_script##*/}" + "\"" + STATE_LINE
                + "case \"$standroid_name\" in" + STATE_LINE
                + "*.sh|*.SH|*.sH|*.Sh) ;;" + STATE_LINE
                + "*) continue ;;" + STATE_LINE
                + "esac" + STATE_LINE
                // The program that runs is the file whose identity was enumerated as a regular
                // entry of the marker directory: that file is opened and the opened descriptor is
                // what the interpreter reads, so a caller that renames the marker directory or the
                // entry between the check and the execution cannot substitute a program from
                // outside the configured folder. The descriptor is handed to the interpreter for the
                // same reason, and it is read by following it, so the identity compared is the opened
                // file and not the process file system entry that stands for it.
                + "standroid_script_inode=$(standroid_file_inode \"$standroid_script\") || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_marker_now=$(standroid_directory_identity \"$standroid_marker\") || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "[ \"$standroid_marker_now\" = \"$standroid_marker_identity\" ] || exit " + FOLDER_UNSAFE_EXIT_CODE + STATE_LINE
                + "exec 3< \"$standroid_script\" || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_opened_inode=$(standroid_opened_file_inode \"$standroid_fd_path\") || { exec 3<&-; exit " + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "[ \"$standroid_opened_inode\" = \"$standroid_script_inode\" ] || { exec 3<&-; exit " + FOLDER_UNSAFE_EXIT_CODE + "; }" + STATE_LINE
                + "standroid_marker_after=$(standroid_directory_identity \"$standroid_marker\") || { exec 3<&-; exit " + FOLDER_ACCESS_EXIT_CODE + "; }" + STATE_LINE
                + "[ \"$standroid_marker_after\" = \"$standroid_marker_identity\" ] || { exec 3<&-; exit " + FOLDER_UNSAFE_EXIT_CODE + "; }" + STATE_LINE
                // The interpreter is named after the script path, so a script that uses "$0" to find
                // companion files keeps working, and it is handed a constant command string that
                // sources the opened descriptor, so the commands still come from the verified file
                // and never from a pathname a caller could have replaced after the checks above.
                // The shell already stands in the pinned folder, so the interpreter is started
                // without resolving the folder pathname again, and the name it is given is the
                // absolute path the approved script expects to see as "$0".
                + "standroid_script_path=\"$standroid_folder/"
                + FOLDER_SCRIPT_DIRECTORY_NAME + "/$standroid_name\"" + STATE_LINE
                + "( /system/bin/sh -c \". $standroid_fd_path\" \"$standroid_script_path\" " + quote(eventArgument) + " 3<&3 ) >/dev/null 2>&1" + STATE_LINE
+ "standroid_status=$?" + STATE_LINE
+ "exec 3<&-" + STATE_LINE
                + "standroid_encoded=$(printf '%s' \"$standroid_name\""
                + " | base64 | tr -d '\\n') || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_output=$(( standroid_output + ${#standroid_encoded}"
                + " + ${#standroid_status} + 2 ))" + STATE_LINE
                + "[ \"$standroid_output\" -le " + FOLDER_SCRIPT_MAX_OUTPUT_CHARS + " ] || exit "
                + FOLDER_LIMIT_EXIT_CODE + STATE_LINE
                + "printf '%s %s\\n' \"$standroid_encoded\" \"$standroid_status\"" + STATE_LINE
                + "done" + STATE_LINE
                + "exit 0" + STATE_LINE;
    }

    /**
     * Builds the exact-process I/O priority command.
     *
     * <p>The command re-reads the recorded process' own {@code stat} entry and compares its start
     * time with the execution the session verified, so an identifier the kernel reused for some
     * other process after that verification cannot reach the platform's {@code ionice}. A stat
     * entry that cannot be read, or a start time that differs from the recorded one, is refused
     * with its own status instead of tuning whatever the identifier now names.</p>
     *
     * <p>The command never selects a process by name, keeps the target identifier in one variable,
     * and uses the best-effort class and level Android supports.</p>
     *
     * <p>The recorded entry is compared column by column, so the compared column is the same one
     * {@link #parseStartTimeTicks(String)} reads after the executable name.</p>
     */
    String ioPriorityScript(int pid, long expectedStartTimeTicks) {
        return stateUidGuard()
                + "command -v ionice >/dev/null 2>&1 || exit "
                + TUNING_NOT_APPLICABLE_EXIT_CODE + STATE_LINE
                + "standroid_pid=" + pid + STATE_LINE
                + "standroid_stat=$(cat " + quote("/proc/" + pid + "/stat")
                + " 2>/dev/null)" + STATE_LINE
                + "case \"$standroid_stat\" in *\") \"*) ;; *) exit "
                + TUNING_IDENTITY_MISMATCH_EXIT_CODE + " ;; esac" + STATE_LINE
                + "set -- ${standroid_stat##*\") \"}" + STATE_LINE
                + "standroid_fields=" + START_TIME_FIELD_INDEX + STATE_LINE
                + "while [ \"$standroid_fields\" -gt 0 ]; do shift 1 2>/dev/null; "
                + "standroid_fields=$(( standroid_fields - 1 )); done" + STATE_LINE
                + "[ \"${1:-}\" = " + quote(Long.toString(expectedStartTimeTicks))
                + " ] || exit " + TUNING_IDENTITY_MISMATCH_EXIT_CODE + STATE_LINE
                + "ionice -c " + IO_PRIORITY_CLASS + " -n " + IO_PRIORITY_LEVEL
                + " -p \"$standroid_pid\""
                + " || exit " + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "exit 0" + STATE_LINE;
    }

    /**
     * Builds the explicit inotify watch-limit maintenance command.
     *
     * <p>The command only raises the limit. A limit that is already sufficient is reported as
     * success without a write, even when the kernel file cannot be written, so a device whose
     * limit is already high enough is never mistaken for a device without the setting. Every
     * write is read back, so a setting the kernel silently rejected never reports success.</p>
     */
    String inotifyWatchLimitScript(int watchLimit) {
        return stateUidGuard()
                + "standroid_limit=" + quote("/proc/sys/fs/inotify/max_user_watches") + STATE_LINE
                + "standroid_current=$(cat \"$standroid_limit\" 2>/dev/null)" + STATE_LINE
                + "standroid_sufficient=0" + STATE_LINE
                + "case \"$standroid_current\" in" + STATE_LINE
                + "''|*[!0-9]*) ;;" + STATE_LINE
                + "*) if [ \"$standroid_current\" -ge " + watchLimit
                + " ]; then standroid_sufficient=1; fi ;;" + STATE_LINE
                + "esac" + STATE_LINE
                + "if [ \"$standroid_sufficient\" -eq 1 ]; then exit 0; fi" + STATE_LINE
                + "if [ ! -w \"$standroid_limit\" ]; then exit "
                + TUNING_NOT_APPLICABLE_EXIT_CODE + "; fi" + STATE_LINE
                + "case \"$standroid_current\" in" + STATE_LINE
                + "''|*[!0-9]*) exit " + FOLDER_ACCESS_EXIT_CODE + " ;;" + STATE_LINE
                + "esac" + STATE_LINE
                + "echo " + watchLimit + " > \"$standroid_limit\" || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "standroid_applied=$(cat \"$standroid_limit\" 2>/dev/null)" + STATE_LINE
                + "[ \"$standroid_applied\" = \"" + watchLimit + "\" ] || exit "
                + FOLDER_ACCESS_EXIT_CODE + STATE_LINE
                + "exit 0" + STATE_LINE;
    }

    static String parseRunToken(List<String> lines) {
        String prefix = RUN_TOKEN_ENVIRONMENT + "=";
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        return null;
    }

    static String runTokenScript(int pid) {
        return "tr '\\0' '\\n' < /proc/" + pid + "/environ 2>/dev/null";
    }

    private static int parsePositiveInt(String value) {
        try {
            return Math.max(Integer.parseInt(value), 0);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
