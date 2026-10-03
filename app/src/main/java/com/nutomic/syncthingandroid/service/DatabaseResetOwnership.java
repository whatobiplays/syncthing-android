package com.nutomic.syncthingandroid.service;

import androidx.annotation.Nullable;

/** Holds the single service-thread reservation for a requested database reset. */
final class DatabaseResetOwnership {
    enum ContinuationPolicy {
        /** The callback must finish operation-specific work such as import cleanup. */
        REQUIRED_OPERATION,
        /** The callback only performs an automatic service restart and may be suppressed. */
        AUTOMATIC_STARTUP
    }

    static final class Operation {
        @Nullable private final Runnable afterReset;
        @Nullable private final Runnable onFailure;
        private final ContinuationPolicy continuationPolicy;
        private boolean automaticStartupSuppressed;

        private Operation(@Nullable Runnable afterReset,
                          @Nullable Runnable onFailure,
                          ContinuationPolicy continuationPolicy) {
            this.afterReset = afterReset;
            this.onFailure = onFailure;
            this.continuationPolicy = continuationPolicy;
        }

        @Nullable Runnable afterReset() {
            return afterReset;
        }

        @Nullable Runnable onFailure() {
            return onFailure;
        }

        private void suppressAutomaticStartup() {
            if (continuationPolicy == ContinuationPolicy.AUTOMATIC_STARTUP) {
                automaticStartupSuppressed = true;
            }
        }

        /** Runs required cleanup, or an automatic startup only when STOP has not suppressed it. */
        void runAfterReset() {
            if (afterReset == null) return;
            if (continuationPolicy == ContinuationPolicy.AUTOMATIC_STARTUP
                    && automaticStartupSuppressed) {
                return;
            }
            afterReset.run();
        }
    }

    @Nullable private Operation currentOperation;

    /** Reserves reset ownership before shutdown continuation or worker admission is scheduled. */
    @Nullable Operation reserve(@Nullable Runnable afterReset, @Nullable Runnable onFailure) {
        return reserve(afterReset, onFailure, ContinuationPolicy.REQUIRED_OPERATION);
    }

    @Nullable Operation reserve(
            @Nullable Runnable afterReset,
            @Nullable Runnable onFailure,
            ContinuationPolicy continuationPolicy
    ) {
        if (currentOperation != null) return null;
        currentOperation = new Operation(afterReset, onFailure, continuationPolicy);
        return currentOperation;
    }

    /** Suppresses only the current external reset's optional automatic restart. */
    void suppressAutomaticStartup() {
        if (currentOperation != null) currentOperation.suppressAutomaticStartup();
    }

    boolean isReserved() {
        return currentOperation != null;
    }

    boolean canStartLifecycle() {
        return currentOperation == null;
    }

    @Nullable Operation currentOperation() {
        return currentOperation;
    }

    /** Releases only the operation that completed, making duplicate completions harmless. */
    boolean complete(Operation operation) {
        if (currentOperation != operation) return false;
        currentOperation = null;
        return true;
    }
}
