package com.nutomic.syncthingandroid.service;

/**
 * Runs one interruption-safe termination wait for service lifecycle cleanup.
 *
 * <p>Lifecycle cleanup must not treat an interruption as termination. Lifecycle handles and
 * runtime admission stay owned until the waited-for execution or thread has really terminated,
 * so an interruption only cancels the current wait attempt. The helper therefore retries the
 * supplied wait, runs the supplied completion work only after the wait succeeded, and restores
 * the calling thread's interrupt flag only after that completion work finished.</p>
 */
final class TerminationWait {

    @FunctionalInterface
    interface Waiter {
        void await() throws InterruptedException;
    }

    /**
     * Waits until the supplied waiter reports termination.
     *
     * @param waiter waits for one termination step, it is retried after an interruption
     * @param afterTermination runs once termination was observed and before the interrupt flag is
     *                         restored, callers without completion work pass a no-op
     * @param onInterrupted runs for every observed interruption while the wait is retried
     */
    static void awaitTermination(
            Waiter waiter,
            Runnable afterTermination,
            Runnable onInterrupted
    ) {
        boolean interrupted = false;
        while (true) {
            try {
                waiter.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
                onInterrupted.run();
            }
        }

        try {
            afterTermination.run();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private TerminationWait() {
    }
}
