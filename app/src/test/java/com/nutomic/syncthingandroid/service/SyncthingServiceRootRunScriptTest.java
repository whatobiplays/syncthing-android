package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import com.nutomic.syncthingandroid.runtime.ConfiguredFolderReference;
import com.nutomic.syncthingandroid.runtime.DefaultSyncthingRuntime;
import com.nutomic.syncthingandroid.runtime.FolderEvent;
import com.nutomic.syncthingandroid.runtime.FolderOperationException;
import com.nutomic.syncthingandroid.runtime.FolderOperationFailure;
import com.nutomic.syncthingandroid.runtime.FolderScriptAdmission;
import com.nutomic.syncthingandroid.runtime.FolderScriptOutcome;
import com.nutomic.syncthingandroid.runtime.PrivilegeBackend;
import com.nutomic.syncthingandroid.runtime.RootAuthorizationCapability;
import com.nutomic.syncthingandroid.runtime.RunScriptRootAuthorization;
import com.nutomic.syncthingandroid.runtime.TuningOutcome;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the service's real root-script acceptance and worker ownership. */
public class SyncthingServiceRootRunScriptTest {
    private static final long WAIT_MILLIS = 5_000;

    @Test public void offRejectsTheEventBeforeSchedulingAnyRootWork() throws Exception {
        Fixture fixture = new Fixture();
        DefaultSyncthingRuntime.FolderScriptReservation reservation = fixture.reserve();
        try {
            assertEquals(SyncthingService.RootRunScriptDispatchDecision.UNAVAILABLE,
                    fixture.dispatch(reservation));
            assertEquals(0, fixture.backendCalls.get());
            assertEquals("OFF", fixture.durable.get());
        } finally {
            reservation.close();
            fixture.scriptAdmission.leave();
        }
    }

