package com.nutomic.syncthingandroid.service;

import android.content.SharedPreferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.runtime.ManagedStateException;
import com.nutomic.syncthingandroid.runtime.ManagedStateFailure;
import com.nutomic.syncthingandroid.runtime.RootFailure;
import com.nutomic.syncthingandroid.runtime.RootTransportException;
import com.nutomic.syncthingandroid.runtime.SyncthingCommand;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the service-owned transition sequence with deterministic boundary effects. */
public class SyncthingServiceExecutionModeTransitionTest {

    @Test
    public void enableCommitsOnlyAfterAuthorizationAdmissionDrainAndProvenShutdown() {
        Fixture fixture = new Fixture(false, false, true, SyncthingService.State.ACTIVE, false);

        fixture.reachShutdown(true);

        assertEquals(ExecutionModeTransitionStatus.Phase.STOPPING,
                fixture.service.executionModeTransitionStatusForTest().phase());
        assertFalse(fixture.mode.durableRoot);
        assertFalse(fixture.mode.runtimeRoot);
        fixture.effects.completeShutdown(true);
        fixture.runWorkerAndCallback();

        assertTrue(fixture.mode.durableRoot);
        assertTrue(fixture.mode.runtimeRoot);
        assertBefore(fixture.effects.events, "authorize-root", "scripts-drained");
        assertBefore(fixture.effects.events, "scripts-drained", "backend-drained");
        assertBefore(fixture.effects.events, "backend-drained", "shutdown-requested");
        assertBefore(fixture.effects.events, "shutdown-proven", "commit-root");
        assertBefore(fixture.effects.events, "commit-root", "select-root");
        assertBefore(fixture.effects.events, "select-root", "open-admission");
        assertBefore(fixture.effects.events, "open-admission", "launch-SERVE");
        assertFalse(FileMutationBarrier.executionModeTransitionPending());
    }

    @Test
    public void explicitDisableAcquiresRootOnceAfterProvenExitBeforeCommittingNormalMode() {
        Fixture fixture = new Fixture(true, true, true, SyncthingService.State.ACTIVE, false);

        fixture.reachShutdown(false);
        fixture.effects.completeShutdown(true);
        fixture.runAllWorkersAndCallbacks();

        assertFalse(fixture.mode.durableRoot);
        assertFalse(fixture.mode.runtimeRoot);
        assertBefore(fixture.effects.events, "shutdown-proven", "repair-explicit");
        assertBefore(fixture.effects.events, "repair-explicit", "state-access-verified");
        assertBefore(fixture.effects.events, "state-access-verified", "commit-normal");
        assertBefore(fixture.effects.events, "commit-normal", "select-normal");
        assertBefore(fixture.effects.events, "select-normal", "launch-SERVE");
        assertEquals("the explicit repair owns one prompt-capable operation", 0,
                fixture.effects.count("authorize-root"));
    }

    @Test
    public void successfulDisableClearsRootScriptConsentBeforeNormalModeCommit()
            throws Exception {
        Fixture fixture = new Fixture(true, true, true, SyncthingService.State.ACTIVE, false);
        AtomicReference<String> consent = installRootScriptConsent(fixture, true);

        fixture.reachShutdown(false);
        fixture.effects.completeShutdown(true);
        fixture.runAllWorkersAndCallbacks();

        assertFalse(fixture.mode.durableRoot);
        assertEquals("OFF", consent.get());
        assertBefore(fixture.effects.events, "reset-script-consent", "commit-normal");
    }

    @Test
    public void disableAnywayAlsoClearsConsentAndFailedResetPreventsModeCommit()
            throws Exception {
        Fixture exceptional = new Fixture(true, true, true,
                SyncthingService.State.ACTIVE, false);
        AtomicReference<String> cleared = installRootScriptConsent(exceptional, true);
        exceptional.effects.repairFailure = unavailableRepair();
        exceptional.reachShutdown(false);
        exceptional.effects.completeShutdown(true);
        exceptional.runWorkerAndCallback();
        exceptional.service.confirmDisableAnyway();
        exceptional.runCallback();
        exceptional.runAllWorkersAndCallbacks();
        assertFalse(exceptional.mode.durableRoot);
        assertEquals("OFF", cleared.get());
        assertBefore(exceptional.effects.events, "reset-script-consent", "commit-normal");

        Fixture failedReset = new Fixture(true, true, true,
                SyncthingService.State.ACTIVE, false);
        AtomicReference<String> retained = installRootScriptConsent(failedReset, false);
        failedReset.reachShutdown(false);
        failedReset.effects.completeShutdown(true);
        failedReset.runAllWorkersAndCallbacks();
        assertTrue(failedReset.mode.durableRoot);
        assertEquals("ARMED", retained.get());
        assertFalse(failedReset.effects.events.contains("commit-normal"));
    }

    @Test
    public void rootDenialLeavesHealthyNormalExecutionUntouched() {
        Fixture fixture = new Fixture(false, false, true, SyncthingService.State.ACTIVE, false);
        fixture.effects.authorizationFailure = RootFailure.ROOT_DENIED;

        fixture.service.requestExecutionModeTransition(true);
        fixture.runCallback();
        fixture.runWorkerAndCallback();

        ExecutionModeTransitionStatus status =
                fixture.service.executionModeTransitionStatusForTest();
        assertEquals(ExecutionModeTransitionStatus.FailureKind.ROOT, status.failureKind());
        assertEquals(RootFailure.ROOT_DENIED, status.rootFailure());
        assertTrue(status.retryAvailable());
        assertEquals(SyncthingService.State.ACTIVE, fixture.service.getCurrentState());
        assertFalse(fixture.mode.durableRoot);
        assertFalse(fixture.mode.runtimeRoot);
        assertFalse(fixture.effects.events.contains("scripts-drained"));
        assertFalse(fixture.effects.events.contains("commit-root"));

        fixture.effects.authorizationFailure = null;
        fixture.service.retryExecutionModeTransition();
        fixture.runCallback();
        fixture.runWorkerAndCallback(); // explicit retry authorization
        fixture.runWorkerAndCallback(); // admission drain
        fixture.effects.completeShutdown(true);
        fixture.runAllWorkersAndCallbacks();
        assertTrue(fixture.mode.durableRoot);
        assertTrue(fixture.mode.runtimeRoot);
    }

