package com.nutomic.syncthingandroid.runtime;

import java.util.concurrent.TimeUnit;

/**
 * Admission gate for sync-completion script dispatch.
 *
 * <p>Script dispatch may overlap a running Syncthing process, so an orderly teardown has to stop
 * new scripts from starting and then wait, within a bound, for the dispatch that may already be
 * running. Entering and closing share one state, so a dispatch either is admitted before the gate
 * closes - and teardown then waits for it - or it observes the closed gate and never starts. There
 * is deliberately no state in which a dispatch starts after teardown decided to stop admitting
 * work.</p>
 *
 * <p>The gate bounds waiting only. A dispatch that does not return inside the caller's wait bound
 * is reported as a timeout to the caller; its own transport deadlines remain responsible for the
 * work itself.</p>
 */
public final class FolderScriptAdmission {
    private final Object lock = new Object();
    private boolean closed;
    private int active;

    /**
     * Enters one dispatch.
     *
     * <p>Every successful call must be paired with {@link #leave()}, normally in a
     * {@code finally} block.</p>
     *
     * @return {@code true} when the caller may dispatch; {@code false} when teardown already
     *     closed admission, in which case the caller must not start any work
     */
    public boolean tryAdmit() {
        synchronized (lock) {
            if (closed) {
                return false;
            }
            active++;
            return true;
        }
    }

    /** Leaves one dispatch that {@link #tryAdmit()} admitted. */
    public void leave() {
        synchronized (lock) {
            active--;
            if (active <= 0) {
                active = 0;
                lock.notifyAll();
            }
        }
    }

    /**
     * Closes admission and waits, within the bound, for admitted dispatches to leave.
     *
     * <p>Closing is idempotent, so a second teardown call simply waits for the remaining work.</p>
     *
     * @return {@code true} when every admitted dispatch left inside the bound, {@code false} when
     *     the bound expired first
     * @throws InterruptedException when the caller is interrupted while waiting; admission stays
     *     closed in that case
     */
    public boolean closeAndWait(long timeoutMillis) throws InterruptedException {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        synchronized (lock) {
            closed = true;
            long deadline = System.nanoTime() + remainingNanos;
            while (active > 0) {
                remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remainingNanos);
            }
            return true;
        }
    }

    /** Reports whether admission is closed. */
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }
}
