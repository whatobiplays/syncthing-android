package com.nutomic.syncthingandroid.service;

import androidx.annotation.Nullable;

/** Holds the single service-thread reservation for a requested database reset. */
final class DatabaseResetOwnership {
    static final class Operation {
        @Nullable private final Runnable afterReset;
        @Nullable private final Runnable onFailure;

        private Operation(@Nullable Runnable afterReset, @Nullable Runnable onFailure) {
            this.afterReset = afterReset;
            this.onFailure = onFailure;
        }

        @Nullable Runnable afterReset() {
            return afterReset;
        }

        @Nullable Runnable onFailure() {
            return onFailure;
        }
    }

    @Nullable private Operation currentOperation;

    /** Reserves reset ownership before shutdown continuation or worker admission is scheduled. */
    @Nullable Operation reserve(@Nullable Runnable afterReset, @Nullable Runnable onFailure) {
        if (currentOperation != null) return null;
        currentOperation = new Operation(afterReset, onFailure);
        return currentOperation;
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