    @Test
    public void failedScriptOrMutationDrainReopensOldAdmissionWithoutCommitting() {
        for (boolean scriptsDrained : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(false, false, false,
                    SyncthingService.State.DISABLED, false);
            fixture.effects.scriptsDrained = scriptsDrained;
            fixture.effects.backendDrained = !scriptsDrained;

            fixture.service.requestExecutionModeTransition(true);
            fixture.runCallback();
            fixture.runWorkerAndCallback();
            fixture.runWorkerAndCallback();

            assertEquals(ExecutionModeTransitionStatus.FailureKind.BUSY,
                    fixture.service.executionModeTransitionStatusForTest().failureKind());
            assertFalse(fixture.mode.durableRoot);
            assertFalse(fixture.effects.events.contains("shutdown-requested"));
            assertTrue(fixture.effects.events.contains("reopen-after-drain"));
            assertFalse(FileMutationBarrier.executionModeTransitionPending());
        }
    }

    @Test
    public void unprovenShutdownAndFailedPreferenceCommitBothFailClosed() {
        Fixture unresolved = new Fixture(false, false, true,
                SyncthingService.State.ACTIVE, false);
        unresolved.reachShutdown(true);
        unresolved.effects.completeShutdown(false);
        assertEquals(ExecutionModeTransitionStatus.FailureKind.EXECUTION_UNRESOLVED,
                unresolved.service.executionModeTransitionStatusForTest().failureKind());
        assertFalse(unresolved.mode.durableRoot);
        assertFalse(unresolved.mode.runtimeRoot);
        assertFalse(unresolved.effects.events.contains("commit-root"));
        assertTrue(unresolved.effects.events.contains("reopen-after-drain"));

        Fixture failedCommit = new Fixture(false, false, true,
                SyncthingService.State.DISABLED, false);
        failedCommit.effects.commitSucceeds = false;
        failedCommit.reachShutdown(true);
        failedCommit.effects.completeShutdown(true);
        failedCommit.runWorkerAndCallback();

        assertEquals(ExecutionModeTransitionStatus.FailureKind.PREFERENCE_PERSISTENCE,
                failedCommit.service.executionModeTransitionStatusForTest().failureKind());
        assertFalse(failedCommit.mode.durableRoot);
        assertFalse(failedCommit.mode.runtimeRoot);
        assertFalse(failedCommit.effects.events.contains("select-root"));
        assertTrue(failedCommit.effects.events.contains("reopen-after-drain"));
    }

    @Test
    public void partialStateRepairFailureCannotOfferDisableAnywayAndCanBeRetried() {
        Fixture fixture = new Fixture(true, true, true,
                SyncthingService.State.ACTIVE, false);
        fixture.effects.repairFailure = new ManagedStateException(
                ManagedStateFailure.STATE_TRANSFER_FAILED,
                "Managed State transfer was incomplete"
        );

        fixture.reachShutdown(false);
        fixture.effects.completeShutdown(true);
        fixture.runWorkerAndCallback();

        ExecutionModeTransitionStatus status =
                fixture.service.executionModeTransitionStatusForTest();
        assertEquals(ExecutionModeTransitionStatus.Phase.FAILED, status.phase());
        assertEquals(ManagedStateFailure.STATE_TRANSFER_FAILED, status.managedStateFailure());
        assertFalse(status.disableAnywayAvailable());
        assertTrue(fixture.mode.durableRoot);
        assertTrue(fixture.mode.runtimeRoot);
        assertFalse(fixture.effects.events.contains("commit-normal"));

        fixture.effects.repairFailure = null;
        fixture.service.retryExecutionModeTransition();
        fixture.runCallback();
        fixture.runWorkerAndCallback(); // explicit authorization and repair in one operation
        fixture.runAllWorkersAndCallbacks();

        assertFalse(fixture.mode.durableRoot);
        assertFalse(fixture.mode.runtimeRoot);
        assertTrue(fixture.effects.events.contains("commit-normal"));
        assertTrue(fixture.effects.events.contains("repair-explicit"));
        assertBefore(fixture.effects.events, "repair-explicit", "commit-normal");
        assertEquals(0, fixture.effects.count("authorize-root"));
    }

    @Test
    public void disableAnywayRequiresUnavailableRepairAndKeepsLaunchSuppressedUntilRepair() {
        Fixture fixture = new Fixture(true, true, true, SyncthingService.State.ACTIVE, false);
        fixture.effects.repairFailure = unavailableRepair();
        fixture.effects.stateAccessible = false;

        fixture.reachShutdown(false);
        fixture.effects.completeShutdown(true);
        fixture.runWorkerAndCallback();

        ExecutionModeTransitionStatus waiting =
                fixture.service.executionModeTransitionStatusForTest();
        assertEquals(ExecutionModeTransitionStatus.Phase.WAITING_FOR_DISABLE_CONFIRMATION,
                waiting.phase());
        assertTrue(waiting.disableAnywayAvailable());
        assertTrue(fixture.mode.durableRoot);

        fixture.service.confirmDisableAnyway();
        fixture.runCallback();
        fixture.runWorkerAndCallback();

        assertFalse(fixture.mode.durableRoot);
        assertFalse(fixture.mode.runtimeRoot);
        assertTrue(fixture.service.executionModeTransitionStatusForTest()
                .stateAccessRepairRequired());
        assertFalse(fixture.effects.events.contains("launch-SERVE"));

        fixture.effects.repairFailure = null;
        fixture.effects.stateAccessible = true;
        fixture.service.requestStateAccessRepair();
        fixture.runCallback();
        fixture.runWorkerAndCallback(); // explicit root preflight
        fixture.runWorkerAndCallback(); // admission drain
        fixture.effects.completeShutdown(true);
        fixture.runWorkerAndCallback(); // explicit repair

        assertTrue(fixture.effects.events.contains("repair-explicit"));
        assertFalse(fixture.service.executionModeTransitionStatusForTest()
                .stateAccessRepairRequired());
        assertTrue(fixture.effects.events.contains("launch-SERVE"));
    }

