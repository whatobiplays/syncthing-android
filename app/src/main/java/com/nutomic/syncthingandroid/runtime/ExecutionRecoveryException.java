package com.nutomic.syncthingandroid.runtime;

/** Indicates that recovery could not prove it is safe to start another bundled process. */
public final class ExecutionRecoveryException extends IllegalStateException {
    private final ExecutionOwnershipManager.RecoveryAssessment assessment;

    public ExecutionRecoveryException(ExecutionOwnershipManager.RecoveryAssessment assessment) {
        super("Bundled Syncthing recovery did not authorize a replacement launch: "
                + assessment.classification());
        this.assessment = assessment;
    }

    public ExecutionOwnershipManager.RecoveryAssessment assessment() {
        return assessment;
    }
}
