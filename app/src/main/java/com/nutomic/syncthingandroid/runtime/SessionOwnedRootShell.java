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

    @Override
    public String readBootId() throws IOException {
        return sessionShell.readBootId();
    }

    /** Ignored on purpose; the session that created the real shell closes it. */
    @Override
    public void close() {
    }
}
