package com.nutomic.syncthingandroid.runtime;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Allows one service-owned process launch while its startup or reset operation is still wanted.
 *
 * <p>The service revokes the permit on its main thread. The worker commits process creation at the
 * final launch boundary after recovery. The atomic transition decides which action wins: a
 * successful revocation prevents process creation, while a committed launch follows the ordinary
 * exact-ownership shutdown path if destruction arrives afterward.</p>
 */
public final class LifecycleLaunchPermit {
    public enum State { OPEN, REVOKED, LAUNCH_COMMITTED }

    private final AtomicReference<State> mState = new AtomicReference<>(State.OPEN);

    /**
     * Attempts to prevent process creation and returns the state that won the race.
     *
     * <p>A later lifecycle decision must create a new permit.</p>
     */
    public State revoke() {
        if (mState.compareAndSet(State.OPEN, State.REVOKED)) return State.REVOKED;
        return mState.get();
    }

    /** Throws the expected cancellation outcome when revocation already won the launch race. */
    public void checkNotRevoked() {
        if (mState.get() == State.REVOKED) throw new CancelledException();
    }

    /** Atomically commits the launch immediately before process creation. */
    public void commitLaunch() {
        if (mState.compareAndSet(State.OPEN, State.LAUNCH_COMMITTED)) return;
        if (mState.get() == State.REVOKED) throw new CancelledException();
        throw new IllegalStateException("Lifecycle process launch was already committed");
    }

    /** Expected worker outcome when a launch is revoked before a child process is created. */
    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Service-owned process launch was cancelled before process creation");
        }
    }
}
