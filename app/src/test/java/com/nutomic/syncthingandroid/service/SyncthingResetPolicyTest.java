package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertTrue(SyncthingResetPolicy.shouldWaitForShutdownComplete(
                SyncthingService.State.DISABLED, false, true
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

    @Test
    public void externalDatabaseResetIsRejectedWhileFileMutationOwnsStoppedState() {
        AtomicInteger resetInvocations = new AtomicInteger();

        boolean accepted = SyncthingResetPolicy.runExternalResetIfUnowned(
                true, false, resetInvocations::incrementAndGet
        );

        assertFalse(accepted);
        assertEquals(0, resetInvocations.get());
    }

    @Test
    public void externalDatabaseResetIsRejectedWhileImportOwnsStartup() {
        AtomicInteger resetInvocations = new AtomicInteger();

        boolean accepted = SyncthingResetPolicy.runExternalResetIfUnowned(
                false, true, resetInvocations::incrementAndGet
        );

        assertFalse(accepted);
        assertEquals(0, resetInvocations.get());
    }

    @Test
    public void rejectedDeltaResetDoesNotLaunchOrLeaveStopAfterState() {
        boolean[] stopAfterDeltaReset = {false};
        AtomicInteger launches = new AtomicInteger();

        boolean accepted = SyncthingResetPolicy.runExternalResetIfUnowned(
                true, false, () -> {
                    stopAfterDeltaReset[0] = true;
                    launches.incrementAndGet();
                }
        );

        assertFalse(accepted);
        assertFalse(stopAfterDeltaReset[0]);
        assertEquals(0, launches.get());
    }

    @Test
    public void externalResetActionsRunAfterMutationOwnershipEnds() {
        AtomicInteger databaseResets = new AtomicInteger();
        AtomicInteger deltaResets = new AtomicInteger();

        assertTrue(SyncthingResetPolicy.runExternalResetIfUnowned(
                false, false, databaseResets::incrementAndGet
        ));
        assertTrue(SyncthingResetPolicy.runExternalResetIfUnowned(
                false, false, deltaResets::incrementAndGet
        ));

        assertEquals(1, databaseResets.get());
        assertEquals(1, deltaResets.get());
    }

    @Test
    public void databaseAndDeltaResetsAreRejectedBehindRestartContinuation() {
        AtomicInteger databaseResets = new AtomicInteger();
        AtomicInteger deltaResets = new AtomicInteger();
        boolean[] stopAfterDeltaReset = {false};

        assertFalse(SyncthingResetPolicy.runExternalResetIfUnowned(
                false, false, false, true, databaseResets::incrementAndGet
        ));
        assertFalse(SyncthingResetPolicy.runExternalResetIfUnowned(
                false, false, false, true, () -> {
                    stopAfterDeltaReset[0] = true;
                    deltaResets.incrementAndGet();
                }
        ));

        assertEquals(0, databaseResets.get());
        assertEquals(0, deltaResets.get());
        assertFalse(stopAfterDeltaReset[0]);
    }

    @Test
    public void shutdownRecoveryWithoutContinuationRejectsExternalDatabaseAndDeltaResets() {
        AtomicInteger databaseResets = new AtomicInteger();
        AtomicInteger deltaResets = new AtomicInteger();
        boolean[] stopAfterDeltaReset = {false};
        boolean shutdownInProgress = true;

        // Shutdown retains replacement authorization even after handles and continuations clear.
        assertFalse(SyncthingResetPolicy.runExternalResetIfUnowned(
                false, false, false, shutdownInProgress,
                databaseResets::incrementAndGet
        ));
        assertFalse(SyncthingResetPolicy.runExternalResetIfUnowned(
                false, false, false, shutdownInProgress,
                () -> {
                    stopAfterDeltaReset[0] = true;
                    deltaResets.incrementAndGet();
                }
        ));

        assertEquals(0, databaseResets.get());
        assertEquals(0, deltaResets.get());
        assertFalse(stopAfterDeltaReset[0]);
    }

    @Test
    public void externalResetIsRejectedWhileDatabaseResetOwnsStoppedState() {
        AtomicInteger resets = new AtomicInteger();

        assertFalse(SyncthingResetPolicy.runExternalResetIfUnowned(
                false, false, true, false, resets::incrementAndGet
        ));

        assertEquals(0, resets.get());
    }

    @Test
    public void certificateMutationUsesStoppedAdmissionDuringImportReset() {
        assertTrue(SyncthingResetPolicy.certificateMutationRequiresShutdown(
                false, true, true
        ));
    }

    @Test
    public void certificateMutationsUseStoppedAdmissionDuringDatabaseReset() {
        assertTrue(SyncthingResetPolicy.certificateMutationRequiresShutdown(
                false, false, true
        ));
    }
}
