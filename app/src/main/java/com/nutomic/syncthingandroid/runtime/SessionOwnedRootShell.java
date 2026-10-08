package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Session-owned view of one root shell that several operations share.
 *
 * <p>Inspection and signal transports close every helper shell they acquire, which is correct for
 * their normal operation-scoped use: each call acquires a fresh bounded shell and closes it when
 * the call ends. A bounded recovery session is different. It must run several operations — candidate
 * discovery, boot identifier read, record inspection, and possibly a signal — inside one root
 * session, because every acquisition may ask the user for root authorization again and because the
 * session has one deadline.</p>
 *
 * <p>This view separates ownership from use. Closing the view is a no-op, so an operation that
 * believes it owns its shell cannot close the shared session early. The code that created the
 * session closes the real shell exactly once after the session ends, including when an operation
 * fails.</p>
 */
final class SessionOwnedRootShell implements RootShell {
    private final RootShell sessionShell;

    SessionOwnedRootShell(RootShell sessionShell) {
        this.sessionShell = Objects.requireNonNull(sessionShell);
    }

    @Override
    public List<ProcessEntry> listProcesses() throws IOException {
        return sessionShell.listProcesses();
    }

    @Override
    public String readRunToken(int pid) throws IOException {
        return sessionShell.readRunToken(pid);
    }

    @Override
    public int pid() throws IOException {
        return sessionShell.pid();
    }

    @Override
    public boolean sendSignal(int pid, int signal) throws IOException {
        return sessionShell.sendSignal(pid, signal);
    }

    @Override
    public void execTerminalScript(String script) throws IOException {
        sessionShell.execTerminalScript(script);
    }

    @Override
    public boolean hasExited() {
        return sessionShell.hasExited();
    }

    @Override
    public int awaitExit() throws InterruptedException {
        return sessionShell.awaitExit();
    }

    /**
     * Reports the provenance the underlying transport recorded during acquisition.
     *
     * <p>A session-owned shell only ever serves helper operations, so this value is never used for
     * an exit decision; it answers because every root shell exposes the transport contract.</p>
     */
    @Override
    public boolean exitStatusBelongsToLaunchedProcess() {
        return sessionShell.exitStatusBelongsToLaunchedProcess();
    }

    @Override
    public String readBootId() throws IOException {
        return sessionShell.readBootId();
    }

    @Override
    public byte[] readStateFile(ManagedStateMember member) throws IOException {
        return sessionShell.readStateFile(member);
    }

    @Override
    public void writeStateFile(ManagedStateMember member, byte[] contents) throws IOException {
        sessionShell.writeStateFile(member, contents);
    }

    @Override
    public void removeStateMember(ManagedStateMember member) throws IOException {
        sessionShell.removeStateMember(member);
    }

    @Override
    public boolean stateMemberExists(ManagedStateMember member) throws IOException {
        return sessionShell.stateMemberExists(member);
    }

    @Override
    public void stageManagedStateForApp(String operationName) throws IOException {
        sessionShell.stageManagedStateForApp(operationName);
    }

    @Override
    public void removeStagingDirectory(String operationName) throws IOException {
        sessionShell.removeStagingDirectory(operationName);
    }

    @Override
    public void installStagedManagedState(String operationName) throws IOException {
        sessionShell.installStagedManagedState(operationName);
    }

    @Override
    public void repairManagedStateAccess() throws IOException {
        sessionShell.repairManagedStateAccess();
    }

    /** Ignored on purpose; the session that created the real shell closes it. */
    @Override
    public void close() {
    }
}
