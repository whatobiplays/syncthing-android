package com.nutomic.syncthingandroid.runtime;

/** Prevents a bundled launch while an earlier recovery shutdown request may still be delivered. */
public final class RecoveryShutdownRequestPendingException extends IllegalStateException {
    public RecoveryShutdownRequestPendingException() {
        super("A recovery shutdown request has not reached terminal state");
    }
}
