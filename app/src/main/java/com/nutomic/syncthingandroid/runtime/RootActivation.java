package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.Objects;

/**
 * Fences bounded root activations against cancellation and supersession.
 *
 * <p>Root acquisition is asynchronous: a request may still be completing while its caller cancels
 * it. Every request therefore carries its own cancellation state, and a shell that arrives for a
 * cancelled request is closed instead of handed out, so it can never create a process or mutate
 * lifecycle state afterwards.</p>
 *
 * <p>Cancellation is request-local. Starting or cancelling one root operation never affects
 * another operation that is still wanted, so two legitimate operations both complete
 * independently.</p>
 *
 * <p>Ownership of an acquired shell is handed over atomically through {@link Request}: at every
 * instant the shell has exactly one owner. Either the waiting caller takes it with
 * {@link Request#take()}, or cancellation takes it with {@link Request#cancelAndTake()} and closes
 * it. A cancellation that races the handoff can therefore neither lose the shell nor leave it with
 * two owners.</p>
 *
 * <p>A request created with {@link #beginLaunchTransport()} also captures the acquired shell's
 * immutable kernel identity while the activation still owns the shell, so the dedicated launch
 * transport never runs a helper operation such as a process listing or a boot identifier read after
 * it has been designated.</p>
 *
 * <p>{@link #acquire(Request)} blocks the calling thread for at most the configured activation
 * deadline. Callers must not run it on Android's main or service threads; it belongs on a
 * dedicated activation worker.</p>
 */
final class RootActivation {
    /**
     * Pauses one activation at the shell handoff boundary, after the transport produced a shell
     * and before ownership of it is decided.
     *
     * <p>Package-private construction seam: JVM tests hold the handoff open here so they can race a
     * cancellation against it deterministically. Production construction uses {@link #NONE}.</p>
     */
    @FunctionalInterface
    interface HandoffBarrier {
        HandoffBarrier NONE = acquired -> { };

        void arrive(RootShell acquired);
    }

    private final RootShellFactory factory;
    private final long timeoutMillis;
    private final HandoffBarrier handoffBarrier;

    RootActivation(RootShellFactory factory, long timeoutMillis) {
        this(factory, timeoutMillis, HandoffBarrier.NONE);
    }

    /** Package-private construction seam used by JVM tests to pause the shell handoff boundary. */
    RootActivation(RootShellFactory factory, long timeoutMillis, HandoffBarrier handoffBarrier) {
        this.factory = Objects.requireNonNull(factory);
        this.handoffBarrier = Objects.requireNonNull(handoffBarrier);
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("The activation deadline must be positive");
        }
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Starts one activation request for an operation-scoped helper session.
     *
     * <p>The request can be cancelled independently of every other request.</p>
     */
    Request begin() {
        return new Request(false);
    }

    /**
     * Starts one activation request whose shell becomes the dedicated launch transport.
     *
     * <p>The activation captures the shell's kernel identity while it still owns the shell, so the
     * launch transport never performs a process listing or boot identifier read after the caller
     * designates it.</p>
     */
    Request beginLaunchTransport() {
        return new Request(true);
    }

    /**
     * Acquires one root shell for one bounded request and publishes it into that request.
     *
     * <p>Publishing and cancellation decide ownership under the request's monitor. When this
     * method returns normally, the waiting caller owns the published shell and takes it with
     * {@link Request#take()}. When cancellation already owns the request, the acquired shell is
     * closed here and {@link RootFailure#ROOT_ACTIVATION_OBSOLETE} is reported instead, so a late
     * shell can never become an unowned root process.</p>
     *
     * @throws RootTransportException when activation itself fails, or when the request was
     *     cancelled while the shell was being acquired
     */
    void acquire(Request request) throws RootTransportException {
        Objects.requireNonNull(request);
        RootShell shell = factory.acquire(timeoutMillis);
        handoffBarrier.arrive(shell);
        RootShellSession session;
        try {
            session = request.capturesTransportIdentity()
                    ? RootShellSession.captureTransportIdentity(shell)
                    : RootShellSession.withoutTransportIdentity(shell);
        } catch (IOException | RuntimeException e) {
            closeQuietly(shell);
            throw new RootTransportException(
                    RootFailure.ROOT_TRANSPORT_FAILED,
                    "Could not capture the acquired root shell identity",
                    e
            );
        }
        if (request.publish(session)) {
            return;
        }
        RuntimeException closingFailure = null;
        try {
            session.close();
        } catch (RuntimeException e) {
            // The shell stays unusable either way; keep the evidence instead of masking the
            // cancelled outcome the caller must see.
            closingFailure = e;
        }
        throw new RootTransportException(
                RootFailure.ROOT_ACTIVATION_OBSOLETE,
                "Root activation completed for a request that was cancelled",
                closingFailure
        );
    }

    /**
     * One caller's activation request and the atomic handoff of the shell it acquires.
     *
     * <p>An acquired shell is published into this request exactly once. Exactly one owner can take
     * it: the waiting caller through {@link #take()}, or cancellation through
     * {@link #cancelAndTake()}. Both claims are decided under one monitor, so a cancellation that
     * races the handoff either takes the published shell and closes it, or finds nothing published
     * and leaves the activation to close the shell it acquires afterwards.</p>
     */
    static final class Request {
        private final boolean captureTransportIdentity;
        private final Object handoff = new Object();
        private boolean cancelled;
        private RootShellSession session;

        Request(boolean captureTransportIdentity) {
            this.captureTransportIdentity = captureTransportIdentity;
        }

        /** Reports whether the activation must capture the acquired shell's kernel identity. */
        boolean capturesTransportIdentity() {
            return captureTransportIdentity;
        }

        /**
         * Publishes one acquired shell for the waiting caller.
         *
         * @return true when the waiting caller now owns the shell; false when cancellation already
         *     owns the request, in which case the activation must close the shell itself
         */
        boolean publish(RootShellSession acquired) {
            synchronized (handoff) {
                if (cancelled) {
                    return false;
                }
                if (session != null) {
                    throw new IllegalStateException(
                            "An activation request can publish only one shell"
                    );
                }
                session = Objects.requireNonNull(acquired);
                return true;
            }
        }

        /** Takes the published session; null when cancellation already owns it. */
        RootShellSession take() {
            synchronized (handoff) {
                RootShellSession taken = session;
                session = null;
                return taken;
            }
        }

        /**
         * Marks this request cancelled and takes the shell cancellation now owns.
         *
         * @return the published shell the caller must close, or null when no shell was published
         *     yet, in which case the activation that publishes later closes its own shell
         */
        RootShellSession cancelAndTake() {
            synchronized (handoff) {
                cancelled = true;
                RootShellSession taken = session;
                session = null;
                return taken;
            }
        }
    }

    private static void closeQuietly(RootShell shell) {
        try {
            shell.close();
        } catch (RuntimeException ignored) {
            // The shell is unusable either way; the identity failure is the reported outcome.
        }
    }
}
