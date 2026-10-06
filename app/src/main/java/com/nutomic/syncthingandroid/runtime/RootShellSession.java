package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.Objects;

/**
 * One acquired root shell together with the immutable kernel identity that the activation boundary
 * captured while it verified that the shell runs as UID 0.
 *
 * <p>Ownership of a session is handed over atomically: either the waiting caller takes it, or the
 * cancellation path closes it, so an acquired shell always has exactly one owner.</p>
 *
 * <p>The identity is captured during activation, before a caller may designate the shell as the
 * dedicated launch transport. A transport identity therefore never has to run a helper operation
 * such as a process listing or a boot identifier read after designation: the dedicated launch
 * shell performs exactly one audited raw terminal script after that point.</p>
 *
 * <p>Helper sessions acquire their shell through the same boundary but need no transport metadata,
 * so their identity is not captured and {@link #transportIdentity(String, String)} refuses to build
 * one for them.</p>
 */
final class RootShellSession implements AutoCloseable {
    private final RootShell shell;
    private final boolean identityCaptured;
    private final int pid;
    private final long processStartTimeTicks;
    private final String bootId;

    private RootShellSession(
            RootShell shell,
            boolean identityCaptured,
            int pid,
            long processStartTimeTicks,
            String bootId
    ) {
        this.shell = Objects.requireNonNull(shell);
        this.identityCaptured = identityCaptured;
        this.pid = pid;
        this.processStartTimeTicks = processStartTimeTicks;
        this.bootId = bootId;
    }

    /** Wraps one shell whose identity is not needed, such as an operation-scoped helper session. */
    static RootShellSession withoutTransportIdentity(RootShell shell) {
        return new RootShellSession(shell, false, 0, 0, null);
    }

    /**
     * Captures the kernel identity of one freshly acquired, already UID-0-verified shell.
     *
     * <p>The capture runs while the activation boundary still owns the shell and before any caller
     * can designate it as the dedicated launch transport, so the launch transport itself never runs
     * a process listing or a boot identifier read after designation.</p>
     *
     * @throws IOException when the shell cannot report its process identifier, its process start
     *         time, or the kernel boot identifier, in which case the caller must close the shell
     */
    static RootShellSession captureTransportIdentity(RootShell shell) throws IOException {
        Objects.requireNonNull(shell);
        int pid = shell.pid();
        long startTicks = 0;
        for (RootShell.ProcessEntry entry : shell.listProcesses()) {
            if (entry.pid() == pid) {
                startTicks = entry.processStartTimeTicks();
                break;
            }
        }
        if (startTicks <= 0) {
            throw new IOException("Could not read the root shell process start time");
        }
        return new RootShellSession(shell, true, pid, startTicks, shell.readBootId());
    }

    RootShell shell() {
        return shell;
    }

    /**
     * Builds the durable transport identity of this shell for one prepared launch.
     *
     * <p>The identity names the bundled executable as the target the transport is expected to
     * become, exactly as the launch script records it in its own pre-exec evidence.</p>
     *
     * @throws IllegalStateException when this session captured no transport identity
     */
    ExecutionIdentity transportIdentity(String executablePath, String runToken) {
        if (!identityCaptured) {
            throw new IllegalStateException(
                    "This root shell session captured no transport identity"
            );
        }
        return new ExecutionIdentity(pid, processStartTimeTicks, bootId, executablePath, runToken);
    }

    @Override
    public void close() {
        shell.close();
    }
}
