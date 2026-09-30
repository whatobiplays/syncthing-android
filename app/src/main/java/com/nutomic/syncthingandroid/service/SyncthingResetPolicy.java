package com.nutomic.syncthingandroid.service;

/**
 * Defines the small amount of reset coordination needed by the service while a service-owned
 * Syncthing invocation may still be active.
 */
final class SyncthingResetPolicy {

    @FunctionalInterface
    interface ShouldRun {
        boolean get();
    }

    private SyncthingResetPolicy() {
    }

    /**
     * Returns whether reset must wait for the service-owned invocation to finish shutting down.
     *
     * <p>Every state other than {@link SyncthingService.State#DISABLED} already represents a
     * lifecycle transition in progress or an active service. A disabled service still has to
     * wait when its runnable handle is present because that handle owns runtime admission until
     * its thread exits.</p>
     */
    static boolean shouldWaitForShutdownComplete(
            SyncthingService.State state,
            boolean serviceRunnablePresent
    ) {
        return state != SyncthingService.State.DISABLED || serviceRunnablePresent;
    }

    /**
     * Creates the relaunch action for a reset without freezing the run-conditions decision when
     * reset is requested. The decision is read when the reset completion action is invoked.
     */
    static Runnable relaunchAfterReset(ShouldRun shouldRun, Runnable relaunch) {
        return () -> {
            if (shouldRun.get()) {
                relaunch.run();
            }
        };
    }
}
