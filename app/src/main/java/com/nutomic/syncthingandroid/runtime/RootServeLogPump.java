package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.Objects;

/**
 * Streams a running root Syncthing's output into the shared log while the app is alive.
 *
 * <p>Root output is written by the bundled process into an app-owned spool file, because a UID-0
 * process cannot safely create files in the app's private storage. This pump then follows that file
 * for as long as the process runs, handing each new chunk to {@link RootServeLogWriter} so the user
 * sees the log while the app is running and the durable consumption offset stays current for
 * recovery after app death.</p>
 *
 * <p>The pump stops on its own once the launched process has exited and the spool has been drained,
 * so the owning execution can join it and decide whether the drained spool can be deleted or has to
 * stay for a later reconciliation attempt. An execution that can no longer wait for that drain
 * stops the pump through {@link #cancelAndAwaitStopped()}, which waits until the pump thread has
 * really terminated, so a later recovery never reconciles the same run concurrently with it.</p>
 */
final class RootServeLogPump implements Runnable {
    /** Receives the I/O failure that stopped the pump. */
    @FunctionalInterface
    interface FailureReporter {
        void onFailure(IOException error);
    }

    /** How long the pump waits between checks for new output. */
    static final long POLL_MILLIS = 200;

    private final RootServeLogWriter writer;
    private final SpoolTailInputStream.Liveness liveness;
    private final long pollMillis;
    private final FailureReporter failureReporter;
    private final Thread thread;

    private volatile IOException failure;

    /**
     * @param failureReporter receives an I/O failure that stopped the pump, or {@code null} to drop it
     */
    RootServeLogPump(
            RootServeLogWriter writer,
            SpoolTailInputStream.Liveness liveness,
            long pollMillis,
            FailureReporter failureReporter
    ) {
        this.writer = Objects.requireNonNull(writer);
        this.liveness = Objects.requireNonNull(liveness);
        this.pollMillis = pollMillis;
        this.failureReporter = failureReporter;
        this.thread = new Thread(this, "root-serve-log");
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    /**
     * Waits until the launched process exited and its spool was drained.
     *
     * @return the failure that stopped the pump, or {@code null} when it drained cleanly
     */
    IOException awaitCompletion() throws InterruptedException {
        thread.join();
        return failure;
    }

    /**
     * Stops the pump and waits until its thread has actually terminated.
     *
     * <p>The owning execution calls this when it has proven that its process exited but it can no
     * longer wait for the pump to drain the run. The pump is interrupted, and the caller returns
     * only once the pump thread has stopped touching the run spool, so a later recovery that
     * reconciles the same run can never write the shared log concurrently with this pump. A run
     * whose pump was stopped this way keeps its durable consumption offset and its undrained
     * output: stopping the pump does not mean that its output reached the shared log.</p>
     *
     * <p>An interruption of the calling thread does not abort the wait. The pump is still joined
     * until it terminated, the interruption is then re-applied to the calling thread, and this
     * method reports it. A caller therefore never observes a "stopped" pump that is still running,
     * even when it is interrupted while waiting.</p>
     *
     * @throws InterruptedException when the calling thread was interrupted while waiting; the pump
     *     has terminated by then
     */
    void cancelAndAwaitStopped() throws InterruptedException {
        boolean interrupted = false;
        thread.interrupt();
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException stillInterrupted) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("Interrupted while stopping the root serve log pump");
        }
    }

    @Override
    public void run() {
        try {
            while (!liveness.hasExited()) {
                writer.appendPendingOutput();
                Thread.sleep(pollMillis);
            }
            writer.appendPendingOutput();
            writer.trimLog();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException stopped) {
            failure = stopped;
            if (failureReporter != null) {
                failureReporter.onFailure(stopped);
            }
        }
    }
}
