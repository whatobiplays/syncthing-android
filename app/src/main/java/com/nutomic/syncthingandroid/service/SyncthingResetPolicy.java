package com.nutomic.syncthingandroid.service;

/**
 * Defines the small amount of reset coordination needed while a Syncthing lifecycle or
 * stopped-state mutation owns admission.
 */
final class SyncthingResetPolicy {

    @FunctionalInterface
    interface ShouldRun {
        boolean get();
    }

    @FunctionalInterface
    interface IsDestroying {
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
        return shouldWaitForShutdownComplete(state, serviceRunnablePresent, false);
    }

    static boolean shouldWaitForShutdownComplete(
            SyncthingService.State state,
            boolean serviceRunnablePresent,
            boolean shutdownInProgress
    ) {
        return shutdownInProgress
                || state != SyncthingService.State.DISABLED
                || serviceRunnablePresent;
    }

    /** Runs an external reset action only when no stopped-state mutation owns lifecycle admission. */
    static boolean runExternalResetIfUnowned(
            boolean fileMutationOwnsStoppedState,
            boolean postMutationOwnsStartup,
            Runnable resetAction
    ) {
        return runExternalResetIfUnowned(
                fileMutationOwnsStoppedState,
                postMutationOwnsStartup,
                false,
                false,
                resetAction
        );
    }

    /** Runs an external reset only when no mutation, reset, or queued continuation owns state. */
    static boolean runExternalResetIfUnowned(
            boolean fileMutationOwnsStoppedState,
            boolean postMutationOwnsStartup,
            boolean databaseResetOwnsStoppedState,
            boolean shutdownContinuationPending,
            Runnable resetAction
    ) {
        if (fileMutationOwnsStoppedState || postMutationOwnsStartup
                || databaseResetOwnsStoppedState || shutdownContinuationPending) {
            return false;
        }
        resetAction.run();
        return true;
    }

    /** Forces certificate mutations through stopped-state admission while lifecycle work owns it. */
    static boolean certificateMutationRequiresShutdown(
            boolean serviceExecutionPresent,
            boolean postMutationOwnsStartup,
            boolean databaseResetOwnsStoppedState
    ) {
        return serviceExecutionPresent
                || postMutationOwnsStartup
                || databaseResetOwnsStoppedState;
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

    /**
     * Creates a service-thread continuation that rechecks destruction when it is dispatched.
     *
     * <p>The reset worker must not read service lifecycle state. Supplying the state reader here
     * defers that read until the handler runs the returned continuation on the service thread.</p>
     */
    static Runnable afterResetUnlessDestroying(IsDestroying isDestroying, Runnable afterReset) {
        return () -> {
            if (!isDestroying.get()) afterReset.run();
        };
    }
}
