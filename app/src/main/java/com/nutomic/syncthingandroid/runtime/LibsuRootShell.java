package com.nutomic.syncthingandroid.runtime;

import com.topjohnwu.superuser.Shell;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private int shellPid;

    private boolean exitStatusProvenanceRecorded;
    private boolean exitStatusBelongsToLaunchedProcess;
    /** Set when a failed helper operation tore this transport down, such as on a timeout. */
    private volatile boolean transportInvalidatedByFailedOperation;

    LibsuRootShell(Shell shell, Process process) {
        this(shell, process, OPERATION_TIMEOUT_MILLIS);
    }

    /**
     * Creates one transport over an acquired shell with an explicit helper-operation bound.
     *
     * <p>Production acquires transports with {@link #OPERATION_TIMEOUT_MILLIS}; the override
     * exists so the cleanup behavior of a timing-out helper operation can be exercised
     * deterministically.</p>
     */
    LibsuRootShell(Shell shell, Process process, long operationTimeoutMillis) {
        this.shell = Objects.requireNonNull(shell);
        this.process = Objects.requireNonNull(process);
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
