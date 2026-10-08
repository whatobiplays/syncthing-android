package com.nutomic.syncthingandroid.service;

import java.util.Objects;

/** Routes restored-certificate recovery through any shutdown already admitted by the service. */
final class CertificateRestoreShutdownContinuation {
    interface ContinuationHandler {
        void accept(Runnable continuation);
    }

    /** Attaches both outcomes to one shutdown already owned by the service. */
    interface ShutdownContinuationHandler {
        void accept(Runnable continuation, Runnable failureContinuation);
    }

    private CertificateRestoreShutdownContinuation() {
    }

    /**
     * Uses the service's existing shutdown when one is active and retains both terminal outcomes.
     * If no shutdown is active, the continuation runs through the service's completion path.
     */
    static void runAfterShutdown(
            boolean shutdownInProgress,
            Runnable continuation,
            Runnable failureContinuation,
            ShutdownContinuationHandler appendToShutdown,
            ContinuationHandler runCompletion
    ) {
        Objects.requireNonNull(continuation);
        Objects.requireNonNull(failureContinuation);
        Objects.requireNonNull(appendToShutdown);
        Objects.requireNonNull(runCompletion);
        if (shutdownInProgress) {
            appendToShutdown.accept(continuation, failureContinuation);
        } else {
            runCompletion.accept(continuation);
        }
    }
}
