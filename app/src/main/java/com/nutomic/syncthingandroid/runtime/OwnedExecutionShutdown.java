package com.nutomic.syncthingandroid.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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
    // A request lease and the start of a process creation are mutually exclusive. The monitor is
    // held only while changing this coordinator state, never while the backend starts a process.
    private static final Object COORDINATOR_MONITOR = new Object();
    private static final List<RequestLease> PENDING_REQUESTS = new ArrayList<>();
    private static boolean processStartInProgress;

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

    /** Prepares the REST shutdown request without making it deliverable. */
    @FunctionalInterface
    public interface RestShutdown {
        /** Prepares a request without making it deliverable. */
        RestShutdownRequest prepare();
    }

    /** A prepared recovery request that is registered before it can become deliverable. */
    public interface RestShutdownRequest {
        /** Registers the shutdown owner to be notified when delivery becomes terminal. */
        void setTerminalListener(Runnable listener);

        /**
         * Enqueues the request. A false result guarantees that it was never deliverable; a thrown
         * exception leaves delivery uncertain and therefore keeps replacement admission blocked.
         */
        boolean send();

        void cancel();

        boolean awaitTerminal(long timeoutMillis) throws InterruptedException;
    }

    private OwnedExecutionShutdown() { }

    /** Permit held only across backend process creation, coordinated with request registration. */
    public static final class LaunchPermit implements AutoCloseable {
        private boolean released;

        private LaunchPermit() { }

        @Override
        public void close() {
            synchronized (COORDINATOR_MONITOR) {
                if (released) return;
                released = true;
                processStartInProgress = false;
                COORDINATOR_MONITOR.notifyAll();
            }
        }
    }

    /** Tracks one prepared request without invoking transport code while holding the coordinator. */
    private static final class RequestLease {
        private final RestShutdownRequest request;
        private final AtomicBoolean terminal = new AtomicBoolean();

        private RequestLease(RestShutdownRequest request) {
            this.request = request;
        }

        private boolean isTerminal() {
            return terminal.get();
        }

        private void observeTerminal() {
            if (terminal.compareAndSet(false, true)) releaseRequestLease(this);
        }
    }

    /**
     * Reserves the process-creation boundary against a recovery request becoming deliverable.
     * Lifecycle launches wait for existing request leases; one-shots fail closed instead.
     */
    public static LaunchPermit acquireLaunchPermit(boolean waitForPendingRequests)
            throws InterruptedException {
        while (true) {
            hasUnquiescedRestShutdownRequests();
            synchronized (COORDINATOR_MONITOR) {
                removeObservedTerminalLeasesLocked();
                boolean pending = !PENDING_REQUESTS.isEmpty();
                if (!pending && !processStartInProgress) {
                    processStartInProgress = true;
                    return new LaunchPermit();
                }
                if (!waitForPendingRequests) {
                    if (pending) throw new RecoveryShutdownRequestPendingException();
                    throw new ExecutionAdmissionException();
                }
                COORDINATOR_MONITOR.wait();
            }
        }
    }

    /**
     * Waits until any committed backend process creation has returned and can be inspected.
     * Recovery scans use this so they cannot mistake an in-flight start for an absent process.
     */
    public static void awaitProcessStartQuiescence() throws InterruptedException {
        synchronized (COORDINATOR_MONITOR) {
            while (processStartInProgress) COORDINATOR_MONITOR.wait();
        }
    }

    /** Waits for older shutdown requests before a lifecycle start inspects process ownership. */
    public static void awaitNoUnquiescedRestShutdownRequests() throws InterruptedException {
        while (true) {
            if (!hasUnquiescedRestShutdownRequests()) return;
            synchronized (COORDINATOR_MONITOR) {
                removeObservedTerminalLeasesLocked();
                if (PENDING_REQUESTS.isEmpty()) continue;
                COORDINATOR_MONITOR.wait();
            }
        }
    }

    /**
     * Reports whether an earlier recovery shutdown request may still be delivered.
     *
     * <p>Requests that could not be drained remain registered until their terminal event is
     * observed. Runtime launch admission checks this before starting any bundled command.</p>
     */
    public static boolean hasUnquiescedRestShutdownRequests() {
        List<RequestLease> snapshot;
        synchronized (COORDINATOR_MONITOR) {
            snapshot = new ArrayList<>(PENDING_REQUESTS);
        }
        for (RequestLease lease : snapshot) {
            try {
                if (lease.request.awaitTerminal(0)) lease.observeTerminal();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException uncertain) {
                // An unobservable request remains registered until its terminal callback arrives.
            }
        }
        synchronized (COORDINATOR_MONITOR) {
            removeObservedTerminalLeasesLocked();
            return !PENDING_REQUESTS.isEmpty();
        }
    }

    private static void removeObservedTerminalLeasesLocked() {
        // Iterating explicitly keeps this cleanup available on every supported API level;
        // Collection.removeIf requires a newer platform than the project minimum.
        boolean removed = false;
        java.util.Iterator<RequestLease> leases = PENDING_REQUESTS.iterator();
        while (leases.hasNext()) {
            if (leases.next().isTerminal()) {
                leases.remove();
                removed = true;
            }
        }
        if (removed) COORDINATOR_MONITOR.notifyAll();
    }

    private static void releaseRequestLease(RequestLease lease) {
        synchronized (COORDINATOR_MONITOR) {
            PENDING_REQUESTS.remove(lease);
            COORDINATOR_MONITOR.notifyAll();
        }
    }

    private static boolean registerPendingRequest(RequestLease lease) {
        synchronized (COORDINATOR_MONITOR) {
            removeObservedTerminalLeasesLocked();
            if (processStartInProgress || !PENDING_REQUESTS.isEmpty() || lease.isTerminal()) {
                return false;
            }
            PENDING_REQUESTS.add(lease);
            COORDINATOR_MONITOR.notifyAll();
            return true;
        }
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

        Outcome ownershipAfterRequestRegistration = null;
        RestShutdownRequest request = null;
        RequestLease requestLease = null;
        boolean requestRegistered = false;
        try {
            request = restShutdown.prepare();
        } catch (RuntimeException ignored) {
            // A failed request still receives the bounded signal escalation.
        }
        if (request != null) {
            try {
                requestLease = new RequestLease(request);
                RequestLease preparedLease = requestLease;
                request.setTerminalListener(preparedLease::observeTerminal);
                if (registerPendingRequest(requestLease)) {
                    requestRegistered = true;

                    // Preparing the HTTP request can take long enough for the execution we verified
                    // above to exit. The registered lease now excludes every replacement launch,
                    // so this is the last safe point to re-verify the exact owner before the
                    // endpoint-scoped request can become deliverable. If A is already gone or no
                    // longer ours, never let a request prepared for A reach a later execution B.
                    ownershipAfterRequestRegistration = currentOwnership(identity, control);
                    if (ownershipAfterRequestRegistration != null) {
                        requestLease.observeTerminal();
                        request = null;
                        requestLease = null;
                        requestRegistered = false;
                    } else {
                        try {
                            if (!request.send()) {
                                requestLease.observeTerminal();
                                request = null;
                                requestLease = null;
                                requestRegistered = false;
                            }
                        } catch (RuntimeException | Error uncertainDelivery) {
                            // Keep the lease: Volley may have accepted the request before failing.
                            // Continue bounded exact-owner shutdown; only terminal observation can
                            // release replacement launch after uncertain delivery.
                        }
                    }
                } else {
                    // A process start owns the short launch boundary, or the prepared request is
                    // already terminal. In either case it was never made deliverable.
                    request = null;
                    requestLease = null;
                }
            } catch (RuntimeException notDeliverable) {
                // Listener setup happens before registration and queue admission.
                request = null;
                requestLease = null;
            }
        }
        Outcome result;
        try {
            if (ownershipAfterRequestRegistration != null) {
                result = ownershipAfterRequestRegistration;
            } else {
                Outcome afterRest = waitThenCheck(
                        identity, REST_SHUTDOWN_WAIT_MS, control, waiter
                );
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
            }
        } catch (InterruptedException interrupted) {
            if (!cancelAndDrainAfterInterruption(request, requestLease)) {
                return Outcome.REST_SHUTDOWN_NOT_QUIESCENT;
            }
            throw interrupted;
        }
        Outcome terminalResult = quiesceShutdownRequest(request, requestLease, result);
        if (requestRegistered && terminalResult != Outcome.REST_SHUTDOWN_NOT_QUIESCENT) {
            requestLease.observeTerminal();
        }
        return terminalResult;
    }

    private static Outcome quiesceShutdownRequest(
            RestShutdownRequest request,
            RequestLease lease,
            Outcome result
    ) throws InterruptedException {
        if (request == null) return result;
        try {
            if (request.awaitTerminal(REST_SHUTDOWN_REQUEST_DRAIN_WAIT_MS)) {
                lease.observeTerminal();
                return result;
            }
        } catch (InterruptedException interrupted) {
            if (!cancelAndDrainAfterInterruption(request, lease)) {
                return Outcome.REST_SHUTDOWN_NOT_QUIESCENT;
            }
            throw interrupted;
        } catch (RuntimeException uncertain) {
            return cancelAndDrainAfterFailure(request, lease, result);
        }

        return cancelAndDrainAfterFailure(request, lease, result);
    }

    private static Outcome cancelAndDrainAfterFailure(
            RestShutdownRequest request,
            RequestLease lease,
            Outcome result
    ) {
        try {
            request.cancel();
        } catch (RuntimeException ignored) {
            // Terminal observation below remains the authority for future launch safety.
        }
        try {
            if (request.awaitTerminal(REST_SHUTDOWN_REQUEST_DRAIN_WAIT_MS)) {
                lease.observeTerminal();
                return result;
            }
            return Outcome.REST_SHUTDOWN_NOT_QUIESCENT;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.REST_SHUTDOWN_NOT_QUIESCENT;
        } catch (RuntimeException uncertain) {
            try {
                request.cancel();
            } catch (RuntimeException ignored) {
                // Keep the request registered when cancellation cannot be confirmed.
            }
            return Outcome.REST_SHUTDOWN_NOT_QUIESCENT;
        }
    }

    private static boolean cancelAndDrainAfterInterruption(
            RestShutdownRequest request,
            RequestLease lease
    ) {
        if (request == null) return true;
        try {
            request.cancel();
        } catch (RuntimeException ignored) {
            // A terminal observation is still required before forgetting this request.
        }
        try {
            if (request.awaitTerminal(REST_SHUTDOWN_REQUEST_DRAIN_WAIT_MS)) {
                lease.observeTerminal();
                return true;
            }
        } catch (InterruptedException interruptedAgain) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException uncertain) {
            // The request remains unsafe to forget when terminal state cannot be observed.
        }
        return false;
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
