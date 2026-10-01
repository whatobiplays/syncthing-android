package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;

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
    public void resetCompletionSkipsContinuationWhenServiceIsDestroyedBeforeDispatch() {
        boolean[] destroying = {false};
        boolean[] continuationRan = {false};
        ArrayDeque<Runnable> serviceThreadQueue = new ArrayDeque<>();

        serviceThreadQueue.add(SyncthingResetPolicy.afterResetUnlessDestroying(
                () -> destroying[0],
                () -> continuationRan[0] = true
        ));
        destroying[0] = true;
        serviceThreadQueue.remove().run();

        assertFalse(continuationRan[0]);
    }

}
