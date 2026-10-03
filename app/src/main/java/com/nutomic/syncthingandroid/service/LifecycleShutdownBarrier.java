package com.nutomic.syncthingandroid.service;

/** Coordinates shutdown recovery with process exit and lifecycle-worker termination. */
final class LifecycleShutdownBarrier {
    private LifecycleShutdownBarrier() {
    }

    /**
     * Runs cleanup and the continuation only after execution exit is proven and the exact worker
     * thread has terminated.
     *
     * <p>The service calls this on its main thread so lifecycle handles remain owned by one thread.
     * Cleanup always runs before the continuation, which may admit a replacement launch.</p>
     *
     * @return {@code true} when cleanup and continuation ran; otherwise the caller must retry
     */
    static boolean runWhenReady(
            boolean executionExitProven,
            Thread lifecycleThread,
            Runnable clearWorkerHandles,
            Runnable continuation
    ) {
        if (!executionExitProven || (lifecycleThread != null && lifecycleThread.isAlive())) {
            return false;
        }
        clearWorkerHandles.run();
        continuation.run();
        return true;
    }
}
