package com.nutomic.syncthingandroid.runtime;

/** Reports that a completed one-shot execution lacked durable exact ownership evidence. */
public final class ExecutionIdentityUnavailableException extends RuntimeException {
    public ExecutionIdentityUnavailableException() {
        super("Syncthing exited without durable exact ownership evidence");
    }
}
