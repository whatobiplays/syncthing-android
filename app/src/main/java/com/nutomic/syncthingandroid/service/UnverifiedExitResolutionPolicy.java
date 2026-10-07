package com.nutomic.syncthingandroid.service;

/**
 * Decides how the service resolves a lifecycle outcome whose Syncthing exit was never verified.
 *
 * <p>A failed exit verification is not process exit: the launched process may still be alive, so
 * such an outcome never enters the ordinary exit-code policy and never takes the resolution of the
 * execution away from a shutdown that is already resolving it. That shutdown owns a bounded
 * exact-ownership worker which proves or refutes the exit independently of the failed
 * verification, and a post-shutdown recovery check reclassifies whatever remains, including the
 * restart that a stop-and-restart request is completing.</p>
 *
 * <p>The shutdown owns the execution's resolution until its recovery check has reported an
 * outcome. That ownership also covers the interval in which the shutdown has already proven the
 * exit but its recovery check is still waiting for the lifecycle thread to terminate: the gap
 * between the bounded worker finishing and the recovery check starting must not hand the
 * execution back to the ordinary failure handling, because that would strand a restart the
 * shutdown is completing.</p>
 */
final class UnverifiedExitResolutionPolicy {

    /** What the service does with one unverified-exit outcome. */
    enum Resolution {
        /** The outcome proved the exit, and a shutdown already accounts for it. */
        ACCOUNT_PROVEN_EXIT,
        /** A shutdown that owns this execution is still resolving it. */
        AWAIT_SHUTDOWN,
        /** Nothing is resolving this execution, so the service reports the failure. */
        REPORT_FAILURE
    }

    private UnverifiedExitResolutionPolicy() {
    }

    /**
     * Selects the resolution for one unverified-exit outcome.
     *
     * @param shutdownInProgress whether a service shutdown is running
     * @param exitObserved whether the outcome itself proved that the launched process exited
     * @param shutdownResolving whether the shutdown still has a resolution step running that may
     *     settle this execution: its bounded exact-ownership worker or its post-shutdown recovery
     *     check
     * @param shutdownExitProven whether the shutdown has already proven that this execution
     *     exited, so only its deferred post-shutdown recovery check can complete the resolution
     */
    static Resolution resolution(
            boolean shutdownInProgress,
            boolean exitObserved,
            boolean shutdownResolving,
            boolean shutdownExitProven
    ) {
        if (!shutdownInProgress) {
            return Resolution.REPORT_FAILURE;
        }
        if (exitObserved) {
            return Resolution.ACCOUNT_PROVEN_EXIT;
        }
        if (shutdownResolving || shutdownExitProven) {
            return Resolution.AWAIT_SHUTDOWN;
        }
        return Resolution.REPORT_FAILURE;
    }
}
