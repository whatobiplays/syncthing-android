package com.nutomic.syncthingandroid.runtime;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Allows one service-owned process launch while its startup or reset operation is still wanted.
 *
 * <p>The service revokes the permit on its main thread. The worker settles a blocked recovery or
 * commits process creation after recovery. These atomic transitions decide which action wins: a
 * successful revocation cancels startup, a settled recovery failure remains blocked, and a
 * committed launch follows the ordinary exact-ownership shutdown path if destruction arrives
 * afterward.</p>
 */
public final class LifecycleLaunchPermit {
    public enum State { OPEN, REVOKED, RECOVERY_BLOCKED, LAUNCH_COMMITTED }

    private final AtomicReference<State> mState = new AtomicReference<>(State.OPEN);

    /**
     * Attempts to prevent process creation and returns the state that won the race.
     *
     * <p>A later lifecycle decision must create a new permit.</p>
     */
    public State revoke() {
        while (true) {
            State current = mState.get();
            if (current != State.OPEN) return current;
            if (mState.compareAndSet(State.OPEN, State.REVOKED)) return State.REVOKED;
        }
    }

    /** Atomically settles a non-launchable recovery result against service cancellation. */
    public void commitRecoveryBlocked() {
        while (true) {
            State current = mState.get();
            switch (current) {
                case OPEN:
                    if (mState.compareAndSet(State.OPEN, State.RECOVERY_BLOCKED)) return;
                    break;
                case REVOKED:
                    throw new CancelledException();
                case RECOVERY_BLOCKED:
                    throw new IllegalStateException(
                            "Lifecycle recovery failure was already settled"
                    );
                case LAUNCH_COMMITTED:
                    throw new IllegalStateException(
                            "Lifecycle recovery failed after process launch was committed"
                    );
                default:
                    throw new IllegalStateException("Unexpected lifecycle launch permit state");
            }
        }
    }

    /** Atomically commits the launch immediately before process creation. */
    public void commitLaunch() {
        while (true) {
            State current = mState.get();
            switch (current) {
                case OPEN:
                    if (mState.compareAndSet(State.OPEN, State.LAUNCH_COMMITTED)) return;
                    break;
                case REVOKED:
                    throw new CancelledException();
                case RECOVERY_BLOCKED:
                    throw new IllegalStateException(
                            "Lifecycle process launch cannot follow blocked recovery"
                    );
                case LAUNCH_COMMITTED:
                    throw new IllegalStateException(
                            "Lifecycle process launch was already committed"
                    );
                default:
                    throw new IllegalStateException("Unexpected lifecycle launch permit state");
            }
        }
    }

    /** Expected worker outcome when a launch is revoked before a child process is created. */
    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Service-owned process launch was cancelled before process creation");
        }
    }
}
