package com.nutomic.syncthingandroid.service;

import java.util.Objects;

/** Cleans up the explicit delta-reset continuation when shutdown work is discarded. */
final class ShutdownFailureContinuationCleanup {

    private ShutdownFailureContinuationCleanup() {
    }

    /** Discards the shutdown callback and cancels its queued explicit delta reset. */
    static void discard(
            Runnable discardShutdownContinuation,
            Runnable cancelActionResetDeltasContinuation
    ) {
        Objects.requireNonNull(discardShutdownContinuation).run();
        Objects.requireNonNull(cancelActionResetDeltasContinuation).run();
    }

    /** Cancels a pending delta reset and releases its owner only if it is still current. */
    static void cancelAndReleaseDeltaReset(
            ActionResetDeltasContinuation continuation,
            Runnable releaseIfCurrent
    ) {
        if (continuation == null) return;
        continuation.cancel();
        Objects.requireNonNull(releaseIfCurrent).run();
    }
}
