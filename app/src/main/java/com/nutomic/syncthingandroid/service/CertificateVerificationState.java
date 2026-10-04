package com.nutomic.syncthingandroid.service;

/**
 * Tracks one certificate verification attempt and the lifecycle decisions that may follow it.
 *
 * <p>An explicit stop can finish an in-progress verification as pending startup, or suppress only
 * the automatic restart when a genuine failure is already being recovered. The verification
 * workflow remains active through failure recovery so a later stop can still prevent relaunch.
 * Evidence of a crash or failure always wins over a service state that still reports the previous
 * execution as active, because the state listener may not have processed that failure yet.</p>
 */
final class CertificateVerificationState {
    interface RunCondition {
        boolean shouldRun();
    }

    enum Outcome {
        IGNORED,
        SUCCESS,
        SUCCESS_PENDING_START,
        FAILURE
    }

    private enum Phase {
        NEW,
        VERIFYING,
        FAILURE_RECOVERY,
        FINISHED
    }

    private Phase mPhase = Phase.NEW;
    private boolean mSawStarting;
    private boolean mExplicitlyStopped;
    private boolean mAutomaticContinuationAllowed = true;
    private boolean mPendingStartResolution;

    void beginVerification() {
        if (mPhase != Phase.NEW) {
            throw new IllegalStateException("Certificate verification has already started");
        }
        mPhase = Phase.VERIFYING;
    }

    void onStarting() {
        if (mPhase == Phase.VERIFYING) {
            mSawStarting = true;
        }
    }

    boolean sawStarting() {
        return mSawStarting;
    }

    boolean isVerificationActive() {
        return mPhase == Phase.VERIFYING;
    }

    boolean isWorkflowActive() {
        return mPhase == Phase.VERIFYING || mPhase == Phase.FAILURE_RECOVERY;
    }

    boolean wasExplicitlyStopped() {
        return mExplicitlyStopped;
    }

    boolean automaticContinuationAllowed() {
        return mAutomaticContinuationAllowed;
    }

    /**
     * Applies an explicit stop to the current verification workflow.
     *
     * @param verificationAlreadyActive whether the service currently reports the verification
     *                                  execution as active
     * @param preserveFailureResolution whether crash or failure evidence requires the workflow
     *                                  to stay available for failure recovery instead of being
     *                                  resolved as a success; this takes precedence over
     *                                  {@code verificationAlreadyActive}
     * @return the resolution the caller must dispatch, or {@link Outcome#IGNORED} when the
     *         workflow keeps running so it can resolve the preserved failure
     */
    Outcome onExplicitStop(boolean verificationAlreadyActive,
                           boolean preserveFailureResolution) {
        if (!isWorkflowActive()) {
            return Outcome.IGNORED;
        }

        mExplicitlyStopped = true;
        mAutomaticContinuationAllowed = false;
        if (mPhase == Phase.FAILURE_RECOVERY) {
            return Outcome.IGNORED;
        }
        // Crash evidence takes precedence over a still-visible ACTIVE state: the service may not
        // have processed the execution's failure transition yet when the stop is delivered.
        if (preserveFailureResolution) {
            return Outcome.IGNORED;
        }
        if (verificationAlreadyActive) {
            mPhase = Phase.FINISHED;
            return Outcome.SUCCESS;
        }

        mPhase = Phase.FINISHED;
        mPendingStartResolution = true;
        return Outcome.SUCCESS_PENDING_START;
    }

    Outcome onVerificationResult(boolean success) {
        if (mPhase != Phase.VERIFYING) {
            return Outcome.IGNORED;
        }
        if (success) {
            mPhase = Phase.FINISHED;
            return Outcome.SUCCESS;
        }

        mPhase = Phase.FAILURE_RECOVERY;
        return Outcome.FAILURE;
    }

    void completePendingStart(Runnable discardBackups, Runnable notifyPendingStart) {
        if (!mPendingStartResolution) {
            return;
        }
        mPendingStartResolution = false;
        discardBackups.run();
        notifyPendingStart.run();
    }

    /**
     * Resolves an unfinished verification during service destruction.
     *
     * <p>Destruction restores the pre-change files instead of accepting an unverified replacement
     * or attempting an automatic restart. The phase closes before callbacks run, so queued state or
     * watchdog callbacks cannot resolve or relaunch the workflow a second time.</p>
     *
     * @return true when this call performed the terminal rollback and failure notification
     */
    boolean resolveForDestruction(Runnable rollback, Runnable notifyFailure) {
        if (!isWorkflowActive()) return false;
        mPhase = Phase.FINISHED;
        mPendingStartResolution = false;
        mAutomaticContinuationAllowed = false;
        try {
            rollback.run();
        } finally {
            notifyFailure.run();
        }
        return true;
    }

    /**
     * Creates the post-shutdown recovery action for a genuine verification failure.
     *
     * <p>Restart permission and Run Conditions are checked when the action executes, after the
     * old execution has stopped and any intervening explicit stop has been observed.</p>
     */
    Runnable completeFailureRecovery(Runnable rollback,
                                     RunCondition shouldRunNow,
                                     Runnable relaunch,
                                     Runnable remainStopped,
                                     Runnable notifyFailure) {
        return () -> {
            if (mPhase != Phase.FAILURE_RECOVERY) {
                return;
            }
            try {
                rollback.run();
                if (mAutomaticContinuationAllowed && shouldRunNow.shouldRun()) {
                    relaunch.run();
                } else {
                    remainStopped.run();
                }
                notifyFailure.run();
            } finally {
                mPhase = Phase.FINISHED;
            }
        };
    }

    static void dispatch(Outcome outcome,
                         Runnable onSuccess,
                         Runnable onPendingStart,
                         Runnable onFailure) {
        switch (outcome) {
            case SUCCESS:
                onSuccess.run();
                break;
            case SUCCESS_PENDING_START:
                onPendingStart.run();
                break;
            case FAILURE:
                onFailure.run();
                break;
            case IGNORED:
                break;
            default:
                throw new IllegalArgumentException("Unknown certificate verification outcome");
        }
    }
}
