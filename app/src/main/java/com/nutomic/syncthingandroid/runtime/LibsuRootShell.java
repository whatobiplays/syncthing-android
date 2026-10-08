package com.nutomic.syncthingandroid.runtime;

import com.topjohnwu.superuser.Shell;
import com.google.common.io.BaseEncoding;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    /** Runs one state command and maps a failed transport to its own typed root failure. */
    private Shell.Result runStateJob(String command, List<String> stdout, String operation)
            throws IOException {
        try {
            // Keep top-level exit commands inside a child shell so libsu's persistent shell
            // survives long enough to deliver its job framing and status. Android Toybox's
            // timeout owns a separate process group and waits for it after TERM/KILL, so a timed
            // out state script cannot keep mutating files after this transport reports failure.
            Shell.Result result = runJob(
                    "(\n" + boundedStateCommand(command) + "\n)",
                    stdout,
                    new ArrayList<>()
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
        long killGraceMillis = Math.min(1_000, Math.max(1, operationTimeoutMillis / 10));
        long transportReserveMillis = Math.min(1_000, Math.max(1, operationTimeoutMillis / 10));
        long scriptTimeoutMillis = Math.max(
                1,
                operationTimeoutMillis - killGraceMillis - transportReserveMillis
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
        Future<Shell.Result> future = OPERATION_EXECUTOR.submit(
                () -> shell.newJob().add(command).to(stdout, stderr).exec()
        );
        try {
            return future.get(operationTimeoutMillis, TimeUnit.MILLISECONDS);
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
