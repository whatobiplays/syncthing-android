package com.nutomic.syncthingandroid.service;

import com.nutomic.syncthingandroid.runtime.ManagedStateFailure;
import com.nutomic.syncthingandroid.runtime.RootFailure;

/** Immutable service-facing progress and failure status for an Execution Mode request. */
public final class ExecutionModeTransitionStatus {
    public enum Phase {
        IDLE,
        PREPARING_ROOT,
        QUIESCING,
        STOPPING,
        REPAIRING_STATE,
        COMMITTING,
        WAITING_FOR_DISABLE_CONFIRMATION,
        FAILED
    }

    public enum FailureKind {
        NONE,
        BUSY,
        ROOT,
        EXECUTION_UNRESOLVED,
        STATE_REPAIR_UNAVAILABLE,
        PREFERENCE_PERSISTENCE,
        STATE_ACCESS_REQUIRED,
        STARTUP
    }

    private final Phase phase;
    private final FailureKind failureKind;
    private final RootFailure rootFailure;
    private final ManagedStateFailure managedStateFailure;
    private final String detail;
    private final boolean requestedUseRoot;
    private final boolean retryAvailable;
    private final boolean disableAnywayAvailable;
    private final boolean stateAccessRepairRequired;

    public ExecutionModeTransitionStatus(
            Phase phase,
            FailureKind failureKind,
            RootFailure rootFailure,
            ManagedStateFailure managedStateFailure,
            String detail,
            boolean requestedUseRoot,
            boolean retryAvailable,
            boolean disableAnywayAvailable,
            boolean stateAccessRepairRequired
    ) {
        this.phase = phase;
        this.failureKind = failureKind;
        this.rootFailure = rootFailure;
        this.managedStateFailure = managedStateFailure;
        this.detail = detail;
        this.requestedUseRoot = requestedUseRoot;
        this.retryAvailable = retryAvailable;
        this.disableAnywayAvailable = disableAnywayAvailable;
        this.stateAccessRepairRequired = stateAccessRepairRequired;
    }

    public static ExecutionModeTransitionStatus idle(boolean stateAccessRepairRequired) {
        return new ExecutionModeTransitionStatus(
                Phase.IDLE,
                FailureKind.NONE,
                null,
                null,
                null,
                false,
                false,
                false,
                stateAccessRepairRequired
        );
    }

    public Phase phase() {
        return phase;
    }

    public FailureKind failureKind() {
        return failureKind;
    }

    public RootFailure rootFailure() {
        return rootFailure;
    }

    public ManagedStateFailure managedStateFailure() {
        return managedStateFailure;
    }

    public String detail() {
        return detail;
    }

    public boolean requestedUseRoot() {
        return requestedUseRoot;
    }

    public boolean retryAvailable() {
        return retryAvailable;
    }

    public boolean disableAnywayAvailable() {
        return disableAnywayAvailable;
    }

    public boolean stateAccessRepairRequired() {
        return stateAccessRepairRequired;
    }

    public boolean busy() {
        return phase != Phase.IDLE && phase != Phase.FAILED;
    }
}
