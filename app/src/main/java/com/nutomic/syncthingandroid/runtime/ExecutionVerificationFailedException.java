package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;

/**
 * Reports that a launched bundled process could not be proven to be the exact execution that was
 * started, so no durable execution identity could be established.
 *
 * <p>The condition is distinct from a transport failure: the launch command was transported, but
 * the resulting process identity did not verify. Callers that own process control must fail closed
 * and must not signal any process on the basis of this failure.</p>
 */
public final class ExecutionVerificationFailedException extends IOException {
    public ExecutionVerificationFailedException(String message) {
        super(message);
    }
}
