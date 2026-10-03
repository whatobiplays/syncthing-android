package com.nutomic.syncthingandroid.runtime;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

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
    public static final long REST_SHUTDOWN_REQUEST_DRAIN_WAIT_MS = 5_000;
    private static final long OBSERVATION_INTERVAL_MS = 100;
    // Handles retain only terminal state and a weak request reference; an undrained request must
    // block replacement launches without extending the lifetime of Volley or Android objects.
    private static final CopyOnWriteArrayList<RestShutdownRequest> UNQUIESCED_REQUESTS =
            new CopyOnWriteArrayList<>();

    public enum Outcome {
        EXITED,
        OWNERSHIP_LOST,
        SIGNAL_FAILED,
        EXIT_NOT_PROVEN,
        REST_SHUTDOWN_NOT_QUIESCENT
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
        RestShutdownRequest request();
    }

    /** A single recovery shutdown request that can be canceled and observed to terminal state. */
    public interface RestShutdownRequest {
        void cancel();

        boolean awaitTerminal(long timeoutMillis) throws InterruptedException;
    }

    private OwnedExecutionShutdown() { }

    /**
     * Reports whether an earlier recovery shutdown request may still be delivered.
     *
     * <p>Requests that could not be drained remain registered until their terminal event is
     * observed. Runtime launch admission checks this before starting any bundled command.</p>
     */
    public static boolean hasUnquiescedRestShutdownRequests() {
        for (RestShutdownRequest request : UNQUIESCED_REQUESTS) {
            try {
                if (request.awaitTerminal(0)) {
                    UNQUIESCED_REQUESTS.remove(request);
                } else {
                    return true;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return true;
            } catch (RuntimeException uncertain) {
                return true;
            }
        }
        return !UNQUIESCED_REQUESTS.isEmpty();
    }

    /** Fails closed before any bundled command can launch behind an undrained request. */
    public static void requireNoUnquiescedRestShutdownRequests() {
        if (hasUnquiescedRestShutdownRequests()) {
            throw new RecoveryShutdownRequestPendingException();
        }
    }

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

        RestShutdownRequest request = null;
        try {
            request = restShutdown.request();
        } catch (RuntimeException ignored) {
            // A failed request still receives the bounded signal escalation.
        }
        Outcome result;
        try {
            Outcome afterRest = waitThenCheck(identity, REST_SHUTDOWN_WAIT_MS, control, waiter);
            if (afterRest != null) {
                result = afterRest;
            } else {
                Outcome sigint = signalAndWait(
                        identity,
                        ExecutionOwnershipManager.Signal.SIGINT,
                        SIGINT_WAIT_MS,
                        control,
                        waiter
                );
                if (sigint != null && sigint != Outcome.SIGNAL_FAILED) {
                    result = sigint;
                } else {
                    Outcome sigkill = signalAndWait(
                            identity,
                            ExecutionOwnershipManager.Signal.SIGKILL,
                            SIGKILL_WAIT_MS,
                            control,
                            waiter
                    );
                    result = sigkill == null ? Outcome.EXIT_NOT_PROVEN : sigkill;
                }
            }
        } catch (InterruptedException interrupted) {
            if (!cancelAndDrainAfterInterruption(request)) {
                retainUnquiescedRequest(request);
                return Outcome.REST_SHUTDOWN_NOT_QUIESCENT;
            }
            throw interrupted;
        }
        return quiesceShutdownRequest(request, result);
    }

    private static Outcome quiesceShutdownRequest(
            RestShutdownRequest request,
            Outcome result
    ) throws InterruptedException {
        if (request == null) return result;
        try {
            if (request.awaitTerminal(REST_SHUTDOWN_REQUEST_DRAIN_WAIT_MS)) return result;
        } catch (InterruptedException interrupted) {
            if (!cancelAndDrainAfterInterruption(request)) {
                return Outcome.REST_SHUTDOWN_NOT_QUIESCENT;
            }
            throw interrupted;
        } catch (RuntimeException uncertain) {
            return cancelAndDrainAfterFailure(request, result);
        }

        return cancelAndDrainAfterFailure(request, result);
    }

    private static Outcome cancelAndDrainAfterFailure(
            RestShutdownRequest request,
            Outcome result
    ) {
        try {
            request.cancel();
        } catch (RuntimeException ignored) {
            // Terminal observation below remains the authority for future launch safety.
        }
        try {
            if (request.awaitTerminal(REST_SHUTDOWN_REQUEST_DRAIN_WAIT_MS)) return result;
            retainUnquiescedRequest(request);
            return Outcome.REST_SHUTDOWN_NOT_QUIESCENT;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            retainUnquiescedRequest(request);
            return Outcome.REST_SHUTDOWN_NOT_QUIESCENT;
        } catch (RuntimeException uncertain) {
            try {
                request.cancel();
            } catch (RuntimeException ignored) {
                // Keep the request registered when cancellation cannot be confirmed.
            }
            retainUnquiescedRequest(request);
            return Outcome.REST_SHUTDOWN_NOT_QUIESCENT;
        }
    }

    private static boolean cancelAndDrainAfterInterruption(RestShutdownRequest request) {
        if (request == null) return true;
        try {
            request.cancel();
        } catch (RuntimeException ignored) {
            // A terminal observation is still required before forgetting this request.
        }
        try {
            if (request.awaitTerminal(REST_SHUTDOWN_REQUEST_DRAIN_WAIT_MS)) return true;
        } catch (InterruptedException interruptedAgain) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException uncertain) {
            // The request remains unsafe to forget when terminal state cannot be observed.
        }
        retainUnquiescedRequest(request);
        return false;
    }

    private static void retainUnquiescedRequest(RestShutdownRequest request) {
        if (request != null) UNQUIESCED_REQUESTS.addIfAbsent(request);
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
