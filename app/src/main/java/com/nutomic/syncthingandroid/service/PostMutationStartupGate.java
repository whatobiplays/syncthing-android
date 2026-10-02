package com.nutomic.syncthingandroid.service;

/**
 * Keeps an import-owned lifecycle continuation ahead of deferred Run Conditions startup.
 * Operation ownership remains active until completion, independently of whether STOP permits an
 * automatic restart afterward.
 */
final class PostMutationStartupGate {
    private boolean operationOwnsStartup;
    // STOP suppresses the final restart without releasing reset admission ownership.
    private boolean automaticStartupAllowed = true;

    void beginOperation() {
        beginOperation(true);
    }

    /** Begins import sequencing with the restart permission captured from its mutation owner. */
    void beginOperation(boolean allowAutomaticStartup) {
        operationOwnsStartup = true;
        automaticStartupAllowed = allowAutomaticStartup;
    }

    boolean ownsStartup() {
        return operationOwnsStartup;
    }

    /** Refuses another stopped-state file mutation while an import still owns its reset. */
    boolean rejectNewMutation(Runnable onRejected) {
        if (!operationOwnsStartup) return false;
        onRejected.run();
        return true;
    }

    boolean consumeDeferredStart(
            ShutdownStartIntent startIntent,
            boolean shouldRunNow,
            boolean executionExitProven,
            boolean recoveryPermitsLaunch,
            boolean serviceCanStart,
            boolean destroying
    ) {
        if (operationOwnsStartup) return false;
        return startIntent.consumeIfRequired(
                shouldRunNow,
                executionExitProven,
                recoveryPermitsLaunch,
                serviceCanStart,
                destroying
        );
    }

    /** Finishes the operation with its live Run Conditions decision and consumes any deferred start. */
    void completeOperation(
            ShutdownStartIntent startIntent,
            boolean shouldRunNow,
            Runnable startService
    ) {
        if (!operationOwnsStartup) return;
        operationOwnsStartup = false;
        boolean shouldStart = automaticStartupAllowed && shouldRunNow;
        automaticStartupAllowed = true;
        startIntent.clear();
        if (shouldStart) startService.run();
    }

    /** Suppresses the final start while retaining ownership until import lifecycle work completes. */
    void suppressAutomaticStartup(ShutdownStartIntent startIntent) {
        if (operationOwnsStartup) automaticStartupAllowed = false;
        startIntent.clear();
    }

    /** Releases failed or abandoned import work and any generic start deferred by shutdown. */
    void cancel(ShutdownStartIntent startIntent) {
        operationOwnsStartup = false;
        automaticStartupAllowed = false;
        startIntent.clear();
    }
}
