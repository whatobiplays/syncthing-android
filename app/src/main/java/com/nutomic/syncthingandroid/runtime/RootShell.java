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
 * <p>Before a shell is designated as a launch transport, the activation boundary captures its
 * immutable kernel identity through {@link #pid()}, {@link #listProcesses()} and
 * {@link #readBootId()}. After the designation the shell performs exactly one audited terminal
 * {@link #execTerminalScript(String)} and no helper or process-metadata operation; the remaining
 * lifecycle operations ({@link #hasExited()}, {@link #awaitExit()} and {@link #close()}) never run
 * a command.</p>
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
     * Lists the process entries visible to this UID-0 shell.
     *
     * <p>This is a helper-session operation. The activation boundary reads the list once while it
     * captures the identity of the dedicated launch transport, and that transport runs no process
     * listing after the designation.</p>
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
     * <p>The identifier names the process that the launch script replaces by {@code exec}, so the
     * durable pre-delivery state can be matched against the live process after an application
     * restart. This is a transport identity accessor, not a command surface: implementations
     * answer it from their own process state and never from caller-supplied text.</p>
     *
     * <p>Only the activation boundary calls this on the dedicated launch transport, and only
     * before the transport is designated, so a launch shell answers it during activation or
     * never.</p>
     *
     * @throws IOException when the shell cannot report its process identifier
     */
    int pid() throws IOException;

    /**
     * Sends one signal that the target ownership manager has verified.
     *
     * @return {@code true} when the kernel accepted the signal delivery request
     */
    boolean sendSignal(int pid, int signal) throws IOException;

    /**
     * Writes the single audited launch script to this shell's standard input.
     *
     * <p>The script ends in the terminal {@code exec} of the bundled binary. After a successful
     * call the shell process belongs to Syncthing, so the shell must not be reused, written to, or
     * closed while that process may still be alive.</p>
     */
    void execTerminalScript(String script) throws IOException;

    /**
     * Reports whether this shell transport's underlying client process terminated.
     *
     * <p>This is transport state, not exact execution ownership evidence. Daemon-backed root
     * managers may keep the UID-0 process alive after the local {@code su} client disappears, so
     * callers must verify the owned launch independently before treating this as a process
     * exit.</p>
     */
    boolean hasExited();

    /**
     * Blocks until this shell transport's underlying client process terminates.
     *
     * <p>The returned status is the launched process's status only while the transport stayed
     * attached through the process's exit; {@link #exitStatusBelongsToLaunchedProcess()} reports
     * whether that is the case. Exact execution state must be verified separately before any
     * cleanup or lifecycle finalization.</p>
     */
    int awaitExit() throws InterruptedException;

    /**
     * Reports whether {@link #awaitExit()}'s status is the launched process's own status.
     *
     * <p>Only a transport whose local client process is replaced by the shell it drives can
     * attribute the exit status to the launched process: the terminal {@code exec} then turns that
     * client into the bundled process, so the awaited status is Syncthing's own status. A transport
     * whose local client stays a separate process, or that cannot prove the relationship, must
     * report {@code false}: the status then describes the client and may be reported even while
     * the UID-0 process keeps running.</p>
     *
     * <p>The value is determined inside the transport boundary while the shell is still alive and
     * is immutable afterwards, so it can be read after the client has exited. It never authorizes
     * cleanup or signaling by itself.</p>
     */
    boolean exitStatusBelongsToLaunchedProcess();

    /**
     * Reads the kernel boot identifier that scopes durable process identity.
     *
     * <p>This is a helper-session operation. The activation boundary reads it once while it
     * captures the identity of the dedicated launch transport, and that transport runs no boot
     * identifier read after the designation.</p>
     */
    String readBootId() throws IOException;

    /**
     * Reads one approved Managed State regular file with byte-exact content.
     *
     * <p>The transfer is a fixed, byte-preserving operation of the root transport: the shell encodes
     * the file exactly as it exists, so no line-oriented read and no trailing-newline
     * normalization can alter it. Callers name an approved member and never a path.</p>
     *
     * @return the exact file content, or {@code null} when the member is absent
     * @throws IOException when the member exists but cannot be read, is not a regular file, or is a
     *     symbolic link
     */
    byte[] readStateFile(ManagedStateMember member) throws IOException;

    /**
     * Replaces one approved Managed State regular file atomically.
     *
     * <p>The command writes through an operation-owned temporary file and renames it over the
     * member, so no reader observes a half-written file and a failed operation leaves the previous
     * content in place.</p>
     *
     * @throws IOException when the member cannot be replaced
     */
    void writeStateFile(ManagedStateMember member, byte[] contents) throws IOException;

    /**
     * Removes one approved Managed State member without following symbolic links.
     *
     * <p>A member that is already absent is a successful no-op.</p>
     *
     * @throws IOException when a present member cannot be removed
     */
    void removeStateMember(ManagedStateMember member) throws IOException;

    /**
     * Reports whether one approved member exists with its required filesystem kind.
     *
     * <p>A symbolic link and a member of the wrong kind both report {@code false}, because neither
     * may be treated as readable Managed State.</p>
     *
     * @throws IOException when the shell cannot answer
     */
    boolean stateMemberExists(ManagedStateMember member) throws IOException;

    /**
     * Copies Managed State into one fresh operation-owned staging directory and hands it to the
     * application.
     *
     * <p>The operation name is one the application generated below the fixed staging base. The
     * command refuses to reuse an existing directory, copies only the approved members, refuses
     * symbolic links and special files anywhere in the copied tree, changes ownership and mode only
     * inside the staged tree, establishes the security context when the kernel enforces SELinux,
     * and proves the handoff before it reports success. Every failure removes only this operation's
     * staging tree and reports the failure.</p>
     *
     * @throws IOException when the snapshot or the handoff to the application failed
     */
    void stageManagedStateForApp(String operationName) throws IOException;

    /**
     * Removes one operation-owned staging directory without following symbolic links.
     *
     * <p>The command removes only that one directory tree and never touches a sibling or the
     * staging base itself.</p>
     *
     * @throws IOException when the directory cannot be removed
     */
    void removeStagingDirectory(String operationName) throws IOException;

    /**
     * Installs the staged members of one application-owned staging directory into Managed State.
     *
     * <p>Only approved members replace live state, and the required members must be staged as
     * regular files. When {@code index-v2} is not staged, the live index database is removed
     * instead, so a legacy archive never leaves stale database state behind. Symbolic links and
     * special files in the staged tree fail the operation before live state is touched.</p>
     *
     * @throws IOException when the staged tree is unsafe or installation failed
     */
    void installStagedManagedState(String operationName) throws IOException;

    /**
     * Restores application access to the approved Managed State members.
     *
     * <p>The command repairs ownership, mode, and the security context of exactly the approved
     * members and verifies the result. It is idempotent and retry-safe: a repair that only partly
     * completed can be repeated, including through a new helper session. It never walks the
     * application data directory and never follows symbolic links.</p>
     *
     * @throws IOException when a member cannot be repaired or the repair cannot be verified
     */
    void repairManagedStateAccess() throws IOException;

    @Override
    void close();
}