    @Test public void explicitConsentArmsOnlyFutureCompletionEvents() throws Exception {
        Fixture fixture = new Fixture();
        fixture.service.requestRootRunScriptPromptConsent(true);
        assertTrue(fixture.consentArmed.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals("ARMED", fixture.durable.get());
        assertEquals(0, fixture.backendCalls.get());
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(fixture.reserve()));
        assertTrue(fixture.backendEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        fixture.backendRelease.countDown();
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(1, fixture.backendCalls.get());
    }

    @Test public void failedAttemptCommitReleasesAdmissionWithoutAcquiringRoot()
            throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        fixture.failAttemptCommit = true;
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(fixture.reserve()));
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(0, fixture.backendCalls.get());
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.PERSISTENCE,
                fixture.policy.status().reason());
        assertEquals("ARMED", fixture.durable.get());
        assertTrue(fixture.scriptAdmission.closeAndWait(0));
        assertTrue(fixture.runtime.closeBackendOperationAdmission(0));
    }

    @Test public void destructionDuringSlowConsentCommitCannotArmARecreatedService()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.blockArmCommit = true;
        fixture.service.requestRootRunScriptPromptConsent(true);
        assertTrue(fixture.armCommitEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        Method destroy = SyncthingService.class.getDeclaredMethod("markServiceDestroying");
        destroy.setAccessible(true);
        destroy.invoke(fixture.service);
        fixture.releaseArmCommit.countDown();
        assertTrue(fixture.suspensionCommitted.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.INTERRUPTED,
                new RootRunScriptAuthorizationPolicy(fixture.store).status().reason());
        assertEquals(0, fixture.backendCalls.get());
    }

    @Test public void admittedRootEventIsAsyncSingleFlightAndHoldsBothGatesUntilTerminal()
            throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        DefaultSyncthingRuntime.FolderScriptReservation first = fixture.reserve();
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(first));
        assertTrue(fixture.backendEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals("ATTEMPT_IN_PROGRESS", fixture.durable.get());
        assertFalse("an admitted backend operation is still unresolved",
                fixture.runtime.closeBackendOperationAdmission(0));

        DefaultSyncthingRuntime.FolderScriptReservation second = null;
        try {
            // A closed runtime gate rejects a concurrent event before it can reach the service.
            assertTrue(fixture.scriptAdmission.tryAdmit());
            second = fixture.runtime.tryReserveFolderScriptOperation();
            assertEquals(null, second);
            fixture.scriptAdmission.leave();
        } finally {
            if (second != null) second.close();
        }
        fixture.backendRelease.countDown();
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(1, fixture.backendCalls.get());
        assertTrue(fixture.scriptAdmission.closeAndWait(0));
        assertEquals(RootRunScriptAuthorizationPolicy.State.ARMED,
                fixture.policy.status().state());
        assertTrue(fixture.runtime.closeBackendOperationAdmission(0));
    }

    @Test public void concurrentEventIsRejectedAsBusyWithoutASecondWorker() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        DefaultSyncthingRuntime.FolderScriptReservation first = fixture.reserve();
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(first));
        assertTrue(fixture.backendEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        DefaultSyncthingRuntime.FolderScriptReservation second = fixture.reserve();
        try {
            assertEquals(SyncthingService.RootRunScriptDispatchDecision.BUSY,
                    fixture.dispatch(second));
        } finally {
            second.close();
            fixture.scriptAdmission.leave();
            fixture.backendRelease.countDown();
        }
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(1, fixture.backendCalls.get());
    }

    @Test public void authorizationFailureSuspendsButAnOrdinaryScriptExitDoesNot()
            throws Exception {
        Fixture denied = new Fixture();
        assertTrue(denied.policy.arm());
        denied.failure.set(FolderOperationFailure.ROOT_SCRIPT_AUTH_DENIED);
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                denied.dispatch(denied.reserve()));
        assertTrue(denied.backendEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertTrue("service authorization must still own this event", denied.currentAtBackend.get());
        denied.backendRelease.countDown();
        assertTrue(denied.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.DENIED,
                denied.policy.status().reason());

        Fixture scriptExit = new Fixture();
        assertTrue(scriptExit.policy.arm());
        scriptExit.outcomes.set(Collections.singletonList(FolderScriptOutcome.of("backup.sh", 7)));
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                scriptExit.dispatch(scriptExit.reserve()));
        assertTrue(scriptExit.backendEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        scriptExit.backendRelease.countDown();
        assertTrue(scriptExit.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(RootRunScriptAuthorizationPolicy.State.ARMED,
                scriptExit.policy.status().state());
    }

    @Test public void destructionInvalidatesAnAdmittedEventAndCannotRearmFromItsLateResult()
            throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(fixture.reserve()));
        assertTrue(fixture.backendEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        Method destroy = SyncthingService.class.getDeclaredMethod("markServiceDestroying");
        destroy.setAccessible(true);
        destroy.invoke(fixture.service);
        fixture.backendRelease.countDown();
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals("ATTEMPT_IN_PROGRESS", fixture.durable.get());
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.INTERRUPTED,
                new RootRunScriptAuthorizationPolicy(fixture.store).status().reason());
    }

    @Test public void teardownCancelsAnAdmittedEventWithoutReleasingItsGatesEarly()
            throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(fixture.reserve()));
        assertTrue(fixture.backendEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        fixture.service.cancelAdmittedRootRunScript();
        assertFalse(fixture.runtime.closeBackendOperationAdmission(0));
        assertFalse(fixture.scriptAdmission.closeAndWait(0));
        fixture.backendRelease.countDown();
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(RootRunScriptAuthorizationPolicy.State.SUSPENDED,
                fixture.policy.status().state());
        assertTrue(fixture.scriptAdmission.closeAndWait(0));
        assertTrue(fixture.runtime.closeBackendOperationAdmission(0));
    }

    @Test public void teardownDoesNotInterruptAClaimedScriptBeforeItSettles()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.claimScriptExecution = true;
        assertTrue(fixture.policy.arm());
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(fixture.reserve()));
        assertTrue(fixture.scriptExecutionClaimed.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));

        fixture.service.cancelAdmittedRootRunScript();
        assertEquals(0, fixture.backendInterruptions.get());
        assertFalse(fixture.runtime.closeBackendOperationAdmission(0));
        assertFalse(fixture.scriptAdmission.closeAndWait(0));
        fixture.backendRelease.countDown();
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(0, fixture.backendInterruptions.get());
        assertTrue(fixture.scriptAdmission.closeAndWait(0));
        assertTrue(fixture.runtime.closeBackendOperationAdmission(0));
    }

    @Test public void authorizationTimeoutSuspendsWhileTheHelperIsStillQuiescing()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.reportAuthorizationTimeout = true;
        assertTrue(fixture.policy.arm());
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(fixture.reserve()));
        assertTrue(fixture.authorizationTimedOut.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.TIMEOUT,
                fixture.policy.status().reason());
        assertFalse(fixture.runtime.closeBackendOperationAdmission(0));
        assertFalse(fixture.scriptAdmission.closeAndWait(0));
        assertFalse("Resume must wait for the original worker", fixture.policy.arm());
        fixture.backendRelease.countDown();
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.TIMEOUT,
                fixture.policy.status().reason());
        assertTrue(fixture.policy.arm());
    }

    @Test public void optOutDuringAnEventNeverRearmsFromItsLateCompletion() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(fixture.reserve()));
        assertTrue(fixture.backendEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertTrue(fixture.policy.disable());
        fixture.backendRelease.countDown();
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals("OFF", fixture.durable.get());
        assertEquals(RootRunScriptAuthorizationPolicy.State.OFF, fixture.policy.status().state());
    }

    @Test public void acceptedOptOutStillCommitsAfterDestructionBeforeItsWorkerStarts()
            throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        AtomicReference<Thread> scheduled = new AtomicReference<>();
        fixture.service.setRootScriptWorkerStarterForTest(scheduled::set);
        fixture.service.requestRootRunScriptPromptConsent(false);
        assertNotNull(scheduled.get());
        destroy(fixture.service);
        RootRunScriptAuthorizationPolicy recreated =
                new RootRunScriptAuthorizationPolicy(fixture.store);
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.PERSISTENCE,
                recreated.status().reason());
        assertEquals(null, recreated.begin(3));
        scheduled.get().start();
        assertTrue(fixture.offCommitted.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        scheduled.get().join(WAIT_MILLIS);
        assertFalse(scheduled.get().isAlive());
        assertEquals("OFF", fixture.durable.get());
        assertEquals(RootRunScriptAuthorizationPolicy.State.OFF,
                recreated.status().state());
    }

    @Test public void optOutSupersedesQueuedOptInEvenWhenServiceIsDestroyed()
            throws Exception {
        Fixture fixture = new Fixture();
        AtomicReference<Thread> queuedArm = new AtomicReference<>();
        AtomicReference<Thread> queuedOff = new AtomicReference<>();
        AtomicInteger scheduled = new AtomicInteger();
        fixture.service.setRootScriptWorkerStarterForTest(worker -> {
            if (scheduled.getAndIncrement() == 0) queuedArm.set(worker);
            else queuedOff.set(worker);
        });

        fixture.service.requestRootRunScriptPromptConsent(true);
        fixture.service.requestRootRunScriptPromptConsent(false);
        assertNotNull("an accepted OFF request needs its own durable worker", queuedOff.get());
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.PERSISTENCE,
                new RootRunScriptAuthorizationPolicy(fixture.store).status().reason());
        destroy(fixture.service);
        queuedArm.get().start();
        queuedArm.get().join(WAIT_MILLIS);
        queuedOff.get().start();
        queuedOff.get().join(WAIT_MILLIS);
        assertFalse(queuedArm.get().isAlive());
        assertFalse(queuedOff.get().isAlive());
        assertEquals("OFF", fixture.durable.get());
        assertEquals(RootRunScriptAuthorizationPolicy.State.OFF,
                new RootRunScriptAuthorizationPolicy(fixture.store).status().state());
    }

    @Test public void optOutSupersedesAnOptInCommitAlreadyInProgress()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.blockArmCommit = true;
        fixture.service.requestRootRunScriptPromptConsent(true);
        assertTrue(fixture.armCommitEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        fixture.service.requestRootRunScriptPromptConsent(false);
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.PERSISTENCE,
                fixture.policy.status().reason());
        fixture.releaseArmCommit.countDown();
        assertTrue(fixture.offCommitted.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals("OFF", fixture.durable.get());
        DefaultSyncthingRuntime.FolderScriptReservation reservation = fixture.reserve();
        try {
            assertEquals(SyncthingService.RootRunScriptDispatchDecision.UNAVAILABLE,
                    fixture.dispatch(reservation));
        } finally {
            reservation.close();
            fixture.scriptAdmission.leave();
        }
    }

    @Test public void failedOptOutWorkerStartCannotRestoreConsentOnRecreation()
            throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        fixture.service.setRootScriptWorkerStarterForTest(worker -> {
            throw new IllegalStateException("Worker unavailable");
        });
        fixture.service.requestRootRunScriptPromptConsent(false);
        assertEquals("ARMED", fixture.durable.get());
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.PERSISTENCE,
                fixture.policy.status().reason());
        assertFalse(fixture.service.isRootRunScriptPromptConsentMutationPending());
        destroy(fixture.service);
        RootRunScriptAuthorizationPolicy recreated =
                new RootRunScriptAuthorizationPolicy(fixture.store);
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.PERSISTENCE,
                recreated.status().reason());
        assertEquals(null, recreated.begin(7));
        assertEquals(0, fixture.backendCalls.get());
        assertTrue("a later explicit request may establish durable OFF", recreated.disable());
        assertEquals("OFF", fixture.durable.get());
    }

    @Test public void queuedEventCannotBeginAuthorizationAfterTransitionAdmission()
            throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        AtomicReference<Thread> queued = new AtomicReference<>();
        fixture.service.setRootScriptWorkerStarterForTest(queued::set);
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(fixture.reserve()));
        assertNotNull(queued.get());
        Runnable failDrain = fixture.beginFailingDisableTransition();
        DefaultSyncthingRuntime.FolderScriptReservation lateReservation = fixture.reserve();
        try {
            assertEquals(SyncthingService.RootRunScriptDispatchDecision.OBSOLETE,
                    fixture.dispatch(lateReservation));
        } finally {
            lateReservation.close();
            fixture.scriptAdmission.leave();
        }
        queued.get().start();
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(0, fixture.backendCalls.get());
        assertEquals("ARMED", fixture.durable.get());
        failDrain.run();

        fixture.service.setRootScriptWorkerStarterForTest(Thread::start);
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(fixture.reserve()));
        assertTrue(fixture.backendEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        fixture.backendRelease.countDown();
        assertEquals(1, fixture.backendCalls.get());
    }

    @Test public void transitionBeforeAuthorizationBoundaryCancelsPromptAndNeedsResume()
            throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        fixture.pauseBeforeAuthorization = true;
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(fixture.reserve()));
        assertTrue(fixture.beforeAuthorization.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        Runnable failDrain = fixture.beginFailingDisableTransition();
        fixture.releaseAuthorization.countDown();
        fixture.backendRelease.countDown();
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        failDrain.run();
        assertEquals(0, fixture.authorizationStarts.get());
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.INTERRUPTED,
                fixture.policy.status().reason());
        assertEquals(RootRunScriptAuthorizationPolicy.State.SUSPENDED,
                new RootRunScriptAuthorizationPolicy(fixture.store).status().state());
    }

    @Test public void transitionDuringDurableAttemptCommitCannotStartLateAuthorization()
            throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        fixture.blockAttemptCommit = true;
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(fixture.reserve()));
        assertTrue(fixture.attemptCommitEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        Runnable failDrain = fixture.beginFailingDisableTransition();
        fixture.releaseAttemptCommit.countDown();
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        failDrain.run();
        assertEquals(0, fixture.backendCalls.get());
        assertEquals("ATTEMPT_IN_PROGRESS", fixture.durable.get());
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.INTERRUPTED,
                fixture.policy.status().reason());
    }

    @Test public void shutdownCancelsWorkerQueuedAfterBothGatesWereReserved()
            throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        AtomicReference<Thread> queued = new AtomicReference<>();
        fixture.service.setRootScriptWorkerStarterForTest(queued::set);
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(fixture.reserve()));
        fixture.service.cancelAdmittedRootRunScript();
        queued.get().start();
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(0, fixture.backendCalls.get());
        assertEquals("ARMED", fixture.durable.get());
        assertTrue(fixture.scriptAdmission.closeAndWait(0));
        assertTrue(fixture.runtime.closeBackendOperationAdmission(0));
    }

    @Test public void failedOptOutPersistenceIsVisibleAndCannotAuthorizeInThisService()
            throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        fixture.failOffCommit = true;
        AtomicReference<Thread> optOutWorker = new AtomicReference<>();
        fixture.service.setRootScriptWorkerStarterForTest(worker -> {
            optOutWorker.set(worker);
            worker.start();
        });
        fixture.service.requestRootRunScriptPromptConsent(false);
        assertTrue(fixture.offCommitAttempted.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        optOutWorker.get().join(WAIT_MILLIS);
        assertFalse(optOutWorker.get().isAlive());
        assertEquals("ARMED", fixture.durable.get());
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.PERSISTENCE,
                fixture.policy.status().reason());
        DefaultSyncthingRuntime.FolderScriptReservation reservation = fixture.reserve();
        try {
            assertEquals(SyncthingService.RootRunScriptDispatchDecision.UNAVAILABLE,
                    fixture.dispatch(reservation));
            assertEquals(0, fixture.backendCalls.get());
        } finally {
            reservation.close();
            fixture.scriptAdmission.leave();
        }
    }

    @Test public void optOutDuringActiveAuthorizationCommitsOffAndLateResultCannotRearm()
            throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.policy.arm());
        assertEquals(SyncthingService.RootRunScriptDispatchDecision.ACCEPTED,
                fixture.dispatch(fixture.reserve()));
        assertTrue(fixture.backendEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        fixture.service.requestRootRunScriptPromptConsent(false);
        assertTrue(fixture.offCommitted.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        fixture.backendRelease.countDown();
        assertTrue(fixture.finished.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals("OFF", fixture.durable.get());
        assertEquals(RootRunScriptAuthorizationPolicy.State.OFF,
                fixture.policy.status().state());
    }

    private static void destroy(SyncthingService service) throws Exception {
        Method destroy = SyncthingService.class.getDeclaredMethod("markServiceDestroying");
        destroy.setAccessible(true);
        destroy.invoke(service);
    }

    static final class Fixture {
        final AtomicReference<String> durable = new AtomicReference<>("OFF");
        final CountDownLatch consentArmed = new CountDownLatch(1);
        final CountDownLatch armCommitEntered = new CountDownLatch(1);
        final CountDownLatch releaseArmCommit = new CountDownLatch(1);
        final CountDownLatch suspensionCommitted = new CountDownLatch(1);
        final CountDownLatch offCommitted = new CountDownLatch(1);
        final CountDownLatch offCommitAttempted = new CountDownLatch(1);
        final CountDownLatch attemptCommitEntered = new CountDownLatch(1);
        final CountDownLatch releaseAttemptCommit = new CountDownLatch(1);
        volatile boolean blockArmCommit;
        volatile boolean failAttemptCommit;
        volatile boolean failOffCommit;
        volatile boolean blockAttemptCommit;
        final RootRunScriptAuthorizationPolicy.Store store = new RootRunScriptAuthorizationPolicy.Store() {
            @Override public String read() { return durable.get(); }
            @Override public boolean commit(String value) {
                if (failAttemptCommit && "ATTEMPT_IN_PROGRESS".equals(value)) return false;
                if ("OFF".equals(value)) {
                    offCommitAttempted.countDown();
                    if (failOffCommit) return false;
                }
                durable.set(value);
                if (blockAttemptCommit && "ATTEMPT_IN_PROGRESS".equals(value)) {
                    attemptCommitEntered.countDown();
                    try { releaseAttemptCommit.await(); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                if ("OFF".equals(value)) offCommitted.countDown();
                if ("ARMED".equals(value)) {
                    consentArmed.countDown();
                    if (blockArmCommit) {
                        armCommitEntered.countDown();
                        try { releaseArmCommit.await(); }
                        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                }
                if ("SUSPENDED:INTERRUPTED".equals(value)) suspensionCommitted.countDown();
                return true;
            }
        };
        final RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(store);
        final SyncthingService service;
        final CountDownLatch backendEntered = new CountDownLatch(1);
        final CountDownLatch backendRelease = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch authorizationTimedOut = new CountDownLatch(1);
        final CountDownLatch scriptExecutionClaimed = new CountDownLatch(1);
        final CountDownLatch beforeAuthorization = new CountDownLatch(1);
        final CountDownLatch releaseAuthorization = new CountDownLatch(1);
        final AtomicInteger backendCalls = new AtomicInteger();
        final AtomicInteger appScriptCalls = new AtomicInteger();
        final AtomicInteger authorizationStarts = new AtomicInteger();
        final AtomicInteger backendInterruptions = new AtomicInteger();
        final AtomicReference<FolderOperationFailure> failure = new AtomicReference<>();
        final AtomicReference<Boolean> currentAtBackend = new AtomicReference<>(false);
        final AtomicReference<List<FolderScriptOutcome>> outcomes =
                new AtomicReference<>(Collections.emptyList());
        final FolderScriptAdmission scriptAdmission = new FolderScriptAdmission();
        volatile boolean reportAuthorizationTimeout;
        volatile boolean pauseBeforeAuthorization;
        volatile boolean claimScriptExecution;
        final DefaultSyncthingRuntime runtime;

        Fixture() throws Exception { this(new SyncthingService(), true); }

        Fixture(SyncthingService service, boolean rootMode) throws Exception {
            this.service = service;
            SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.class},
                    (proxy, method, args) -> {
                        if ("getBoolean".equals(method.getName())) return true;
                        throw new AssertionError("Unexpected preference access: " + method.getName());
                    });
            PrivilegeBackend app = backend(false);
            PrivilegeBackend root = backend(true);
            RootAuthorizationCapability rootCapability = new RootAuthorizationCapability() {
                @Override public void verifyAuthorization() { throw new AssertionError(); }
                @Override public boolean isAuthorizationAvailableWithoutPrompt() { return false; }
                @Override public TuningOutcome applyInotifyWatchLimitIfAuthorized() {
                    throw new AssertionError();
                }
            };
            runtime = new DefaultSyncthingRuntime(app, root, rootCapability, rootMode);
            service.mRuntime = runtime;
            service.mPreferences = preferences;
            Field policyField = SyncthingService.class.getDeclaredField("mRootRunScriptPromptPolicy");
            policyField.setAccessible(true);
            policyField.set(service, policy);
        }

        PrivilegeBackend backend(boolean root) {
            return (PrivilegeBackend) Proxy.newProxyInstance(
                    PrivilegeBackend.class.getClassLoader(),
                    new Class<?>[]{PrivilegeBackend.class},
                    (proxy, method, args) -> {
                        if ("runFolderScripts".equals(method.getName()) && root) {
                            backendCalls.incrementAndGet();
                            RunScriptRootAuthorization authorization =
                                    (RunScriptRootAuthorization) args[2];
                            currentAtBackend.set(authorization.isCurrent());
                            backendEntered.countDown();
                            if (reportAuthorizationTimeout) {
                                authorization.onAuthorizationTimeout();
                                authorizationTimedOut.countDown();
                            }
                            if (pauseBeforeAuthorization) {
                                beforeAuthorization.countDown();
                                while (true) {
                                    try { releaseAuthorization.await(); break; }
                                    catch (InterruptedException ignored) { /* Simulate slow transport. */ }
                                }
                                if (authorization.tryBeginAuthorization()) {
                                    authorizationStarts.incrementAndGet();
                                }
                            }
                            if (claimScriptExecution) {
                                assertTrue(authorization.tryBeginScriptExecution());
                                scriptExecutionClaimed.countDown();
                            }
                            while (true) {
                                try { backendRelease.await(); break; }
                                catch (InterruptedException ignored) {
                                    backendInterruptions.incrementAndGet();
                                }
                            }
                            if (!authorization.isCurrent()) {
                                throw new FolderOperationException(
                                        FolderOperationFailure.FOLDER_OPERATION_CANCELLED,
                                        "The service revoked this operation");
                            }
                            FolderOperationFailure typedFailure = failure.get();
                            if (typedFailure != null) {
                                throw new FolderOperationException(typedFailure, "Simulated failure");
                            }
                            return outcomes.get();
                        }
                        if ("runFolderScripts".equals(method.getName()) && !root) {
                            appScriptCalls.incrementAndGet();
                            return outcomes.get();
                        }
                        if ("toString".equals(method.getName())) return root ? "root" : "app";
                        throw new AssertionError("Unexpected backend call: " + method.getName());
                    });
        }

        DefaultSyncthingRuntime.FolderScriptReservation reserve() {
            assertTrue(scriptAdmission.tryAdmit());
            DefaultSyncthingRuntime.FolderScriptReservation reservation =
                    runtime.tryReserveFolderScriptOperation();
            assertNotNull(reservation);
            return reservation;
        }

        SyncthingService.RootRunScriptDispatchDecision dispatch(
                DefaultSyncthingRuntime.FolderScriptReservation reservation
        ) {
            return service.tryDispatchRootRunScript(reservation,
                    ConfiguredFolderReference.of("folder-1"), FolderEvent.SYNC_COMPLETE,
                    () -> { scriptAdmission.leave(); finished.countDown(); });
        }

        Runnable beginFailingDisableTransition() {
            AtomicReference<Runnable> drain = new AtomicReference<>();
            ExecutionModeTransitionTestSeam seam = (ExecutionModeTransitionTestSeam)
                    Proxy.newProxyInstance(ExecutionModeTransitionTestSeam.class.getClassLoader(),
                            new Class<?>[]{ExecutionModeTransitionTestSeam.class},
                            (proxy, method, args) -> {
                                switch (method.getName()) {
                                    case "post": ((Runnable) args[0]).run(); return true;
                                    case "executeWorker": drain.set((Runnable) args[1]); return true;
                                    case "durableMode":
                                    case "runtimeMode": return true;
                                    case "closeScriptAdmission": return true;
                                    case "closeBackendAdmission": return false;
                                    case "reopenBackendAdmissionWhenDrained": return null;
                                    default: throw new AssertionError("Unexpected transition effect: "
                                            + method.getName());
                                }
                            });
            service.setExecutionModeTransitionTestSeam(seam);
            service.setExecutionModeTransitionTestState(false, false,
                    SyncthingService.State.DISABLED);
            service.requestExecutionModeTransition(false);
            assertNotNull(drain.get());
            return drain.get();
        }

    }
}
