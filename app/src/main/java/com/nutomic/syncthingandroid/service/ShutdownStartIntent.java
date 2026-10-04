package com.nutomic.syncthingandroid.service;

/** Tracks one Run Conditions start request deferred by an active shutdown. */
final class ShutdownStartIntent {
    private boolean pending;

    /** Records a true decision only when lifecycle shutdown currently prevents startup. */
    void onRunConditionChanged(boolean shouldRun, boolean shutdownBlocksStartup) {
        if (!shouldRun) {
            pending = false;
        } else if (shutdownBlocksStartup) {
            pending = true;
        }
    }

    /** Cancels a deferred start when an explicit stop or terminal failure takes precedence. */
    void clear() {
        pending = false;
    }

    /**
     * Consumes the request after exit and recovery have completed.
     *
     * <p>A request remains pending until recovery permits launch. Once recovery completes it is
     * consumed even when a continuation already started the service, preventing a later duplicate
     * launch.</p>
     */
    boolean consumeIfRequired(
            boolean shouldRunNow,
            boolean executionExitProven,
            boolean recoveryPermitsLaunch,
            boolean serviceCanStart,
            boolean destroying
    ) {
        if (!pending) return false;
        if (!shouldRunNow || destroying) {
            pending = false;
            return false;
        }
        if (!executionExitProven || !recoveryPermitsLaunch) return false;

        pending = false;
        return serviceCanStart;
    }
}
