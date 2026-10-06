package com.nutomic.syncthingandroid.runtime;

import java.util.Objects;

/**
 * Durable identity evidence for one bundled Syncthing process.
 *
 * <p>The PID is meaningful only together with the process start time, boot ID, executable path,
 * and run token. Callers must verify every field against a live process before sending a signal.
 * The kernel's {@code " (deleted)"} marker on a procfs executable link is ignored when comparing
 * executable targets; no other path normalization is performed.</p>
 */
public final class ExecutionIdentity {
    private static final String PROC_DELETED_SUFFIX = " (deleted)";
    private final int pid;
    private final long processStartTimeTicks;
    private final String bootId;
    private final String executablePath;
    private final String runToken;

    public ExecutionIdentity(
            int pid,
            long processStartTimeTicks,
            String bootId,
            String executablePath,
            String runToken
    ) {
        if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
        if (processStartTimeTicks <= 0) {
            throw new IllegalArgumentException("process start time must be positive");
        }
        this.pid = pid;
        this.processStartTimeTicks = processStartTimeTicks;
        this.bootId = requireText(bootId, "bootId");
        this.executablePath = requireText(executablePath, "executablePath");
        this.runToken = requireValue(runToken, "runToken");
    }

    public int pid() {
        return pid;
    }

    public long processStartTimeTicks() {
        return processStartTimeTicks;
    }

    public String bootId() {
        return bootId;
    }

    public String executablePath() {
        return executablePath;
    }

    public String runToken() {
        return runToken;
    }

    boolean matches(ExecutionIdentity other) {
        return other != null
                && pid == other.pid
                && processStartTimeTicks == other.processStartTimeTicks
                && bootId.equals(other.bootId)
                && sameExecutableTarget(executablePath, other.executablePath)
                && runToken.equals(other.runToken);
    }

    boolean sameProcess(ExecutionIdentity other) {
        return other != null
                && pid == other.pid
                && processStartTimeTicks == other.processStartTimeTicks
                && bootId.equals(other.bootId)
                && sameExecutableTarget(executablePath, other.executablePath);
    }

    /**
     * Reports whether both identities describe the same kernel process, ignoring what the
     * process currently executes.
     *
     * <p>A process keeps its PID, start time, and boot identity across {@code exec}, so this is
     * the comparison that recognizes the handoff window in which a launch script has already
     * written its durable evidence but has not replaced the shell with the bundled binary
     * yet.</p>
     */
    boolean sameKernelProcess(ExecutionIdentity other) {
        return other != null
                && pid == other.pid
                && processStartTimeTicks == other.processStartTimeTicks
                && bootId.equals(other.bootId);
    }

    static boolean sameExecutableTarget(String firstPath, String secondPath) {
        return withoutProcDeletedSuffix(firstPath).equals(withoutProcDeletedSuffix(secondPath));
    }

    private static String withoutProcDeletedSuffix(String path) {
        return path.endsWith(PROC_DELETED_SUFFIX)
                ? path.substring(0, path.length() - PROC_DELETED_SUFFIX.length())
                : path;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        return value;
    }

    private static String requireValue(String value, String name) {
        return Objects.requireNonNull(value, name);
    }
}