    @Test
    public void sharedPreferenceListenerKeepsDisableAnywayRepairRequiredAfterRelease() {
        Fixture fixture = new Fixture(true, true, true, SyncthingService.State.ACTIVE, false);
        SharedPreferences preferences = recordingPreferences(true);
        fixture.effects.preferences = preferences;
        fixture.service.registerExecutionModePreferenceListenerForTest(preferences);
        fixture.effects.repairFailure = unavailableRepair();
        fixture.effects.stateAccessible = false;

        fixture.reachShutdown(false);
        fixture.effects.completeShutdown(true);
        fixture.runWorkerAndCallback();
        assertTrue(fixture.service.executionModeTransitionStatusForTest()
                .disableAnywayAvailable());

        fixture.effects.events.clear();
        fixture.service.confirmDisableAnyway();
        fixture.runCallback();
        fixture.runWorker(); // SharedPreferences notifies while transition admission is held.
        assertTrue(FileMutationBarrier.executionModeTransitionPending());
        fixture.runCallback(); // Preference listener observes the pending reservation.
        assertTrue(FileMutationBarrier.executionModeTransitionPending());
        fixture.runCallback(); // Commit callback selects Normal Mode and releases the reservation.

        assertFalse(fixture.mode.durableRoot);
        assertFalse(fixture.mode.runtimeRoot);
        assertTrue(fixture.service.isStateAccessRepairRequired());
        assertTrue(fixture.effects.hasCallback()); // Listener continuation runs after release.
        fixture.runCallback();

        assertTrue(fixture.service.isStateAccessRepairRequired());
        assertFalse(fixture.effects.events.contains("open-admission"));
        assertFalse(fixture.effects.events.contains("launch-SERVE"));
        assertFalse(FileMutationBarrier.executionModeTransitionPending());
    }

    @Test
    public void startupConfigurationTimeoutRetainsAdmissionUntilWorkerSettlesThenAllowsRetry()
            throws Exception {
        Fixture fixture = new Fixture(true, true, true,
                SyncthingService.State.DISABLED, false);
        fixture.service.registerExecutionModePreferenceListenerForTest(
                recordingPreferences(true)
        );

        fixture.service.startRootStartupConfigurationLoadForTest();
        assertEquals(1, fixture.effects.startupConfigurationLoadCount);
        assertNotNull(fixture.effects.startupConfigurationTimeout);
        fixture.effects.startupConfigurationTimeout.run();

        ExecutionModeTransitionStatus timedOut =
                fixture.service.executionModeTransitionStatusForTest();
        assertEquals(ExecutionModeTransitionStatus.Phase.QUIESCING, timedOut.phase());
        assertFalse(timedOut.retryAvailable());
        assertTrue(FileMutationBarrier.tryReserveExecutionModeTransition() == null);

        fixture.service.retryExecutionModeTransition();
        fixture.runCallback();
        assertEquals(1, fixture.effects.startupConfigurationLoadCount);

        fixture.effects.completeStartupConfiguration(
                StartupConfigurationLoad.Result.failure(new IOException("settled failure"))
        );
        FileMutationBarrier.ExecutionModeTransitionReservation afterSettle =
                FileMutationBarrier.tryReserveExecutionModeTransition();
        assertNotNull(afterSettle);
        afterSettle.release();
        fixture.runCallback();

        ExecutionModeTransitionStatus settled =
                fixture.service.executionModeTransitionStatusForTest();
        assertEquals(ExecutionModeTransitionStatus.Phase.FAILED, settled.phase());
        assertTrue(settled.retryAvailable());

        fixture.service.retryExecutionModeTransition();
        fixture.runCallback();
        fixture.runWorkerAndCallback();
        assertEquals(2, fixture.effects.startupConfigurationLoadCount);
        fixture.effects.completeStartupConfiguration(
                StartupConfigurationLoad.Result.failure(new IOException("retry settled"))
        );
        fixture.runCallback();
    }

    @Test
    public void runConditionCycleDuringConfigurationReadStartsOnlyAfterOldWorkerSettles()
            throws Exception {
        Fixture fixture = new Fixture(true, true, true,
                SyncthingService.State.DISABLED, false);
        fixture.service.startRootStartupConfigurationLoadForTest();
        assertEquals(1, fixture.effects.startupConfigurationLoadCount);
        setServiceField(fixture.service, "mRootStartupAuthorizationAttempted", true);
        setServiceField(fixture.service, "mRootStartupAuthorizationComplete", true);
        Method stopped = SyncthingService.class.getDeclaredMethod(
                "invalidateRootStartupForStoppedRunConditions");
        stopped.setAccessible(true);
        stopped.invoke(fixture.service);
        setServiceField(fixture.service, "mLastDeterminedShouldRun", false);
        setServiceField(fixture.service, "mLastDeterminedShouldRun", true);
        assertEquals("the old configuration worker still owns storage", 1,
                fixture.effects.startupConfigurationLoadCount);

        fixture.effects.completeStartupConfiguration(
                StartupConfigurationLoad.Result.failure(new IOException("obsolete result")));
        fixture.runCallback();
        fixture.runWorkerAndCallback();
        assertEquals("a fresh eligible startup authorizes after old storage settles", 1,
                fixture.effects.count("authorize-root"));
        assertEquals(2, fixture.effects.startupConfigurationLoadCount);
        fixture.effects.completeStartupConfiguration(
                StartupConfigurationLoad.Result.failure(new IOException("new result")));
        fixture.runCallback();
    }

