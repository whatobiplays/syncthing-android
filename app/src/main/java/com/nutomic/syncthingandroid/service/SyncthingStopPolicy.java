package com.nutomic.syncthingandroid.service;

/** Selects whether a normal explicit STOP has a service-owned execution to shut down. */
final class SyncthingStopPolicy {
    private SyncthingStopPolicy() { }

    static boolean shouldStopForNormalAction(
            SyncthingService.State state,
            boolean exactExecutionRetained
    ) {
        return state == SyncthingService.State.STARTING
                || state == SyncthingService.State.ACTIVE
                || (state == SyncthingService.State.ERROR && exactExecutionRetained);
    }

    /** Runs the bounded shutdown only when the current state has a stoppable service execution. */
    static boolean stopForNormalAction(
            SyncthingService.State state,
            boolean exactRetainedOwner,
            Runnable shutdown
    ) {
        if (!shouldStopForNormalAction(state, exactRetainedOwner)) return false;
        shutdown.run();
        return true;
    }
}
