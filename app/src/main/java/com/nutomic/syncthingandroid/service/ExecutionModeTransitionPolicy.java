package com.nutomic.syncthingandroid.service;

import com.nutomic.syncthingandroid.runtime.ManagedStateFailure;
import com.nutomic.syncthingandroid.runtime.RootFailure;

/** Stateless policy checks for the exceptional part of an Execution Mode transition. */
public final class ExecutionModeTransitionPolicy {

    private ExecutionModeTransitionPolicy() {
    }

    /**
     * Allows the confirmed repair exception only after a normal-mode disable repair failed and
     * the old execution and all admitted work have reached safe terminal states.
     */
    public static boolean mayConfirmDisableAnyway(
            ExecutionModeTransitionStatus status,
            boolean changingMode,
            boolean targetUseRoot,
            boolean rootExecutionExitProven,
            boolean admissionsClosed
    ) {
        return status != null
                && changingMode
                && !targetUseRoot
                && rootExecutionExitProven
                && admissionsClosed
                && status.phase()
                == ExecutionModeTransitionStatus.Phase.WAITING_FOR_DISABLE_CONFIRMATION
                && status.disableAnywayAvailable()
                && mayOfferDisableAnyway(
                        status.managedStateFailure(), status.rootFailure()
                );
    }

    /**
     * Allows Disable anyway only when repair cannot be authorized after a terminal denial or loss.
     * Timeouts, transport errors, unsafe state, and partial repair remain retryable failures.
     */
    public static boolean mayOfferDisableAnyway(
            ManagedStateFailure managedStateFailure,
            RootFailure rootFailure
    ) {
        if (managedStateFailure != ManagedStateFailure.STATE_REPAIR_FAILED) return false;
        return rootFailure == RootFailure.ROOT_DENIED
                || rootFailure == RootFailure.ROOT_UNAVAILABLE
                || rootFailure == RootFailure.ROOT_AUTHORIZATION_LOST;
    }

    /**
     * Reports whether this service instance may make one automatic root startup attempt.
     * Passive UI and background work never authorize root. Once Run Conditions permit execution,
     * at most one automatic attempt is made; a later explicit retry may try again.
     */
    public static boolean mayAttemptRootStartup(
            boolean rootModeSelected,
            boolean runConditionsPermit,
            boolean automaticAttemptAlreadyMade,
            boolean explicitRetry
    ) {
        if (!rootModeSelected) return false;
        if (explicitRetry) return runConditionsPermit;
        return runConditionsPermit && !automaticAttemptAlreadyMade;
    }
}