    @Test
    public void injectedNormalStartupAccessVerdictStillControlsRepairGate()
            throws Exception {
        Fixture fixture = new Fixture(false, false, true,
                SyncthingService.State.DISABLED, false);
        Method probe = SyncthingService.class.getDeclaredMethod(
                "ensureStartupStateAccessProbe");
        probe.setAccessible(true);
        fixture.effects.stateAccessible = false;
        probe.invoke(fixture.service);
        assertTrue(fixture.service.isStateAccessRepairRequired());

        Method invalidate = SyncthingService.class.getDeclaredMethod(
                "invalidateStartupStateAccessProbe");
        invalidate.setAccessible(true);
        invalidate.invoke(fixture.service);
        fixture.effects.stateAccessible = true;
        probe.invoke(fixture.service);
        assertFalse(fixture.service.isStateAccessRepairRequired());
    }

    @Test
    public void failedPostCommitStartupRemainsRetryableAndTyped() {
        Fixture fixture = new Fixture(false, false, true, SyncthingService.State.ACTIVE, false);

        fixture.reachShutdown(true);
        fixture.effects.completeShutdown(true);
        fixture.runWorkerAndCallback();
        assertTrue(fixture.mode.durableRoot);
        assertTrue(fixture.mode.runtimeRoot);
        assertEquals(ExecutionModeTransitionStatus.Phase.PREPARING_ROOT,
                fixture.service.executionModeTransitionStatusForTest().phase());

        fixture.service.onServiceStateChangeForTest(SyncthingService.State.ERROR);

        ExecutionModeTransitionStatus status =
                fixture.service.executionModeTransitionStatusForTest();
        assertEquals(ExecutionModeTransitionStatus.FailureKind.STARTUP, status.failureKind());
        assertTrue(status.retryAvailable());
        assertTrue(fixture.mode.durableRoot);
        assertTrue(fixture.mode.runtimeRoot);

        int authorizationAttempts = fixture.effects.count("authorize-root");
        fixture.service.retryExecutionModeTransition();
        fixture.runCallback();
        fixture.effects.completeShutdown(false);
        fixture.runCallback();
        assertEquals(ExecutionModeTransitionStatus.FailureKind.EXECUTION_UNRESOLVED,
                fixture.service.executionModeTransitionStatusForTest().failureKind());
        assertEquals("an unproven old execution cannot reach root authorization",
                authorizationAttempts, fixture.effects.count("authorize-root"));

        fixture.effects.authorizationFailure = null;
        fixture.service.retryExecutionModeTransition();
        fixture.runCallback();
        assertEquals("Retry must prove the failed root execution stopped", 1,
                fixture.effects.shutdownCallbacks.size());
        fixture.effects.completeShutdown(true);
        fixture.runCallback();
        fixture.runWorkerAndCallback();
        assertEquals("Retry resumes root startup", 1,
                fixture.effects.startupConfigurationLoadCount);
        assertEquals("Retry does not recommit the selected mode", 1,
                fixture.effects.count("commit-root"));
        fixture.effects.completeStartupConfiguration(
                StartupConfigurationLoad.Result.failure(new IOException("settled retry"))
        );
        fixture.runCallback();
    }

    @Test
    public void committedRootAuthorizationFailureUsesServiceRetryAsStartupRetry() {
        Fixture fixture = new Fixture(true, true, true,
                SyncthingService.State.DISABLED, false);
        fixture.effects.authorizationFailure = RootFailure.ROOT_DENIED;

        fixture.service.startRootSelectedStartupForTest();
        fixture.runWorkerAndCallback();
        assertEquals(ExecutionModeTransitionStatus.FailureKind.ROOT,
                fixture.service.executionModeTransitionStatusForTest().failureKind());
        assertTrue(fixture.mode.durableRoot);

        fixture.effects.authorizationFailure = null;
        fixture.service.retryExecutionModeTransition();
        fixture.runCallback();
        fixture.runWorkerAndCallback();

        assertEquals("committed-mode Retry starts one new authorization attempt", 2,
                fixture.effects.count("authorize-root"));
        assertEquals(1, fixture.effects.startupConfigurationLoadCount);
        assertTrue(fixture.mode.durableRoot);
        assertEquals("startup Retry does not begin a mode transition", 0,
                fixture.effects.count("commit-root"));
        fixture.effects.completeStartupConfiguration(
                StartupConfigurationLoad.Result.failure(new IOException("settled retry"))
        );
        fixture.runCallback();
    }

    @Test
    public void rootStartupRetryClearsStaleActionWhenRunConditionsRequireStopped() {
        Fixture fixture = new Fixture(true, true, true,
                SyncthingService.State.DISABLED, false);
        fixture.effects.authorizationFailure = RootFailure.ROOT_DENIED;

        fixture.service.startRootSelectedStartupForTest();
        fixture.runWorkerAndCallback();
        assertTrue(fixture.service.executionModeTransitionStatusForTest().retryAvailable());

        fixture.service.setExecutionModeTransitionTestState(
                false, false, SyncthingService.State.DISABLED
        );
        int authorizationsBeforeRetry = fixture.effects.count("authorize-root");
        fixture.service.retryExecutionModeTransition();
        fixture.runCallback();

        assertEquals(ExecutionModeTransitionStatus.Phase.IDLE,
                fixture.service.executionModeTransitionStatusForTest().phase());
        assertEquals(authorizationsBeforeRetry, fixture.effects.count("authorize-root"));
        assertEquals(0, fixture.effects.startupConfigurationLoadCount);
        assertTrue(fixture.mode.durableRoot);
        assertTrue(fixture.mode.runtimeRoot);
    }

