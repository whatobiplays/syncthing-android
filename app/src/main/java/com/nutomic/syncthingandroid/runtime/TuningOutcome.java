package com.nutomic.syncthingandroid.runtime;

import java.util.Objects;

/**
 * Result of one optional privileged tuning operation.
 *
 * <p>Tuning is a best-effort optimization: it never blocks startup, never fails folder work, and
 * never triggers privilege acquisition on its own. Callers therefore read the outcome instead of
 * handling an exception, and report failures through logging.</p>
 */
public final class TuningOutcome {
    /** How a tuning request ended. */
    public enum Status {
        /** The privileged setting was applied and verified. */
        APPLIED,
        /** The selected execution mode or the target process made the request inapplicable. */
        NOT_APPLICABLE,
        /** The setting could not be applied; the reason is in {@link #detail()}. */
        FAILED
    }

    private final Status status;
    private final String detail;

    private TuningOutcome(Status status, String detail) {
        this.status = Objects.requireNonNull(status, "The tuning status is required");
        this.detail = Objects.requireNonNull(detail, "The tuning detail is required");
    }

    public static TuningOutcome applied(String detail) {
        return new TuningOutcome(Status.APPLIED, detail);
    }

    public static TuningOutcome notApplicable(String detail) {
        return new TuningOutcome(Status.NOT_APPLICABLE, detail);
    }

    public static TuningOutcome failed(String detail) {
        return new TuningOutcome(Status.FAILED, detail);
    }

    public Status status() {
        return status;
    }

    /** Returns a human-readable reason or confirmation for logging. */
    public String detail() {
        return detail;
    }
}
