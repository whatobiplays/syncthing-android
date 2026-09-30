package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SyncthingResetPolicyTest {

    @Test
    public void resetWaitsWhileServiceMayOwnAnInvocation() {
        assertTrue(SyncthingResetPolicy.shouldWaitForShutdownComplete(
                SyncthingService.State.STARTING, false
        ));
        assertTrue(SyncthingResetPolicy.shouldWaitForShutdownComplete(
                SyncthingService.State.ACTIVE, false
        ));
        assertTrue(SyncthingResetPolicy.shouldWaitForShutdownComplete(
                SyncthingService.State.DISABLED, true
        ));
        assertFalse(SyncthingResetPolicy.shouldWaitForShutdownComplete(
                SyncthingService.State.DISABLED, false
        ));
    }

    @Test
    public void deferredRelaunchReadsRunConditionWhenResetCompletes() {
        boolean[] shouldRun = {false};
        boolean[] relaunched = {false};

        Runnable afterReset = SyncthingResetPolicy.relaunchAfterReset(
                () -> shouldRun[0],
                () -> relaunched[0] = true
        );

        shouldRun[0] = true;
        afterReset.run();

        assertTrue(relaunched[0]);
    }

    @Test
    public void shutdownCompletionWaitsThroughInterruptionsAndRestoresAfterCallback() {
        RepeatedlyInterruptedJoin join = new RepeatedlyInterruptedJoin(2);
        boolean[] callbackSawInterrupted = {false};
        boolean[] callbackSawExit = {false};
        boolean interruptedBeforeTest = Thread.currentThread().isInterrupted();
        Thread.interrupted();

        try {
            TerminationWait.awaitTermination(
                    join::join,
                    () -> {
                        callbackSawInterrupted[0] = Thread.currentThread().isInterrupted();
                        callbackSawExit[0] = join.exitKnown;
                    },
                    () -> { }
            );

            assertTrue(join.exitKnown);
            assertEquals(3, join.joinCount);
            assertTrue(callbackSawExit[0]);
            assertFalse(callbackSawInterrupted[0]);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            if (interruptedBeforeTest) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class RepeatedlyInterruptedJoin {
        private int interruptionsRemaining;
        private int joinCount;
        private boolean exitKnown;

        private RepeatedlyInterruptedJoin(int interruptionsRemaining) {
            this.interruptionsRemaining = interruptionsRemaining;
        }

        private void join() throws InterruptedException {
            joinCount++;
            if (interruptionsRemaining > 0) {
                interruptionsRemaining--;
                if (interruptionsRemaining == 0) {
                    exitKnown = true;
                }
                throw new InterruptedException("test interruption");
            }
            if (!exitKnown) {
                throw new AssertionError("shutdown completed before execution exit was known");
            }
        }
    }
}
