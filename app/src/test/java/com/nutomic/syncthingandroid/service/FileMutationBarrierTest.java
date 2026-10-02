package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class FileMutationBarrierTest {
    @Test
    public void stoppedStateIsReportedBeforeDeferredRestartRuns() {
        FileMutationBarrier barrier = new FileMutationBarrier();
        List<String> events = new ArrayList<>();
        Runnable restart = () -> events.add("restart");

        barrier.stopCompleted(restart);

        assertTrue(barrier.awaitSafeToMutate());
        events.add("file-mutation");
        Runnable deferred = barrier.takeAfterMutation();
        assertSame(restart, deferred);
        deferred.run();

        assertEquals(Arrays.asList("file-mutation", "restart"), events);
    }

    @Test
    public void stopDuringExportSuppressesDeferredAndPostMutationStartup() {
        FileMutationBarrier barrier = new FileMutationBarrier();
        ShutdownStartIntent startIntent = new ShutdownStartIntent();
        startIntent.onRunConditionChanged(true, true);
        AtomicInteger serveLaunches = new AtomicInteger();
        AtomicInteger fileMutations = new AtomicInteger();
        List<String> events = new ArrayList<>();

        barrier.suppressAutomaticStartup();
        startIntent.clear();
        events.add("stop-during-export");
        // A later Run Conditions notification during this mutation must not bypass the STOP latch.
        startIntent.onRunConditionChanged(true, true);
        fileMutations.incrementAndGet();
        events.add("export-files-completed");
        assertTrue(barrier.clearDeferredStartIfSuppressed(startIntent));
        if (startIntent.consumeIfRequired(true, true, true, true, false)) {
            events.add("serve-from-deferred-start");
            serveLaunches.incrementAndGet();
        }
        if (barrier.shouldAutomaticallyStartAfterMutation(true)) {
            events.add("serve-from-mutation-completion");
            serveLaunches.incrementAndGet();
        }

        assertEquals(1, fileMutations.get());
        assertEquals(0, serveLaunches.get());
        assertEquals(Arrays.asList("stop-during-export", "export-files-completed"), events);
    }

    @Test
    public void stopDuringCertificateShutdownKeepsFileMutationAndReturnsPendingStart() {
        FileMutationBarrier owner = FileMutationBarrier.reserveAsyncOwner(
                null, () -> { }, () -> { }, () -> { }
        );
        ShutdownStartIntent startIntent = new ShutdownStartIntent();
        startIntent.onRunConditionChanged(true, true);
        AtomicInteger fileWrites = new AtomicInteger();
        AtomicInteger verificationLaunches = new AtomicInteger();
        AtomicReference<SyncthingService.HttpsCertReplaceResult> listenerResult =
                new AtomicReference<>();
        List<String> events = new ArrayList<>(Arrays.asList("shutdown-pending", "stop"));

        owner.suppressAutomaticStartup();
        owner.setAsyncMutation(() -> {
            assertTrue(owner.clearDeferredStartIfSuppressed(startIntent));
            fileWrites.incrementAndGet();
            events.add("certificate-files-completed");
            if (owner.shouldAutomaticallyStartAfterMutation(true)) {
                verificationLaunches.incrementAndGet();
                listenerResult.set(SyncthingService.HttpsCertReplaceResult.SUCCESS);
                events.add("verification-started");
            } else {
                listenerResult.set(SyncthingService.HttpsCertReplaceResult.SUCCESS_PENDING_START);
                events.add("listener-pending-start");
            }
        });

        Runnable completion = owner.completeAsyncOwner();
        events.add("shutdown-completed");
        completion.run();

        assertEquals(1, fileWrites.get());
        assertEquals(0, verificationLaunches.get());
        assertEquals(SyncthingService.HttpsCertReplaceResult.SUCCESS_PENDING_START,
                listenerResult.get());
        assertEquals(Arrays.asList(
                "shutdown-pending", "stop", "shutdown-completed",
                "certificate-files-completed", "listener-pending-start"
        ), events);
    }

    @Test
    public void shutdownFailureReleasesWaiterWithFailure() {
        FileMutationBarrier barrier = new FileMutationBarrier();

        barrier.stopFailed();

        assertFalse(barrier.awaitSafeToMutate());
        assertEquals(null, barrier.takeAfterMutation());
    }

    @Test
    public void fileMutationRunsBeforePreviouslyQueuedRestart() {
        List<String> events = new ArrayList<>();

        Runnable completion = FileMutationBarrier.mutationBeforeDeferredCompletion(
                () -> events.add("file-mutation"),
                () -> events.add("queued-restart")
        );
        completion.run();

        assertEquals(Arrays.asList("file-mutation", "queued-restart"), events);
    }

    @Test
    public void concurrentMutationIsRejectedWithoutReplacingCurrentOwner() {
        FileMutationBarrier barrier = new FileMutationBarrier();
        Runnable restart = () -> { };
        AtomicInteger rejectionCallbacks = new AtomicInteger();
        barrier.stopCompleted(restart);

        boolean rejected = FileMutationBarrier.rejectConcurrentMutation(
                barrier, rejectionCallbacks::incrementAndGet
        );

        assertTrue(rejected);
        assertEquals(1, rejectionCallbacks.get());
        assertTrue(barrier.awaitSafeToMutate());
        assertSame(restart, barrier.takeAfterMutation());
    }

    @Test
    public void importIsRejectedWhenActiveShutdownAlreadyOwnsRestartContinuation() {
        List<String> events = new ArrayList<>();
        AtomicInteger importedResets = new AtomicInteger();
        AtomicInteger serveLaunches = new AtomicInteger();
        Runnable queuedRestart = () -> {
            events.add("queued-restart");
            serveLaunches.incrementAndGet();
        };

        boolean rejected = FileMutationBarrier.rejectIfShutdownContinuationPending(
                true, true, () -> events.add("import-rejected")
        );
        if (!rejected) {
            events.add("import-owner-reserved");
            events.add("import-files-mutated");
            importedResets.incrementAndGet();
        }
        queuedRestart.run();

        assertTrue(rejected);
        assertEquals(Arrays.asList("import-rejected", "queued-restart"), events);
        assertEquals(0, importedResets.get());
        assertEquals(1, serveLaunches.get());
    }

    @Test
    public void importIsRejectedWhenActiveShutdownAlreadyOwnsResetContinuation() {
        List<String> events = new ArrayList<>();
        AtomicReference<String> runtimeAdmission = new AtomicReference<>();
        AtomicInteger importedResets = new AtomicInteger();
        AtomicInteger serveLaunches = new AtomicInteger();
        Runnable queuedResetAndServe = () -> {
            if (!runtimeAdmission.compareAndSet(null, "RESET_DATABASE")) {
                throw new AssertionError("Queued reset lost runtime admission");
            }
            events.add("queued-reset-admitted");
            events.add("queued-reset-completed");
            runtimeAdmission.set(null);
            if (!runtimeAdmission.compareAndSet(null, "SERVE")) {
                throw new AssertionError("Queued reset could not hand off to serve");
            }
            serveLaunches.incrementAndGet();
            events.add("queued-serve-launched");
            runtimeAdmission.compareAndSet("SERVE", null);
        };

        boolean rejected = FileMutationBarrier.rejectIfShutdownContinuationPending(
                true, true, () -> events.add("import-rejected")
        );
        if (!rejected) importedResets.incrementAndGet();
        queuedResetAndServe.run();

        assertTrue(rejected);
        assertEquals(Arrays.asList(
                "import-rejected",
                "queued-reset-admitted",
                "queued-reset-completed",
                "queued-serve-launched"
        ), events);
        assertEquals(0, importedResets.get());
        assertEquals(1, serveLaunches.get());
        assertNull(runtimeAdmission.get());
    }

    @Test
    public void certificateMutationFailsBeforeJoiningShutdownWithQueuedReset() {
        List<String> events = new ArrayList<>();
        AtomicReference<String> runtimeAdmission = new AtomicReference<>();
        AtomicInteger certificateWrites = new AtomicInteger();
        AtomicInteger listenerFailures = new AtomicInteger();
        AtomicInteger serveLaunches = new AtomicInteger();
        Runnable queuedResetAndServe = () -> {
            if (!runtimeAdmission.compareAndSet(null, "RESET_DATABASE")) {
                throw new AssertionError("Queued reset lost runtime admission");
            }
            events.add("queued-reset-admitted");
            events.add("queued-reset-completed");
            runtimeAdmission.set(null);
            if (!runtimeAdmission.compareAndSet(null, "SERVE")) {
                throw new AssertionError("Queued reset could not hand off to serve");
            }
            serveLaunches.incrementAndGet();
            events.add("queued-serve-launched");
            runtimeAdmission.compareAndSet("SERVE", null);
        };

        boolean rejected = FileMutationBarrier.rejectIfShutdownContinuationPending(
                true, true, () -> {
                    listenerFailures.incrementAndGet();
                    events.add("certificate-failed");
                }
        );
        if (!rejected) certificateWrites.incrementAndGet();
        queuedResetAndServe.run();

        assertTrue(rejected);
        assertEquals(Arrays.asList(
                "certificate-failed",
                "queued-reset-admitted",
                "queued-reset-completed",
                "queued-serve-launched"
        ), events);
        assertEquals(0, certificateWrites.get());
        assertEquals(1, listenerFailures.get());
        assertEquals(1, serveLaunches.get());
        assertNull(runtimeAdmission.get());
    }

    @Test
    public void shutdownWithoutQueuedContinuationAllowsMutationAdmission() {
        AtomicInteger rejections = new AtomicInteger();

        boolean rejected = FileMutationBarrier.rejectIfShutdownContinuationPending(
                true, false, rejections::incrementAndGet
        );

        assertFalse(rejected);
        assertEquals(0, rejections.get());
    }

    @Test
    public void importLifecycleOwnerRejectsSyncAndAsyncFileMutationAdmission() {
        PostMutationStartupGate gate = new PostMutationStartupGate();
        gate.beginOperation();
        AtomicInteger syncRejections = new AtomicInteger();
        AtomicInteger asyncRejections = new AtomicInteger();

        assertTrue(gate.rejectNewMutation(syncRejections::incrementAndGet));
        assertTrue(gate.rejectNewMutation(asyncRejections::incrementAndGet));
        assertEquals(1, syncRejections.get());
        assertEquals(1, asyncRejections.get());
        assertTrue(gate.ownsStartup());
    }

    @Test
    public void failedImportCompletionReleasesGateWithoutStartingService() {
        PostMutationStartupGate gate = new PostMutationStartupGate();
        ShutdownStartIntent startIntent = new ShutdownStartIntent();
        AtomicInteger serveLaunches = new AtomicInteger();
        gate.beginOperation();
        startIntent.onRunConditionChanged(true, true);

        Runnable afterImport = null;
        try {
            throw new IllegalStateException("Import aborted before assigning its continuation");
        } catch (IllegalStateException expected) {
            assertEquals("Import aborted before assigning its continuation",
                    expected.getMessage());
        } finally {
            SyncthingService.importCompletionOrFailure(
                    afterImport, true, () -> gate.cancel(startIntent)
            ).run();
        }

        assertFalse(gate.ownsStartup());
        assertFalse(startIntent.consumeIfRequired(true, true, true, true, false));
        assertEquals(0, serveLaunches.get());
        assertFalse(gate.rejectNewMutation(
                () -> { throw new AssertionError("still wedged"); }
        ));
    }

    @Test
    public void partiallyFailedImportDoesNotStartServiceToReleaseGate() {
        PostMutationStartupGate gate = new PostMutationStartupGate();
        ShutdownStartIntent intent = new ShutdownStartIntent();
        AtomicInteger launches = new AtomicInteger();
        gate.beginOperation();

        SyncthingService.importCompletionOrFailure(
                () -> gate.completeOperation(intent, true, launches::incrementAndGet),
                false,
                () -> gate.cancel(intent)
        ).run();

        assertFalse(gate.ownsStartup());
        assertEquals(0, launches.get());
    }

    @Test
    public void abandonedWaitKeepsOwnerUntilShutdownCompletes() {
        FileMutationBarrier barrier = new FileMutationBarrier();
        barrier.abandon();
        assertTrue(barrier.isAbandoned());
        barrier.stopCompleted(null);
        assertTrue(barrier.isAbandoned());
    }

    @Test
    public void interruptedFileWaitCompletesShutdownAndRestartsOnceWhenAllowed() {
        FileMutationBarrier barrier = new FileMutationBarrier();
        ShutdownStartIntent intent = new ShutdownStartIntent();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();

        Thread.currentThread().interrupt();
        try {
            assertFalse(barrier.awaitSafeToMutate());
        } finally {
            Thread.interrupted();
        }
        barrier.abandon();
        assertTrue(barrier.isAbandoned());
        assertEquals(0, writes.get());

        barrier.stopCompleted(null);
        assertNull(SyncthingService.finishAbandonedFileMutation(barrier, intent, true, false));
        if (intent.consumeIfRequired(true, true, true, true, false)) {
            launches.incrementAndGet();
        }
        assertFalse(intent.consumeIfRequired(true, true, true, true, false));

        assertEquals(0, writes.get());
        assertEquals(1, launches.get());
    }

    @Test
    public void explicitStopDuringInterruptedFileWaitSuppressesShutdownRestart() {
        FileMutationBarrier barrier = new FileMutationBarrier();
        AtomicInteger launches = new AtomicInteger();
        ShutdownStartIntent intent = new ShutdownStartIntent();
        intent.onRunConditionChanged(true, true);

        barrier.abandon();
        barrier.suppressAutomaticStartup();
        barrier.stopCompleted(null);
        assertNull(SyncthingService.finishAbandonedFileMutation(barrier, intent, true, false));
        if (intent.consumeIfRequired(true, true, true, true, false)) launches.incrementAndGet();

        assertEquals(0, launches.get());
        assertFalse(intent.consumeIfRequired(true, true, true, true, false));
    }

    @Test
    public void abandonedMutationDuringDestructionDropsShutdownContinuation() {
        FileMutationBarrier barrier = new FileMutationBarrier();
        ShutdownStartIntent intent = new ShutdownStartIntent();
        AtomicInteger continuations = new AtomicInteger();
        barrier.abandon();
        barrier.stopCompleted(continuations::incrementAndGet);

        assertNull(SyncthingService.finishAbandonedFileMutation(barrier, intent, true, true));

        assertEquals(0, continuations.get());
    }

    @Test
    public void asyncCertificateMutationOwnsBarrierBeforePendingShutdownCompletes() {
        AtomicReference<FileMutationBarrier> serviceOwner = new AtomicReference<>();
        AtomicInteger shutdownFailures = new AtomicInteger();
        AtomicInteger rejectedMutations = new AtomicInteger();
        AtomicInteger mutations = new AtomicInteger();
        List<String> events = new ArrayList<>();

        FileMutationBarrier owner = FileMutationBarrier.reserveAsyncOwner(
                serviceOwner.get(),
                rejectedMutations::incrementAndGet,
                shutdownFailures::incrementAndGet,
                () -> { }
        );
        assertNotNull(owner);
        SyncthingService.beginReservedAsyncMutation(
                owner,
                () -> serviceOwner.set(owner),
                () -> {
                    events.add("shutdown-pending");
                    assertSame(owner, serviceOwner.get());
                    assertNull(FileMutationBarrier.reserveAsyncOwner(
                            serviceOwner.get(), rejectedMutations::incrementAndGet,
                            shutdownFailures::incrementAndGet, () -> { }
                    ));
                }
        );
        assertEquals(1, rejectedMutations.get());
        assertEquals(0, mutations.get());
        assertEquals(0, shutdownFailures.get());

        owner.setAsyncMutation(() -> {
            mutations.incrementAndGet();
            events.add("certificate-files-mutated");
            serviceOwner.compareAndSet(owner, null);
            events.add("verification-handoff");
        });
        Runnable completion = owner.completeAsyncOwner();
        events.add("shutdown-completed");
        completion.run();

        assertEquals(1, mutations.get());
        assertEquals(Arrays.asList(
                "shutdown-pending",
                "shutdown-completed",
                "certificate-files-mutated",
                "verification-handoff"
        ), events);
        assertEquals(0, shutdownFailures.get());
        assertEquals(null, serviceOwner.get());
    }

    @Test
    public void asyncShutdownFailureInvokesOwnerFailureExactlyOnce() {
        AtomicInteger failures = new AtomicInteger();
        FileMutationBarrier owner = FileMutationBarrier.reserveAsyncOwner(
                null, () -> { }, failures::incrementAndGet, () -> { }
        );

        owner.stopFailed();
        owner.stopFailed();

        assertEquals(1, failures.get());
        assertFalse(owner.awaitSafeToMutate());
    }

    @Test
    public void asyncReservationRejectsAnExistingOwnerExactlyOnce() {
        AtomicInteger rejections = new AtomicInteger();
        FileMutationBarrier owner = FileMutationBarrier.reserveAsyncOwner(
                null, rejections::incrementAndGet, () -> { }, () -> { }
        );

        FileMutationBarrier secondOwner = FileMutationBarrier.reserveAsyncOwner(
                owner, rejections::incrementAndGet, () -> { }, () -> { }
        );

        assertNull(secondOwner);
        assertEquals(1, rejections.get());
        assertTrue(owner.isAsyncOwner());
    }

    @Test
    public void asyncMutationFailsBeforeWritesWhenContinuationArrivesDuringShutdown() {
        AtomicInteger shutdownFailures = new AtomicInteger();
        AtomicInteger continuationConflicts = new AtomicInteger();
        AtomicInteger fileMutations = new AtomicInteger();
        List<String> events = new ArrayList<>();
        FileMutationBarrier owner = FileMutationBarrier.reserveAsyncOwner(
                null,
                () -> { },
                shutdownFailures::incrementAndGet,
                continuationConflicts::incrementAndGet
        );
        owner.setAsyncMutation(() -> {
            fileMutations.incrementAndGet();
            events.add("certificate-files-mutated");
        });
        Runnable queuedReset = () -> {
            events.add("queued-reset-admitted");
            events.add("queued-reset-completed");
        };

        boolean rejected = FileMutationBarrier.rejectMutationForDeferredCompletion(
                owner, queuedReset
        );
        if (rejected) queuedReset.run();

        assertTrue(rejected);
        assertFalse(owner.awaitSafeToMutate());
        assertEquals(0, fileMutations.get());
        assertEquals(0, shutdownFailures.get());
        assertEquals(1, continuationConflicts.get());
        assertEquals(Arrays.asList("queued-reset-admitted", "queued-reset-completed"), events);
    }

    @Test
    public void importWaiterFailsBeforeMutationWhenContinuationArrivesDuringShutdown() {
        FileMutationBarrier barrier = new FileMutationBarrier();
        List<String> events = new ArrayList<>();
        AtomicInteger importMutations = new AtomicInteger();
        Runnable queuedRestart = () -> events.add("queued-restart");

        boolean rejected = FileMutationBarrier.rejectMutationForDeferredCompletion(
                barrier, queuedRestart
        );
        if (rejected) queuedRestart.run();
        else if (barrier.awaitSafeToMutate()) importMutations.incrementAndGet();

        assertTrue(rejected);
        assertFalse(barrier.awaitSafeToMutate());
        assertEquals(0, importMutations.get());
        assertEquals(Arrays.asList("queued-restart"), events);
    }

    @Test
    public void unidentifiedLiveExecutionRejectsMutationWithoutStartingShutdown() {
        FileMutationBarrier barrier = new FileMutationBarrier();
        AtomicInteger processSignals = new AtomicInteger();
        AtomicInteger mutations = new AtomicInteger();

        boolean rejected = FileMutationBarrier.rejectIfNoSafeShutdownPath(
                true, false, false, barrier::stopFailed
        );
        if (!rejected) {
            processSignals.incrementAndGet();
            mutations.incrementAndGet();
        }

        assertTrue(rejected);
        assertEquals(0, processSignals.get());
        assertEquals(0, mutations.get());
        assertFalse(barrier.awaitSafeToMutate());
    }

    @Test
    public void deadWorkerHandleWithoutExitProofRejectsImmediately() {
        FileMutationBarrier barrier = new FileMutationBarrier();
        AtomicInteger signals = new AtomicInteger();
        Thread lifecycleThread = new Thread();
        assertFalse(lifecycleThread.isAlive());

        boolean rejected = FileMutationBarrier.rejectIfNoSafeShutdownPath(
                lifecycleThread != null, false, false, barrier::stopFailed
        );
        if (!rejected) signals.incrementAndGet();

        assertTrue(rejected);
        assertEquals(0, signals.get());
        assertFalse(barrier.awaitSafeToMutate());
    }

    @Test
    public void exactIdentityOrObservedExitAllowsBoundedShutdownPath() {
        AtomicInteger rejections = new AtomicInteger();

        assertFalse(FileMutationBarrier.rejectIfNoSafeShutdownPath(
                true, true, false, rejections::incrementAndGet
        ));
        assertFalse(FileMutationBarrier.rejectIfNoSafeShutdownPath(
                true, false, true, rejections::incrementAndGet
        ));
        assertFalse(FileMutationBarrier.rejectIfNoSafeShutdownPath(
                false, false, false, rejections::incrementAndGet
        ));
        assertEquals(0, rejections.get());
    }

    @Test
    public void interruptedMutationWaitReturnsFailureInsteadOfContinuingToWait() {
        FileMutationBarrier barrier = new FileMutationBarrier();
        barrier.stopCompleted(null);
        Thread.currentThread().interrupt();

        try {
            assertFalse(barrier.awaitSafeToMutate());
        } finally {
            Thread.interrupted();
        }
    }
}
