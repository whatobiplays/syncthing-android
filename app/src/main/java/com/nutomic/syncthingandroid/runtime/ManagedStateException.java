package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.Objects;

/**
 * Reports one failed Managed State operation with a stable, backend-independent cause.
 *
 * <p>The exception is a checked {@link IOException} so it travels through the existing
 * mode-neutral storage contracts, such as {@link ConfigStorage}, without those callers having to
 * know anything about the selected execution backend. Callers must treat it as fail-closed: no
 * operation continues with partially transferred state, and no caller substitutes application-UID
 * access for a failed privileged operation.</p>
 *
 * <p>A privileged implementation keeps the backend-specific detail - including a
 * {@link RootTransportException} - as the cause, so the semantic cause reported to callers stays
 * stable while diagnostics keep the original failure.</p>
 */
public final class ManagedStateException extends IOException {
    private final ManagedStateFailure failure;

    public ManagedStateException(ManagedStateFailure failure, String detail) {
        super(detail);
        this.failure = Objects.requireNonNull(failure);
    }

    public ManagedStateException(ManagedStateFailure failure, String detail, Throwable cause) {
        super(detail, cause);
        this.failure = Objects.requireNonNull(failure);
    }

    /** Returns the typed cause of this failure. */
    public ManagedStateFailure failure() {
        return failure;
    }
}
