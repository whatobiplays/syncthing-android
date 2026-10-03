package com.nutomic.syncthingandroid.service;

import java.util.Objects;

/** Owns only the deferred continuation installed by an explicit ACTION_RESTART. */
final class ActionRestartContinuation {
    private Runnable continuation;
    private boolean cancelled;
    private boolean completed;

    ActionRestartContinuation(Runnable continuation) {
        this.continuation = Objects.requireNonNull(continuation);
    }

    /** Suppresses the restart when explicit STOP arrives before shutdown completion. */
    boolean cancel() {
        if (cancelled || completed) return false;
        cancelled = true;
        continuation = null;
        return true;
    }

    /** Runs the restart at most once unless STOP cancelled it first. */
    boolean complete() {
        if (cancelled || completed) return false;
        completed = true;
        Runnable deferred = continuation;
        continuation = null;
        deferred.run();
        return true;
    }
}