    @Test
    public void retryReconcilesBackendSelectionAfterModeWasAlreadyCommitted() {
        Fixture fixture = new Fixture(false, false, true,
                SyncthingService.State.DISABLED, false);
        fixture.effects.failNextSelection = true;

        fixture.reachShutdown(true);
        fixture.effects.completeShutdown(true);
        fixture.runAllWorkersAndCallbacks();

        assertTrue(fixture.mode.durableRoot);
        assertFalse(fixture.mode.runtimeRoot);
        assertEquals(ExecutionModeTransitionStatus.FailureKind.STARTUP,
                fixture.service.executionModeTransitionStatusForTest().failureKind());
        assertTrue(fixture.service.executionModeTransitionStatusForTest().retryAvailable());

        fixture.service.retryExecutionModeTransition();
        fixture.runCallback();

        assertTrue(fixture.mode.runtimeRoot);
        assertEquals("the committed mode is not written twice", 1,
                fixture.effects.count("commit-root"));
        assertEquals(ExecutionModeTransitionStatus.Phase.PREPARING_ROOT,
                fixture.service.executionModeTransitionStatusForTest().phase());
        if (fixture.effects.startupConfigurationCallback != null) {
            fixture.effects.completeStartupConfiguration(
                    StartupConfigurationLoad.Result.failure(new IOException("settled startup"))
            );
            fixture.runCallback();
        }
    }

    @Test
    public void concurrentLifecycleRequestsCannotAdmitTwoTransitions() {
        Fixture fixture = new Fixture(false, false, false,
                SyncthingService.State.DISABLED, false);
        fixture.service.requestExecutionModeTransition(true);
        fixture.service.requestExecutionModeTransition(false);

        fixture.runCallback();
        fixture.runCallback();
        assertEquals(ExecutionModeTransitionStatus.FailureKind.BUSY,
                fixture.service.executionModeTransitionStatusForTest().failureKind());
        assertTrue(fixture.effects.hasWorker());
        fixture.runWorkerAndCallback();
        assertEquals(1, fixture.effects.count("authorize-root"));
        fixture.runWorkerAndCallback();
        fixture.effects.completeShutdown(true);
        fixture.runWorkerAndCallback();

        assertEquals(1, fixture.effects.count("commit-root"));
        assertEquals(0, fixture.effects.count("commit-normal"));
        assertTrue(fixture.mode.runtimeRoot);
        assertFalse(fixture.effects.events.contains("launch-SERVE"));
        assertEquals(ExecutionModeTransitionStatus.Phase.IDLE,
                fixture.service.executionModeTransitionStatusForTest().phase());
    }

    @Test
    public void destructionDuringAuthorizationDropsObsoleteCallback() {
        Fixture fixture = new Fixture(false, false, true,
                SyncthingService.State.DISABLED, false);
        fixture.service.requestExecutionModeTransition(true);
        fixture.runCallback();

        fixture.service.destroyExecutionModeLifecycleForTest();
        fixture.effects.completeShutdown(true);
        fixture.runWorkerAndCallback();

        assertFalse(fixture.mode.durableRoot);
        assertFalse(fixture.mode.runtimeRoot);
        assertFalse(fixture.effects.events.contains("scripts-drained"));
    }

    @Test
    public void destructionDuringAdmissionDrainRetainsReservationUntilWorkSettles() {
        Fixture old = new Fixture(false, false, true,
                SyncthingService.State.DISABLED, false);
        old.service.requestExecutionModeTransition(true);
        old.runCallback();
        old.runWorker();
        old.runCallback();
        assertTrue(FileMutationBarrier.executionModeTransitionPending());

        old.service.destroyExecutionModeLifecycleForTest();
        old.effects.completeShutdown(true);
        assertTrue(FileMutationBarrier.executionModeTransitionPending());
        old.runWorkerAndCallback();

        assertFalse(FileMutationBarrier.executionModeTransitionPending());
        assertFalse(old.mode.durableRoot);
        assertFalse(old.mode.runtimeRoot);
    }

    @Test
    public void recreatedServiceWaitsForOldReservationThenReconcilesCommittedMode() {
        SharedMode mode = new SharedMode(false, false);
        Fixture old = new Fixture(mode, true, SyncthingService.State.DISABLED, false);
        old.reachShutdown(true);
        old.effects.completeShutdown(true);
        old.runWorker(); // preference is durable while the old service still owns admission
        assertTrue(mode.durableRoot);
        assertFalse(mode.runtimeRoot);
        assertTrue(FileMutationBarrier.executionModeTransitionPending());

        Fixture recreated = new Fixture(mode, true, SyncthingService.State.DISABLED, false);
        recreated.service.reconcileRuntimeExecutionModeForTest();
        assertFalse(recreated.effects.hasWorker());
        assertTrue(FileMutationBarrier.executionModeTransitionPending());

        old.service.destroyExecutionModeLifecycleForTest();
        old.effects.completeShutdown(true);
        recreated.runCallback(); // released old reservation re-enters production reconciliation
        recreated.runWorkerAndCallback();

        assertTrue(mode.runtimeRoot);
        assertBefore(recreated.effects.events, "select-root", "launch-SERVE");
        assertFalse(FileMutationBarrier.executionModeTransitionPending());
    }

    @Test
    public void destructionWhileShutdownIsPendingCannotCommit() {
        Fixture fixture = new Fixture(false, false, true,
                SyncthingService.State.ACTIVE, false);
        fixture.reachShutdown(true);
        assertEquals(1, fixture.effects.shutdownCallbacks.size());

        fixture.service.destroyExecutionModeLifecycleForTest();
        fixture.effects.completeShutdown(true); // obsolete transition shutdown
        fixture.effects.completeShutdown(true); // service destruction shutdown
        fixture.runAllWorkersAndCallbacks();

        assertFalse(fixture.mode.durableRoot);
        assertFalse(fixture.mode.runtimeRoot);
        assertFalse(fixture.effects.events.contains("commit-root"));
    }

