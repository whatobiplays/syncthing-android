package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Acquires bounded, UID-verified root helper sessions for privileged operations.
 *
 * <p>One session serves exactly one operation: it is acquired through the activation boundary,
 * lent to the operation, and closed when the operation ends, whether it succeeds or fails. The
 * session shells are shared with no other caller, so a cancelled operation loses only its own
 * work and never a session someone else still uses.</p>
 *
 * <p>Acquisition runs on this class's own worker and is bounded, so an unanswered root prompt can
 * never block the calling thread - which may be a service or user-interface thread - and a
 * caller that gives up only abandons its own request.</p>
 */
final class RootHelperSessions implements AutoCloseable {
    /** One privileged operation that runs inside a single helper session. */
    interface SessionOperation<T> {
        T run(RootShell shell) throws IOException;
    }

    private final RootActivation activation;
    private final long activationTimeoutMillis;
    /**
     * Worker one acquisition runs on, so a pending root prompt never blocks a caller's own thread.
     *
     * <p>The pool is cached on purpose: one worker belongs to one activation, so an unanswered root
     * prompt cannot delay an unrelated privileged operation behind it. The number of live workers
     * therefore follows the privileged operations running at the same time rather than the number
     * of requests ever made, because an activation ends when the transport's bounded shell
     * verification expires and the pool reclaims an idle worker after it has parked for a minute.</p>
     */
    private final ExecutorService activationWorker;

    /**
     * Creates the boundary one component uses for its privileged operations.
     *
     * @param shellFactory           transport factory that acquires root shells
     * @param activationTimeoutMillis caller-visible deadline for one acquisition
     */
    RootHelperSessions(RootShellFactory shellFactory, long activationTimeoutMillis) {
        this.activation = new RootActivation(
                Objects.requireNonNull(shellFactory),
                activationTimeoutMillis
        );
        this.activationTimeoutMillis = activationTimeoutMillis;
        this.activationWorker = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "root-helper-activation");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * Runs one operation inside a fresh session and always closes that session afterwards.
     *
     * @throws IOException when the operation fails; transport failures arrive as
     *                     {@link RootTransportException}
     */
    <T> T run(SessionOperation<T> operation) throws IOException {
        Objects.requireNonNull(operation, "The session operation is required");
        RootActivation.Request request = activation.begin();
        RootShellSession session = BoundedRootAcquisition.acquireBounded(
                activation,
                activationWorker,
                activationTimeoutMillis,
                request
        );
        try {
            return operation.run(session.shell());
        } finally {
            BoundedRootAcquisition.closeQuietly(session);
        }
    }

    /** Stops accepting new acquisitions; already running sessions keep their own shells. */
    @Override
    public void close() {
        activationWorker.shutdownNow();
    }
}
