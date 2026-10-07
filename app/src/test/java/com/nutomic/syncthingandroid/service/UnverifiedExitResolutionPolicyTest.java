package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UnverifiedExitResolutionPolicyTest {

    @Test
    public void provenExitDuringShutdownIsAccountedByTheShutdown() {
        assertEquals(
                UnverifiedExitResolutionPolicy.Resolution.ACCOUNT_PROVEN_EXIT,
                UnverifiedExitResolutionPolicy.resolution(true, true, true, true)
        );
        assertEquals(
                UnverifiedExitResolutionPolicy.Resolution.ACCOUNT_PROVEN_EXIT,
                UnverifiedExitResolutionPolicy.resolution(true, true, false, false)
        );
    }

    @Test
    public void unprovenExitWaitsWhileShutdownCanStillSettleTheExecution() {
        assertEquals(
                UnverifiedExitResolutionPolicy.Resolution.AWAIT_SHUTDOWN,
                UnverifiedExitResolutionPolicy.resolution(true, false, true, false)
        );
    }

    /**
     * The bounded shutdown worker can prove the exit and stop running while the post-shutdown
     * recovery check is still waiting for the lifecycle thread to terminate. That interval leaves
     * no running resolution step, but the pending recovery check still owns the execution, so an
     * unverified wait arriving inside it must not be reported as a failed restart.
     */
    @Test
    public void pendingShutdownExitProofWaitsForTheDeferredRecoveryCheck() {
        assertEquals(
                UnverifiedExitResolutionPolicy.Resolution.AWAIT_SHUTDOWN,
                UnverifiedExitResolutionPolicy.resolution(true, false, false, true)
        );
    }

    @Test
    public void unprovenExitWithoutAShutdownStepReportsTheFailure() {
        assertEquals(
                UnverifiedExitResolutionPolicy.Resolution.REPORT_FAILURE,
                UnverifiedExitResolutionPolicy.resolution(true, false, false, false)
        );
    }

    @Test
    public void outcomeOutsideAShutdownAlwaysReportsTheFailure() {
        assertEquals(
                UnverifiedExitResolutionPolicy.Resolution.REPORT_FAILURE,
                UnverifiedExitResolutionPolicy.resolution(false, false, false, false)
        );
        assertEquals(
                UnverifiedExitResolutionPolicy.Resolution.REPORT_FAILURE,
                UnverifiedExitResolutionPolicy.resolution(false, true, true, true)
        );
    }
}
