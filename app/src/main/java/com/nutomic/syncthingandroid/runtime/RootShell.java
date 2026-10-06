package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.List;

/**
 * One explicit root shell owned by the root transport boundary.
 *
 * <p>Implementations carry the direct shell mechanics; callers receive only the semantic
 * operations declared here and never a generic command surface. A shell instance is either an
 * operation-scoped helper or the dedicated launch shell of exactly one bundled Syncthing
 * invocation, and it is never shared between the two roles.</p>
 *
 * <p>The dedicated launch shell is a raw launch transport. The activation boundary captures
 * its immutable kernel identity through {@link #pid()}, {@link #listProcesses()} and
 * {@link #readBootId()} while that boundary establishes and verifies the shell, before any
 * caller designates the shell as the launch transport. After designation the shell performs
 * exactly one audited terminal {@link #execTerminalScript(String)} and no helper or
 * process-metadata operation; the remaining lifecycle operations ({@link #hasExited()},
 * {@link #awaitExit()} and {@link #close()}) never run a command.</p>
 *
 * <p>{@link #execTerminalScript(String)} transports raw launch text and is restricted to the
 * audited typed launch path inside this package. It must never become a general command API for
 * application callers.</p>
 */
interface RootShell extends AutoCloseable {
    /** One {@code /proc} entry that could be read, possibly with an unreadable executable link. */
    final class ProcessEntry {
        private final int pid;
        private final String executablePath;
        private final long processStartTimeTicks;

        ProcessEntry(int pid, String executablePath, long processStartTimeTicks) {
            this.pid = pid;
            this.executablePath = executablePath;
            this.processStartTimeTicks = processStartTimeTicks;
        }

        int pid() {
            return pid;
        }

        /** Returns the executable target, or {@code null} when the link could not be read. */
        String executablePath() {
            return executablePath;
        }

        /** Returns the process start time, or {@code 0} when the stat entry could not be read. */
        long processStartTimeTicks() {
            return processStartTimeTicks;
        }
    }

    /**
     * Lists every process entry visible to a UID-0 shell.
     *
     * <p>This is a helper-session operation. The activation boundary reads the list once while
     * it captures the identity of a dedicated launch transport, and the transport runs no
     * process listing after designation.</p>
     */
    List<ProcessEntry> listProcesses() throws IOException;

    /**
     * Reads the run token environment value of one process.
     *
     * @return the recorded token, or {@code null} when the process carries no such value
     */
    String readRunToken(int pid) throws IOException;

    /**
     * Returns the kernel process identifier of this shell's own transport process.
     *
     * <p>The identifier names the process that a launch script replaces by {@code exec}, so durable
     * pre-delivery state can be matched against the live process after an application restart. It
     * is a transport identity accessor, not a command surface: implementations answer it from
     * their own process state and never from caller-supplied text.</p>
     *
     * <p>Only the activation boundary calls this on a dedicated launch transport, and only
     * before that transport is designated, so a launch shell answers it during activation or
     * never.</p>
     *
     * @throws IOException when the shell cannot report its process identifier
     */
    int pid() throws IOException;

    /**
     * Sends one signal to a target the ownership manager has verified.
     *
     * @return {@code true} when the kernel accepted the signal delivery request
     */
    boolean sendSignal(int pid, int signal) throws IOException;

    /**
     * Writes the single audited launch script to this shell's standard input.
     *
     * <p>The script ends in a terminal {@code exec} of the bundled binary. After a successful call
     * the shell process belongs to Syncthing, so the shell must not be reused, written to, or
     * closed while that process may still be alive.</p>
     */
    void execTerminalScript(String script) throws IOException;

    /** Reports whether the underlying process already terminated. */
    boolean hasExited();

    /**
     * Blocks until the underlying process terminates.
     *
     * @return the exit status the kernel reported for the launched process
     */
    int awaitExit() throws InterruptedException;

    /**
     * Reads the kernel boot identifier that scopes durable process identity.
     *
     * <p>This is a helper-session operation. The activation boundary reads it once while it
     * captures the identity of a dedicated launch transport, and the transport runs no
     * boot identifier read after designation.</p>
     */
    String readBootId() throws IOException;

    @Override
    void close();
}
