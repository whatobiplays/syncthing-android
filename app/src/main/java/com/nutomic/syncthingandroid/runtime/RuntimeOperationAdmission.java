package com.nutomic.syncthingandroid.runtime;

import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

/** Closes semantic backend operations and drains calls already admitted before a mode change. */
final class RuntimeOperationAdmission {
    final class Permit implements AutoCloseable {
        private boolean released;

        private Permit() {
        }

        @Override
        public void close() {
            List<Runnable> drainedCallbacks = null;
            synchronized (lock) {
                if (released) return;
                released = true;
                active--;
                if (active == 0) {
                    if (reopenWhenDrained) {
                        closed = false;
                        reopenWhenDrained = false;
                    }
                    if (!whenDrained.isEmpty()) {
                        drainedCallbacks = new ArrayList<>(whenDrained);
                        whenDrained.clear();
                    }
                    lock.notifyAll();
                }
            }
            if (drainedCallbacks != null) {
                for (Runnable callback : drainedCallbacks) callback.run();
            }
        }
    }

    private final Object lock = new Object();
    private boolean closed;
    private boolean reopenWhenDrained;
    private int active;
    private final List<Runnable> whenDrained = new ArrayList<>();

    Permit tryEnter() {
        synchronized (lock) {
            if (closed) return null;
            active++;
            return new Permit();
        }
    }

    boolean closeAndWait(long timeoutMillis) throws InterruptedException {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        synchronized (lock) {
            closed = true;
            reopenWhenDrained = false;
            long deadline = System.nanoTime() + remainingNanos;
            while (active > 0) {
                remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(lock, remainingNanos);
            }
            return true;
        }
    }

    /** Reopens after pre-commit failure, once any timed-out admitted operation has left. */
    void reopenWhenDrained() {
        synchronized (lock) {
            if (!closed) return;
            if (active == 0) {
                closed = false;
                reopenWhenDrained = false;
            } else {
                reopenWhenDrained = true;
            }
            lock.notifyAll();
        }
    }

    boolean isClosedAndDrained() {
        synchronized (lock) {
            return closed && active == 0;
        }
    }

    boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** Runs after admitted work leaves a transition-closed gate, without polling for drain. */
    void whenClosedAndDrained(Runnable callback) {
        boolean runNow;
        synchronized (lock) {
            if (!closed) {
                throw new IllegalStateException("Backend admission must be closed first");
            }
            runNow = active == 0;
            if (!runNow) whenDrained.add(callback);
        }
        if (runNow) callback.run();
    }

    void open() {
        synchronized (lock) {
            if (!closed) return;
            if (active != 0) {
                throw new IllegalStateException("Backend operations are still admitted");
            }
            closed = false;
            reopenWhenDrained = false;
            lock.notifyAll();
        }
    }
}
