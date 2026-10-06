package com.nutomic.syncthingandroid.runtime;

import java.util.Objects;

/**
 * Reports a typed failure of the superuser (root) execution backend.
 *
 * <p>The exception is unchecked so it can travel through the mode-neutral runtime seam, which only
 * declares checked I/O and interruption failures. Callers must treat it as fail-closed: no process
 * is signaled, no replacement launch is attempted, and no application-UID execution is
 * substituted.</p>
 */
public final class RootTransportException extends IllegalStateException {
    private final RootFailure failure;

    public RootTransportException(RootFailure failure, String detail) {
        super(detail);
        this.failure = Objects.requireNonNull(failure);
    }

    public RootTransportException(RootFailure failure, String detail, Throwable cause) {
        super(detail, cause);
        this.failure = Objects.requireNonNull(failure);
    }

    /** Returns the typed cause that should be reported to callers. */
    public RootFailure failure() {
        return failure;
    }
}
