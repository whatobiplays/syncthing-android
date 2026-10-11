package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.runtime.ManagedStateFailure;
import com.nutomic.syncthingandroid.runtime.RootFailure;

import org.junit.Test;

/** Verifies the exceptional disable path cannot skip execution or admission safety checks. */
public class ExecutionModeTransitionPolicyTest {

    @Test
    public void disableAnywayRequiresFailedRepairAndSafeOldModeQuiescence() {
        ExecutionModeTransitionStatus repairUnavailable = status(true);

        assertTrue(ExecutionModeTransitionPolicy.mayConfirmDisableAnyway(
                repairUnavailable, true, false, true, true
        ));
        assertFalse(ExecutionModeTransitionPolicy.mayConfirmDisableAnyway(
                repairUnavailable, true, false, false, true
        ));
        assertFalse(ExecutionModeTransitionPolicy.mayConfirmDisableAnyway(
                repairUnavailable, true, false, true, false
        ));
        assertFalse(ExecutionModeTransitionPolicy.mayConfirmDisableAnyway(
                repairUnavailable, true, true, true, true
        ));
    }

    @Test
    public void disableAnywayIsUnavailableBeforeARepairFailure() {
        ExecutionModeTransitionStatus repairing = new ExecutionModeTransitionStatus(
                ExecutionModeTransitionStatus.Phase.REPAIRING_STATE,
                ExecutionModeTransitionStatus.FailureKind.NONE,
                null,
                null,
                "Repairing state",
                false,
                false,
                false,
                false
        );

        assertFalse(ExecutionModeTransitionPolicy.mayConfirmDisableAnyway(
                repairing, true, false, true, true
        ));
    }

    @Test
    public void disableAnywayIsLimitedToTerminalRootAuthorizationUnavailability() {
        assertTrue(ExecutionModeTransitionPolicy.mayOfferDisableAnyway(
                ManagedStateFailure.STATE_REPAIR_FAILED, RootFailure.ROOT_DENIED
        ));
        assertTrue(ExecutionModeTransitionPolicy.mayOfferDisableAnyway(
                ManagedStateFailure.STATE_REPAIR_FAILED, RootFailure.ROOT_UNAVAILABLE
        ));
        assertTrue(ExecutionModeTransitionPolicy.mayOfferDisableAnyway(
                ManagedStateFailure.STATE_REPAIR_FAILED, RootFailure.ROOT_AUTHORIZATION_LOST
        ));

        assertFalse(ExecutionModeTransitionPolicy.mayOfferDisableAnyway(
                ManagedStateFailure.STATE_REPAIR_FAILED, RootFailure.ROOT_ACTIVATION_TIMEOUT
        ));
        assertFalse(ExecutionModeTransitionPolicy.mayOfferDisableAnyway(
                ManagedStateFailure.STATE_REPAIR_FAILED, RootFailure.ROOT_TRANSPORT_FAILED
        ));
        assertFalse(ExecutionModeTransitionPolicy.mayOfferDisableAnyway(
                ManagedStateFailure.STATE_TRANSFER_FAILED, RootFailure.ROOT_DENIED
        ));
    }

    @Test
    public void disableAnywayCannotBeConfirmedForTimeoutOrUnrelatedRepairFailure() {
        ExecutionModeTransitionStatus timeout = new ExecutionModeTransitionStatus(
                ExecutionModeTransitionStatus.Phase.WAITING_FOR_DISABLE_CONFIRMATION,
                ExecutionModeTransitionStatus.FailureKind.ROOT,
                RootFailure.ROOT_ACTIVATION_TIMEOUT,
                ManagedStateFailure.STATE_REPAIR_FAILED,
                "Authorization timed out",
                false,
                true,
                true,
                false
        );
        ExecutionModeTransitionStatus corruptState = new ExecutionModeTransitionStatus(
                ExecutionModeTransitionStatus.Phase.WAITING_FOR_DISABLE_CONFIRMATION,
                ExecutionModeTransitionStatus.FailureKind.STATE_REPAIR_UNAVAILABLE,
                null,
                ManagedStateFailure.STATE_TRANSFER_FAILED,
                "Managed State is unsafe",
                false,
                true,
                true,
                false
        );

        assertFalse(ExecutionModeTransitionPolicy.mayConfirmDisableAnyway(
                timeout, true, false, true, true
        ));
        assertFalse(ExecutionModeTransitionPolicy.mayConfirmDisableAnyway(
                corruptState, true, false, true, true
        ));
    }

    @Test
    public void automaticRootAuthorizationRequiresAnEligibleRunDecisionAndRunsOnlyOnce() {
        assertFalse(ExecutionModeTransitionPolicy.mayAttemptRootStartup(
                true, false, false, false
        ));
        assertTrue(ExecutionModeTransitionPolicy.mayAttemptRootStartup(
                true, true, false, false
        ));
        assertFalse(ExecutionModeTransitionPolicy.mayAttemptRootStartup(
                true, true, true, false
        ));
        assertTrue(ExecutionModeTransitionPolicy.mayAttemptRootStartup(
                true, true, true, true
        ));
        assertFalse(ExecutionModeTransitionPolicy.mayAttemptRootStartup(
                false, true, false, true
        ));
    }

    private static ExecutionModeTransitionStatus status(boolean disableAnywayAvailable) {
        return new ExecutionModeTransitionStatus(
                ExecutionModeTransitionStatus.Phase.WAITING_FOR_DISABLE_CONFIRMATION,
                ExecutionModeTransitionStatus.FailureKind.STATE_REPAIR_UNAVAILABLE,
                RootFailure.ROOT_DENIED,
                ManagedStateFailure.STATE_REPAIR_FAILED,
                "Repair unavailable",
                false,
                true,
                disableAnywayAvailable,
                false
        );
    }
}
