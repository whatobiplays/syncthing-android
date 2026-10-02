package com.nutomic.syncthingandroid.runtime;

import java.util.Objects;

/**
 * Applies the bounded shutdown policy to one durably owned Syncthing execution.
 *
 * <p>The initial REST, SIGINT, and SIGKILL waits are policy values that can be tuned from
 * qualification evidence. Every signal is delegated to an ownership-aware control, which must
 * verify the exact execution immediately before sending it.</p>
 */
public final class OwnedExecutionShutdown {
    public static final long REST_SHUTDOWN_WAIT_MS = 10_000;
    public static final long SIGINT_WAIT_MS = 5_000;
    public static final long SIGKILL_WAIT_MS = 5_000;
    private static final long OBSERVATION_INTERVAL_MS = 100;

    public enum Outcome {
        EXITED,
        OWNERSHIP_LOST,
        SIGNAL_FAILED,
        EXIT_NOT_PROVEN
    }

    /** Reports live ownership and sends a signal only after an immediate exact verification. */
    public interface ExecutionControl {
        ExecutionOwnershipManager.Observation observe(ExecutionIdentity identity);

        ExecutionOwnershipManager.SignalAttempt signalIfOwned(
                ExecutionIdentity identity,
                ExecutionOwnershipManager.Signal signal
        );
    }

    /** Waits for exact process exit without prescribing a real-time mechanism to tests. */
    @FunctionalInterface
    public interface Waiter {
        boolean awaitExit(
                ExecutionIdentity identity,
                long timeoutMillis,
                ExecutionControl control
        ) throws InterruptedException;
    }

    /** Sends the normal REST shutdown request when one is available. */
    @FunctionalInterface
    public interface RestShutdown {
        void request();
    }

    private OwnedExecutionShutdown() { }

    /**
     * Requests graceful exit, then escalates through SIGINT and SIGKILL if necessary.
     *
     * @param identity the durable identity of the execution to stop
     * @param restShutdown REST shutdown request for the owned execution
     * @param control exact ownership and signal operations
     * @param waiter bounded, fakeable exit observation
     * @return the strongest exit or ownership result observed during shutdown
     * @throws InterruptedException when the background lifecycle worker is interrupted
     */
    public static Outcome stop(
            ExecutionIdentity identity,
            RestShutdown restShutdown,
            ExecutionControl control,
            Waiter waiter
    ) throws InterruptedException {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(restShutdown);
        Objects.requireNonNull(control);
        Objects.requireNonNull(waiter);

        Outcome ownership = currentOwnership(identity, control);
        if (ownership != null) return ownership;

        try {
            restShutdown.request();
        } catch (RuntimeException ignored) {
            // A failed REST request still receives its bounded observation window.
        }
        Outcome afterRest = waitThenCheck(identity, REST_SHUTDOWN_WAIT_MS, control, waiter);
        if (afterRest != null) return afterRest;

        Outcome sigint = signalAndWait(
                identity, ExecutionOwnershipManager.Signal.SIGINT, SIGINT_WAIT_MS, control, waiter
        );
        if (sigint != null && sigint != Outcome.SIGNAL_FAILED) return sigint;

        Outcome sigkill = signalAndWait(
                identity, ExecutionOwnershipManager.Signal.SIGKILL, SIGKILL_WAIT_MS, control, waiter
        );
        return sigkill == null ? Outcome.EXIT_NOT_PROVEN : sigkill;
    }

    /** Uses Android's process observer and a monotonic clock for bounded production waits. */
    public static Waiter processWaiter() {
        return (identity, timeoutMillis, control) -> {
            long timeoutNanos = timeoutMillis * 1_000_000L;
            long deadline = System.nanoTime() + timeoutNanos;
            while (true) {
                if (control.observe(identity) == ExecutionOwnershipManager.Observation.EXITED) {
                    return true;
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) return false;
                long sleepNanos = Math.min(
                        remainingNanos, OBSERVATION_INTERVAL_MS * 1_000_000L
                );
                Thread.sleep(
                        sleepNanos / 1_000_000L,
                        (int) (sleepNanos % 1_000_000L)
                );
            }
        };
    }

    private static Outcome signalAndWait(
            ExecutionIdentity identity,
            ExecutionOwnershipManager.Signal signal,
            long waitMillis,
            ExecutionControl control,
            Waiter waiter
    ) throws InterruptedException {
        Outcome beforeSignal = currentOwnership(identity, control);
        if (beforeSignal != null) return beforeSignal;

        ExecutionOwnershipManager.SignalAttempt result = control.signalIfOwned(identity, signal);
        if (result != ExecutionOwnershipManager.SignalAttempt.SIGNALED) {
            Outcome afterFailedSignal = currentOwnership(identity, control);
            if (afterFailedSignal == Outcome.EXITED) return Outcome.EXITED;
            if (afterFailedSignal != null) return afterFailedSignal;
            Outcome afterFailedSignalWait = waitThenCheck(
                    identity, waitMillis, control, waiter
            );
            if (afterFailedSignalWait != null) return afterFailedSignalWait;
            if (result == ExecutionOwnershipManager.SignalAttempt.SIGNAL_FAILED) {
                return Outcome.SIGNAL_FAILED;
            }
            return signal == ExecutionOwnershipManager.Signal.SIGKILL
                    ? Outcome.EXIT_NOT_PROVEN
                    : null;
        }

        return waitThenCheck(identity, waitMillis, control, waiter);
    }

    private static Outcome waitThenCheck(
            ExecutionIdentity identity,
            long waitMillis,
            ExecutionControl control,
            Waiter waiter
    ) throws InterruptedException {
        if (waiter.awaitExit(identity, waitMillis, control)) return Outcome.EXITED;
        return currentOwnership(identity, control);
    }

    /** Returns {@code null} only when the identity remains both live and exactly owned. */
    private static Outcome currentOwnership(
            ExecutionIdentity identity,
            ExecutionControl control
    ) {
        switch (control.observe(identity)) {
            case EXITED:
                return Outcome.EXITED;
            case OWNED:
                return null;
            case NOT_OWNED:
            case UNKNOWN:
            default:
                return Outcome.OWNERSHIP_LOST;
        }
    }
}
