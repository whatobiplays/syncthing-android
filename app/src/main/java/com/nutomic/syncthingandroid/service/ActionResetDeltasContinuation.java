package com.nutomic.syncthingandroid.service;

import java.util.Objects;

/** Owns only the deferred RESET_DELTAS launch installed by an explicit reset action. */
final class ActionResetDeltasContinuation {
    private Runnable continuation;
    private final Runnable onCancel;
    private boolean cancelled;
    private boolean completed;

    ActionResetDeltasContinuation(Runnable continuation, Runnable onCancel) {
        this.continuation = Objects.requireNonNull(continuation);
        this.onCancel = Objects.requireNonNull(onCancel);
    }

    /** Cancels a reset launch that has not yet reached its lifecycle launch boundary. */
    boolean cancel() {
        if (cancelled || completed) return false;
        cancelled = true;
        continuation = null;
        onCancel.run();
        return true;
    }

    /** Runs the queued reset at most once unless normal STOP cancelled it first. */
    boolean complete() {
        if (cancelled || completed) return false;
        completed = true;
        Runnable deferred = continuation;
        continuation = null;
        deferred.run();
        return true;
    }
}