    @Test
    public void destructionRecoveryCompletionReleasesTheOldTransitionReservation()
            throws Exception {
        Fixture fixture = new Fixture(false, false, true,
                SyncthingService.State.ACTIVE, false);
        fixture.reachShutdown(true);
        assertTrue(FileMutationBarrier.executionModeTransitionPending());

        Method destroy = SyncthingService.class.getDeclaredMethod("markServiceDestroying");
        destroy.setAccessible(true);
        Object abandoned = destroy.invoke(fixture.service);
        setServiceField(fixture.service, "mDestructionAbandonedTransition", abandoned);
        setServiceField(fixture.service, "mDestructionShutdownCompletionPending", true);
        setServiceField(fixture.service, "mShutdownInProgress", true);
        setServiceField(fixture.service, "mShutdownExitProven", true);
        Method recovered = SyncthingService.class.getDeclaredMethod(
                "onShutdownRecoveryChecked", boolean.class,
                com.nutomic.syncthingandroid.runtime.ExecutionOwnershipManager.RecoveryAssessment.class);
        recovered.setAccessible(true);
        recovered.invoke(fixture.service, true, null);

        assertFalse(FileMutationBarrier.executionModeTransitionPending());
        assertFalse(fixture.mode.durableRoot);
        assertFalse(fixture.effects.events.contains("commit-root"));
    }

    @Test
    public void stoppedRootCycleRearmsOnlyAPreviouslyAuthorizedLaunch()
            throws Exception {
        Fixture authorized = new Fixture(true, true, true,
                SyncthingService.State.DISABLED, false);
        setServiceField(authorized.service, "mRootStartupAuthorizationAttempted", true);
        setServiceField(authorized.service, "mRootStartupAuthorizationComplete", true);
        Method stopped = SyncthingService.class.getDeclaredMethod(
                "invalidateRootStartupForStoppedRunConditions");
        stopped.setAccessible(true);
        stopped.invoke(authorized.service);
        Field attempted = SyncthingService.class.getDeclaredField(
                "mRootStartupAuthorizationAttempted");
        attempted.setAccessible(true);
        assertFalse(attempted.getBoolean(authorized.service));
        assertTrue(ExecutionModeTransitionPolicy.mayAttemptRootStartup(
                true, true, attempted.getBoolean(authorized.service), false));

        Fixture denied = new Fixture(true, true, true,
                SyncthingService.State.DISABLED, false);
        setServiceField(denied.service, "mRootStartupAuthorizationAttempted", true);
        stopped.invoke(denied.service);
        assertTrue(attempted.getBoolean(denied.service));
        assertFalse(ExecutionModeTransitionPolicy.mayAttemptRootStartup(
                true, true, attempted.getBoolean(denied.service), false));

        Fixture lostDuringConfiguration = new Fixture(true, true, true,
                SyncthingService.State.DISABLED, false);
        setServiceField(lostDuringConfiguration.service,
                "mRootStartupAuthorizationAttempted", true);
        setServiceField(lostDuringConfiguration.service,
                "mRootStartupAuthorizationComplete", true);
        Method fail = SyncthingService.class.getDeclaredMethod("failRootStartup",
                ExecutionModeTransitionStatus.FailureKind.class, RootFailure.class,
                ManagedStateFailure.class, String.class);
        fail.setAccessible(true);
        fail.invoke(lostDuringConfiguration.service,
                ExecutionModeTransitionStatus.FailureKind.ROOT,
                RootFailure.ROOT_AUTHORIZATION_LOST, null, "Authorization was lost");
        stopped.invoke(lostDuringConfiguration.service);
        assertTrue("authorization loss cannot silently rearm a later Run Condition cycle",
                attempted.getBoolean(lostDuringConfiguration.service));
    }

