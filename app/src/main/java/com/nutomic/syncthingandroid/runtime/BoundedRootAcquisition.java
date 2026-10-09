package com.nutomic.syncthingandroid.runtime;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Waits for one bounded root activation and hands back the acquired session.
 *
 * <p>The privileged tuning capabilities acquire their helper sessions through this one
 * implementation, so their timeout, cancellation, and ownership rules cannot drift apart. The Root
 * Mode folder operations, the launch path and the recovery path acquire their sessions inside
 * {@code RootBackend}, which keeps its own bounded acquisition.</p>
 *
 * <p>Ownership is decided by the request rather than by the worker's future value: a timeout that
 * races the handoff may discard the future, and the acquired shell must still not be lost.</p>
 */
final class BoundedRootAcquisition {
    private BoundedRootAcquisition() {
    }

    /**
     * Runs one activation request on the given worker and waits for the session it publishes.
     *
     * @param activation    activation boundary that owns the request
     * @param worker        worker the acquisition runs on, so a pending root prompt cannot block
     *                      the calling thread's own work
     * @param timeoutMillis caller-visible activation deadline
     * @param request       request that receives the acquired session
     * @throws RootTransportException when the deadline expires, the acquisition fails, or the
     *                                caller is interrupted
     */
    static RootShellSession acquireBounded(
            RootActivation activation,
            ExecutorService worker,
            long timeoutMillis,
            RootActivation.Request request
    ) {
        Future<?> future = worker.submit(() -> {
            activation.acquire(request);
            return null;
        });
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            closeQuietly(request.cancelAndTake());
            throw new RootTransportException(
                    RootFailure.ROOT_ACTIVATION_TIMEOUT,
                    "Root activation exceeded the bounded activation deadline",
                    e
            );
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RootTransportException) {
                throw (RootTransportException) cause;
            }
            throw new RootTransportException(
                    RootFailure.ROOT_TRANSPORT_FAILED,
                    "Root activation failed",
                    cause
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            closeQuietly(request.cancelAndTake());
            throw new RootTransportException(
                    RootFailure.ROOT_TRANSPORT_FAILED,
                    "Root activation was interrupted",
                    e
            );
        }
        RootShellSession session = request.take();
        if (session == null) {
            throw new RootTransportException(
                    RootFailure.ROOT_ACTIVATION_OBSOLETE,
                    "Root activation completed without a root shell owned by the requesting caller"
            );
        }
        return session;
    }

    /** Closes one session without letting a teardown failure hide the operation's own outcome. */
    static void closeQuietly(RootShellSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException ignored) {
            // The operation's own result outranks a failed teardown of its session.
        }
    }
}
