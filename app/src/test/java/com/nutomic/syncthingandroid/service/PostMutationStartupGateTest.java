package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class PostMutationStartupGateTest {
    @Test
    public void importResetTakesPriorityOverDeferredRunConditionStart() {
        PostMutationStartupGate gate = new PostMutationStartupGate();
        ShutdownStartIntent startIntent = pendingStartIntent();
        FileMutationBarrier barrier = new FileMutationBarrier();
        List<String> events = new ArrayList<>();
        AtomicInteger serveLaunches = new AtomicInteger();
        AtomicInteger externalResetRequests = new AtomicInteger();
        AtomicReference<String> runtimeAdmission = new AtomicReference<>();

        events.add("import-owner-reserved");
        events.add("shutdown-pending");
        events.add("run-conditions-true");
        barrier.stopCompleted(null);
        assertTrue(barrier.awaitSafeToMutate());
        events.add("proven-stopped");
        events.add("import-files-and-preferences");
        events.add("reset-required");

        gate.beginOperation();
        assertTrue(gate.ownsStartup());
        assertFalse(SyncthingResetPolicy.runExternalResetIfUnowned(
                false, gate.ownsStartup(), externalResetRequests::incrementAndGet
        ));
        assertEquals(0, externalResetRequests.get());
        Runnable deferred = barrier.takeAfterMutation();
        if (deferred != null) deferred.run();
        boolean startedBeforeReset = gate.consumeDeferredStart(
                startIntent,
                true,
                true,
                true,
                true,
                false
        ) && tryAcquireRuntimeAdmission(runtimeAdmission, "SERVE");
        assertFalse(startedBeforeReset);
        assertEquals(0, serveLaunches.get());

        events.add("reset-requested");
        if (!tryAcquireRuntimeAdmission(runtimeAdmission, "RESET_DATABASE")) {
            throw new AssertionError("Reset did not acquire runtime admission first");
        }
        events.add("reset-runtime-admission-acquired");
        events.add("reset-completed");
        assertTrue(runtimeAdmission.compareAndSet("RESET_DATABASE", null));
        gate.completeOperation(startIntent, true, () -> {
            if (!tryAcquireRuntimeAdmission(runtimeAdmission, "SERVE")) {
                throw new AssertionError("Serve could not acquire released runtime admission");
            }
            serveLaunches.incrementAndGet();
            events.add("serve-launched");
        });

        assertEquals(1, serveLaunches.get());
        assertEquals(Arrays.asList(
                "import-owner-reserved",
                "shutdown-pending",
                "run-conditions-true",
                "proven-stopped",
                "import-files-and-preferences",
                "reset-required",
                "reset-requested",
                "reset-runtime-admission-acquired",
                "reset-completed",
                "serve-launched"
        ), events);
    }

    @Test
    public void importResetUsesRunConditionsAtResetCompletion() {
        PostMutationStartupGate gate = new PostMutationStartupGate();
        ShutdownStartIntent startIntent = pendingStartIntent();
        AtomicInteger serveLaunches = new AtomicInteger();
        gate.beginOperation();

        startIntent.onRunConditionChanged(false, true);
        gate.completeOperation(startIntent, false, serveLaunches::incrementAndGet);

        assertEquals(0, serveLaunches.get());
        assertFalse(gate.ownsStartup());
        assertFalse(startIntent.consumeIfRequired(true, true, true, true, false));
    }

    @Test
    public void ordinaryExportMayConsumeDeferredStartAfterMutation() {
        PostMutationStartupGate gate = new PostMutationStartupGate();
        ShutdownStartIntent startIntent = pendingStartIntent();
        FileMutationBarrier barrier = new FileMutationBarrier();
        List<String> events = new ArrayList<>();

        barrier.stopCompleted(() -> events.add("earlier-shutdown-completion"));
        events.add("export-files-mutated");
        Runnable deferred = barrier.takeAfterMutation();
        if (deferred != null) deferred.run();
        assertTrue(barrier.shouldAutomaticallyStartAfterMutation(true));
        if (gate.consumeDeferredStart(startIntent, true, true, true, true, false)) {
            events.add("serve-launched");
        }

        assertEquals(Arrays.asList(
                "export-files-mutated",
                "earlier-shutdown-completion",
                "serve-launched"
        ), events);
    }

    @Test
    public void cancelReleasesOperationStartupOwnership() {
        PostMutationStartupGate gate = new PostMutationStartupGate();
        ShutdownStartIntent startIntent = pendingStartIntent();
        gate.beginOperation();

        gate.cancel(startIntent);

        assertFalse(gate.ownsStartup());
        assertFalse(startIntent.consumeIfRequired(true, true, true, true, false));
    }

    @Test
    public void normalStopSuppressesImportStartupWithoutAbortingReset() {
        assertStopSuppressesImportStartupWithoutAbortingReset("normal-stop");
    }

    @Test
    public void crashedNativeStopSuppressesImportStartupWithoutAbortingReset() {
        assertStopSuppressesImportStartupWithoutAbortingReset("crashed-native-stop");
    }

    @Test
    public void stopBeforeImportGateBeginsStillSuppressesTheImportRestart() {
        PostMutationStartupGate gate = new PostMutationStartupGate();
        FileMutationBarrier barrier = new FileMutationBarrier();
        ShutdownStartIntent startIntent = pendingStartIntent();
        AtomicInteger resetRuns = new AtomicInteger();
        AtomicInteger serveLaunches = new AtomicInteger();
        List<String> events = new ArrayList<>();

        // STOP arrives during file mutation, before finishFileMutation creates the gate.
        barrier.suppressAutomaticStartup();
        events.add("stop-during-import-files");
        startIntent.onRunConditionChanged(true, true);
        assertTrue(barrier.clearDeferredStartIfSuppressed(startIntent));
        gate.beginOperation(!barrier.automaticStartupSuppressed());
        resetRuns.incrementAndGet();
        events.add("import-reset-started");
        events.add("import-reset-completed");
        gate.completeOperation(startIntent, true, serveLaunches::incrementAndGet);

        assertEquals(1, resetRuns.get());
        assertEquals(0, serveLaunches.get());
        assertEquals(Arrays.asList(
                "stop-during-import-files", "import-reset-started", "import-reset-completed"
        ), events);
    }

    private static ShutdownStartIntent pendingStartIntent() {
        ShutdownStartIntent intent = new ShutdownStartIntent();
        intent.onRunConditionChanged(true, true);
        return intent;
    }

    private static boolean tryAcquireRuntimeAdmission(
            AtomicReference<String> runtimeAdmission,
            String operation
    ) {
        return runtimeAdmission.compareAndSet(null, operation);
    }

    private static void assertStopSuppressesImportStartupWithoutAbortingReset(String stopAction) {
        PostMutationStartupGate gate = new PostMutationStartupGate();
        ShutdownStartIntent startIntent = pendingStartIntent();
        AtomicReference<String> runtimeAdmission = new AtomicReference<>("RESET_DATABASE");
        AtomicInteger serveLaunches = new AtomicInteger();
        List<String> events = new ArrayList<>(Arrays.asList("reset-in-progress"));
        gate.beginOperation();
        assertTrue(gate.ownsStartup());

        gate.suppressAutomaticStartup(startIntent);
        events.add(stopAction);
        assertTrue("STOP must suppress startup without releasing import ownership",
                gate.ownsStartup());
        AtomicInteger externalDatabaseResets = new AtomicInteger();
        boolean[] deltaResetFollowUp = {false};
        assertFalse(SyncthingResetPolicy.runExternalResetIfUnowned(
                false, gate.ownsStartup(), externalDatabaseResets::incrementAndGet
        ));
        assertFalse(SyncthingResetPolicy.runExternalResetIfUnowned(
                false,
                gate.ownsStartup(),
                () -> deltaResetFollowUp[0] = true
        ));
        assertEquals(0, externalDatabaseResets.get());
        assertFalse(deltaResetFollowUp[0]);
        assertEquals("RESET_DATABASE", runtimeAdmission.get());
        events.add("reset-completed");
        assertTrue(runtimeAdmission.compareAndSet("RESET_DATABASE", null));

        gate.completeOperation(startIntent, true, () -> {
            serveLaunches.incrementAndGet();
            events.add("serve-launched");
        });

        assertEquals(0, serveLaunches.get());
        assertFalse(gate.ownsStartup());
        assertFalse(startIntent.consumeIfRequired(true, true, true, true, false));
        assertEquals(Arrays.asList("reset-in-progress", stopAction, "reset-completed"), events);
        assertEquals(null, runtimeAdmission.get());
    }
}