    private static void setServiceField(SyncthingService service, String name, Object value)
            throws Exception {
        Field field = SyncthingService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    @Test
    public void destructionDuringRepairDropsResultAndPreventsCommit() {
        Fixture fixture = new Fixture(true, true, true,
                SyncthingService.State.ACTIVE, false);
        fixture.reachShutdown(false);
        fixture.effects.completeShutdown(true);
        assertTrue(fixture.effects.hasWorker());

        fixture.service.destroyExecutionModeLifecycleForTest();
        fixture.effects.completeShutdown(true);
        fixture.runAllWorkersAndCallbacks();

        assertTrue(fixture.mode.durableRoot);
        assertTrue(fixture.mode.runtimeRoot);
        assertFalse(fixture.effects.events.contains("commit-normal"));
    }

    @Test
    public void destructionBeforeCommitCancelsItAndAfterCommitDefersSelectionToRecreatedService() {
        Fixture before = new Fixture(false, false, true,
                SyncthingService.State.DISABLED, false);
        before.reachShutdown(true);
        before.effects.completeShutdown(true);
        assertTrue(before.effects.hasWorker()); // durable commit has not run
        before.service.destroyExecutionModeLifecycleForTest();
        before.effects.completeShutdown(true);
        before.runAllWorkersAndCallbacks();
        assertFalse(before.mode.durableRoot);
        assertFalse(before.mode.runtimeRoot);

        Fixture old = new Fixture(false, false, true,
                SyncthingService.State.DISABLED, false);
        old.reachShutdown(true);
        old.effects.completeShutdown(true);
        old.runWorker(); // commit is durable; service callback has not selected a backend
        assertTrue(old.mode.durableRoot);
        assertFalse(old.mode.runtimeRoot);

        old.service.destroyExecutionModeLifecycleForTest();
        old.effects.completeShutdown(true);
        old.runAllCallbacks();
        assertFalse(old.mode.runtimeRoot);
        assertTrue(FileMutationBarrier.executionModeTransitionPending() == false);

        Fixture recreated = new Fixture(old.mode, true, SyncthingService.State.DISABLED, false);
        recreated.service.reconcileRuntimeExecutionModeForTest();
        recreated.runWorkerAndCallback();

        assertTrue(recreated.mode.durableRoot);
        assertTrue(recreated.mode.runtimeRoot);
        assertBefore(recreated.effects.events, "select-root", "launch-SERVE");
    }

    @Test
    public void destructionDuringCommitCannotSwitchOrLaunchFromTheOldService() {
        Fixture fixture = new Fixture(false, false, true,
                SyncthingService.State.DISABLED, false);
        fixture.reachShutdown(true);
        fixture.effects.completeShutdown(true);
        fixture.effects.beforeCommitWrite = fixture.service::destroyExecutionModeLifecycleForTest;

        fixture.runWorker();

        assertTrue(fixture.mode.durableRoot);
        assertFalse(fixture.mode.runtimeRoot);
        assertFalse(fixture.effects.events.contains("select-root"));
        assertFalse(fixture.effects.events.contains("launch-SERVE"));
        fixture.effects.completeShutdown(true);
        fixture.runAllCallbacks();
        assertFalse(fixture.mode.runtimeRoot);
        assertFalse(fixture.effects.events.contains("select-root"));
    }

    @Test
    public void coldStartSelectsTheBackendFromTheDurablePreference() {
        for (boolean committedRoot : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(committedRoot, !committedRoot, true,
                    SyncthingService.State.DISABLED, false);
            fixture.service.reconcileRuntimeExecutionModeForTest();
            fixture.runWorkerAndCallback();

            assertEquals(committedRoot, fixture.mode.runtimeRoot);
            assertEquals(committedRoot ? "select-root" : "select-normal",
                    fixture.effects.lastSelection());
            assertBefore(fixture.effects.events, fixture.effects.lastSelection(), "launch-SERVE");
        }
    }

    private static ManagedStateException unavailableRepair() {
        return new ManagedStateException(
                ManagedStateFailure.STATE_REPAIR_FAILED,
                "Previously authorized root access is unavailable",
                new RootTransportException(RootFailure.ROOT_DENIED, "Denied")
        );
    }

    private static void assertBefore(List<String> events, String first, String second) {
        int firstIndex = events.indexOf(first);
        int secondIndex = events.indexOf(second);
        assertTrue("Missing ordered events " + first + " then " + second + ": " + events,
                firstIndex >= 0 && secondIndex > firstIndex);
    }

    private static AtomicReference<String> installRootScriptConsent(
            Fixture fixture, boolean resetSucceeds
    ) throws Exception {
        AtomicReference<String> durable = new AtomicReference<>("ARMED");
        RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(
                new RootRunScriptAuthorizationPolicy.Store() {
                    @Override public String read() { return durable.get(); }
                    @Override public boolean commit(String value) {
                        if ("OFF".equals(value)) {
                            fixture.effects.events.add("reset-script-consent");
                            if (!resetSucceeds) return false;
                        }
                        durable.set(value);
                        return true;
                    }
                });
        Field field = SyncthingService.class.getDeclaredField("mRootRunScriptPromptPolicy");
        field.setAccessible(true);
        field.set(fixture.service, policy);
        return durable;
    }

    private static final class Fixture {
        private final SharedMode mode;
        private final SyncthingService service;
        private final Effects effects;

        private Fixture(boolean durableRoot, boolean runtimeRoot, boolean shouldRun,
                        SyncthingService.State state, boolean repairRequired) {
            this(new SharedMode(durableRoot, runtimeRoot), shouldRun, state, repairRequired);
        }

        private Fixture(SharedMode mode, boolean shouldRun,
                        SyncthingService.State state, boolean repairRequired) {
            this.mode = mode;
            service = new SyncthingService();
            effects = new Effects(mode);
            service.setExecutionModeTransitionTestSeam(effects);
            service.setExecutionModeTransitionTestState(shouldRun, repairRequired, state);
        }

        private void reachShutdown(boolean targetRoot) {
            service.requestExecutionModeTransition(targetRoot);
            runCallback();
            if (targetRoot) runWorkerAndCallback(); // authorization
            runWorkerAndCallback(); // admission drain
            assertEquals(1, effects.shutdownCallbacks.size());
        }

        private void runCallback() {
            effects.runCallback();
        }

        private void runWorker() {
            effects.runWorker();
        }

        private void runWorkerAndCallback() {
            runWorker();
            runCallback();
        }

        private void runAllCallbacks() {
            while (effects.hasCallback()) runCallback();
        }

        private void runAllWorkersAndCallbacks() {
            int guard = 0;
            while ((effects.hasWorker() || effects.hasCallback()) && guard++ < 40) {
                if (effects.hasWorker()) runWorker();
                if (effects.hasCallback()) runCallback();
            }
            assertTrue("The deterministic service queues did not settle",
                    guard < 40 && !effects.hasWorker() && !effects.hasCallback());
        }
    }

    private static final class SharedMode {
        private boolean durableRoot;
        private boolean runtimeRoot;

        private SharedMode(boolean durableRoot, boolean runtimeRoot) {
            this.durableRoot = durableRoot;
            this.runtimeRoot = runtimeRoot;
        }
    }

    private static final class Effects implements ExecutionModeTransitionTestSeam {
        private final SharedMode mode;
        private final Deque<Runnable> callbacks = new ArrayDeque<>();
        private final Deque<Runnable> workers = new ArrayDeque<>();
        private final Deque<Consumer<Boolean>> shutdownCallbacks = new ArrayDeque<>();
        private final List<String> events = new ArrayList<>();
        private RootFailure authorizationFailure;
        private ManagedStateException repairFailure;
        private boolean scriptsDrained = true;
        private boolean backendDrained = true;
        private boolean stateAccessible = true;
        private boolean commitSucceeds = true;
        private Runnable beforeCommitWrite;
        private SharedPreferences preferences;
        private Runnable startupConfigurationTimeout;
        private StartupConfigurationLoad.Callback startupConfigurationCallback;
        private int startupConfigurationLoadCount;
        private boolean failNextSelection;

        private Effects(SharedMode mode) {
            this.mode = mode;
        }

        @Override
        public boolean post(Runnable callback) {
            callbacks.addLast(callback);
            return true;
        }

        @Override
        public boolean executeWorker(String name, Runnable work) {
            workers.addLast(work);
            return true;
        }

        @Override
        public boolean durableMode() {
            return mode.durableRoot;
        }

        @Override
        public boolean runtimeMode() {
            return mode.runtimeRoot;
        }

        @Override
        public RootFailure authorizeRoot() {
            events.add("authorize-root");
            return authorizationFailure;
        }

        @Override
        public boolean closeScriptAdmission(long timeoutMillis) {
            events.add("scripts-drained");
            return scriptsDrained;
        }

        @Override
        public boolean closeBackendAdmission(long timeoutMillis) {
            events.add("backend-drained");
            return backendDrained;
        }

        @Override
        public void reopenBackendAdmissionWhenDrained() {
            events.add("reopen-after-drain");
        }

        @Override
        public void openBackendAdmission() {
            events.add("open-admission");
        }

        @Override
        public void shutdown(Consumer<Boolean> completion) {
            events.add("shutdown-requested");
            shutdownCallbacks.addLast(completion);
        }

        private void completeShutdown(boolean safe) {
            assertFalse("No shutdown is waiting for proof", shutdownCallbacks.isEmpty());
            events.add(safe ? "shutdown-proven" : "shutdown-unproven");
            shutdownCallbacks.removeFirst().accept(safe);
        }

        @Override
        public ManagedStateException repairState(boolean explicitAuthorization) {
            events.add(explicitAuthorization ? "repair-explicit" : "repair-automatic");
            return repairFailure;
        }

        @Override
        public boolean applicationStateAccessible() {
            events.add("state-access-verified");
            return stateAccessible;
        }

        @Override
        public boolean commitMode(boolean useRoot) {
            events.add(useRoot ? "commit-start-root" : "commit-start-normal");
            if (beforeCommitWrite != null) {
                Runnable hook = beforeCommitWrite;
                beforeCommitWrite = null;
                hook.run();
            }
            if (commitSucceeds) {
                if (preferences == null) {
                    mode.durableRoot = useRoot;
                } else {
                    commitSucceeds = preferences.edit()
                            .putBoolean(Constants.PREF_USE_ROOT, useRoot)
                            .commit();
                    if (commitSucceeds) mode.durableRoot = useRoot;
                }
            }
            events.add(useRoot ? "commit-root" : "commit-normal");
            return commitSucceeds;
        }

        @Override
        public void selectMode(boolean useRoot) {
            events.add(useRoot ? "select-root" : "select-normal");
            if (failNextSelection) {
                failNextSelection = false;
                throw new IllegalStateException("Simulated runtime selection failure");
            }
            mode.runtimeRoot = useRoot;
        }

        @Override
        public void launchStartup(SyncthingCommand command) {
            events.add("launch-" + command.name());
        }

        @Override
        public void startStartupConfigurationLoad(
                StartupConfigurationLoad.Source source,
                StartupConfigurationLoad.Callback callback
        ) {
            startupConfigurationLoadCount++;
            startupConfigurationCallback = callback;
        }

        @Override
        public void scheduleStartupConfigurationTimeout(Runnable timeout, long delayMillis) {
            startupConfigurationTimeout = timeout;
        }

        private void completeStartupConfiguration(StartupConfigurationLoad.Result result) {
            assertNotNull(startupConfigurationCallback);
            StartupConfigurationLoad.Callback callback = startupConfigurationCallback;
            startupConfigurationCallback = null;
            callback.onComplete(result);
        }

        private int count(String event) {
            int count = 0;
            for (String actual : events) if (event.equals(actual)) count++;
            return count;
        }

        private String lastSelection() {
            for (int i = events.size() - 1; i >= 0; i--) {
                String event = events.get(i);
                if (event.startsWith("select-")) return event;
            }
            return null;
        }

        private boolean hasWorker() {
            return !workers.isEmpty();
        }

        private boolean hasCallback() {
            return !callbacks.isEmpty();
        }

        private void runWorker() {
            assertFalse("No deterministic worker is queued; events=" + events
                    + ", callbacks=" + callbacks.size(), workers.isEmpty());
            workers.removeFirst().run();
        }

        private void runCallback() {
            assertFalse("No service callback is queued; events=" + events
                    + ", workers=" + workers.size(), callbacks.isEmpty());
            callbacks.removeFirst().run();
        }
    }

    private static SharedPreferences recordingPreferences(boolean initialRoot) {
        Map<String, Object> values = new HashMap<>();
        values.put(Constants.PREF_USE_ROOT, initialRoot);
        List<SharedPreferences.OnSharedPreferenceChangeListener> listeners = new ArrayList<>();
        SharedPreferences[] holder = new SharedPreferences[1];
        holder[0] = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getBoolean":
                            return values.getOrDefault(
                                    (String) arguments[0], arguments[1]
                            );
                        case "registerOnSharedPreferenceChangeListener":
                            listeners.add((SharedPreferences.OnSharedPreferenceChangeListener)
                                    arguments[0]);
                            return null;
                        case "unregisterOnSharedPreferenceChangeListener":
                            listeners.remove(arguments[0]);
                            return null;
                        case "edit":
                            Map<String, Object> pending = new HashMap<>();
                            SharedPreferences.Editor[] editorHolder =
                                    new SharedPreferences.Editor[1];
                            editorHolder[0] = (SharedPreferences.Editor) Proxy.newProxyInstance(
                                    SharedPreferences.Editor.class.getClassLoader(),
                                    new Class<?>[]{SharedPreferences.Editor.class},
                                    (editor, editorMethod, editorArguments) -> {
                                        if ("putBoolean".equals(editorMethod.getName())) {
                                            pending.put((String) editorArguments[0],
                                                    editorArguments[1]);
                                            return editorHolder[0];
                                        }
                                        if ("commit".equals(editorMethod.getName())
                                                || "apply".equals(editorMethod.getName())) {
                                            for (Map.Entry<String, Object> entry
                                                    : pending.entrySet()) {
                                                values.put(entry.getKey(), entry.getValue());
                                                for (SharedPreferences.OnSharedPreferenceChangeListener
                                                        listener : new ArrayList<>(listeners)) {
                                                    listener.onSharedPreferenceChanged(
                                                            holder[0], entry.getKey()
                                                    );
                                                }
                                            }
                                            return "commit".equals(editorMethod.getName());
                                        }
                                        return editorHolder[0];
                                    }
                            );
                            return editorHolder[0];
                        default:
                            return null;
                    }
                }
        );
        return holder[0];
    }
}
