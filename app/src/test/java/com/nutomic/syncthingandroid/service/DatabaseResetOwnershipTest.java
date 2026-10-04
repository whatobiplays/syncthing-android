package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.runtime.LifecycleLaunchPermit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class DatabaseResetOwnershipTest {
    @Test
    public void explicitStopSuppressesExternalResetStartupAndReleasesOwnership() {
        DatabaseResetOwnership ownership = new DatabaseResetOwnership();
        AtomicInteger serveLaunches = new AtomicInteger();
        AtomicInteger failureContinuations = new AtomicInteger();
        DatabaseResetOwnership.Operation operation = ownership.reserve(
                serveLaunches::incrementAndGet,
                failureContinuations::incrementAndGet,
                DatabaseResetOwnership.ContinuationPolicy.AUTOMATIC_STARTUP
        );

        assertTrue(ownership.isReserved());
        ownership.suppressAutomaticStartup();
        assertTrue(ownership.complete(operation));
        operation.runAfterReset();

        assertFalse(ownership.isReserved());
        assertEquals(0, serveLaunches.get());
        assertEquals(0, failureContinuations.get());

        DatabaseResetOwnership.Operation failedOperation = ownership.reserve(
                serveLaunches::incrementAndGet,
                failureContinuations::incrementAndGet,
                DatabaseResetOwnership.ContinuationPolicy.AUTOMATIC_STARTUP
        );
        ownership.suppressAutomaticStartup();
        assertTrue(ownership.complete(failedOperation));
        failedOperation.onFailure().run();

        assertEquals(0, serveLaunches.get());
        assertEquals(1, failureContinuations.get());
    }

    @Test
    public void stopDuringImportResetRunsRequiredCompletionButSuppressesServe() {
        DatabaseResetOwnership ownership = new DatabaseResetOwnership();
        PostMutationStartupGate importGate = new PostMutationStartupGate();
        ShutdownStartIntent startIntent = new ShutdownStartIntent();
        startIntent.onRunConditionChanged(true, true);
        importGate.beginOperation();
        AtomicInteger importCompletions = new AtomicInteger();
        AtomicInteger serveLaunches = new AtomicInteger();
        DatabaseResetOwnership.Operation operation = ownership.reserve(
                () -> {
                    importCompletions.incrementAndGet();
                    importGate.completeOperation(startIntent, true, serveLaunches::incrementAndGet);
                },
                null,
                DatabaseResetOwnership.ContinuationPolicy.REQUIRED_OPERATION
        );

        importGate.suppressAutomaticStartup(startIntent);
        ownership.suppressAutomaticStartup();
        assertTrue(ownership.complete(operation));
        operation.runAfterReset();

        assertEquals(1, importCompletions.get());
        assertEquals(0, serveLaunches.get());
        assertFalse(importGate.ownsStartup());
        assertFalse(ownership.isReserved());
    }

    @Test
    public void reservationBlocksRunConditionStartUntilResetCompletes() {
        DatabaseResetOwnership ownership = new DatabaseResetOwnership();
        List<String> events = new ArrayList<>();
        AtomicInteger serveLaunches = new AtomicInteger();
        boolean[] shouldRun = {false};

        DatabaseResetOwnership.Operation operation = ownership.reserve(
                () -> {
                    events.add("reset-completed");
                    if (shouldRun[0]) {
                        serveLaunches.incrementAndGet();
                        events.add("serve-launched");
                    }
                },
                null
        );
        assertTrue(ownership.isReserved());
        events.add("reset-request-reserved");

        shouldRun[0] = true;
        events.add("run-conditions-true-before-worker-admission");
        assertFalse(ownership.canStartLifecycle());
        assertEquals(0, serveLaunches.get());

        events.add("reset-worker-admitted");
        assertTrue(ownership.complete(operation));
        assertFalse(ownership.isReserved());
        operation.afterReset().run();

        assertEquals(1, serveLaunches.get());
        assertEquals(Arrays.asList(
                "reset-request-reserved",
                "run-conditions-true-before-worker-admission",
                "reset-worker-admitted",
                "reset-completed",
                "serve-launched"
        ), events);
    }

    @Test
    public void secondResetCannotReserveAndStaleCompletionCannotReleaseCurrentOwner() {
        DatabaseResetOwnership ownership = new DatabaseResetOwnership();
        DatabaseResetOwnership.Operation first = ownership.reserve(null, null);

        assertNull(ownership.reserve(null, null));
        assertTrue(ownership.complete(first));

        DatabaseResetOwnership.Operation second = ownership.reserve(null, null);
        assertTrue(ownership.isReserved());
        assertFalse(ownership.complete(first));
        assertTrue(ownership.isReserved());
        assertTrue(ownership.complete(second));
        assertFalse(ownership.isReserved());
    }

    @Test
    public void destructionRevokesPendingResetAndReleasesOnlyItsReservation() {
        DatabaseResetOwnership ownership = new DatabaseResetOwnership();
        AtomicInteger afterReset = new AtomicInteger();
        AtomicInteger onFailure = new AtomicInteger();
        DatabaseResetOwnership.Operation operation = ownership.reserve(
                afterReset::incrementAndGet,
                onFailure::incrementAndGet
        );

        operation.checkNotRevoked();
        assertTrue(ownership.isReserved());
        assertTrue(ownership.cancelBeforeLaunch(operation));
        assertFalse(ownership.isReserved());
        assertThrows(LifecycleLaunchPermit.CancelledException.class, operation::checkNotRevoked);
        assertThrows(LifecycleLaunchPermit.CancelledException.class, operation::commitLaunch);
        assertFalse(ownership.complete(operation));
        assertEquals(0, afterReset.get());
        assertEquals(0, onFailure.get());

        DatabaseResetOwnership.Operation newer = ownership.reserve(null, null);
        assertFalse(ownership.cancelBeforeLaunch(operation));
        assertTrue(ownership.isReserved());
        assertTrue(ownership.complete(newer));
    }

    @Test
    public void resetLaunchCommitPreventsDestructionFromReleasingItsReservation() {
        DatabaseResetOwnership ownership = new DatabaseResetOwnership();
        AtomicInteger afterReset = new AtomicInteger();
        AtomicInteger onFailure = new AtomicInteger();
        DatabaseResetOwnership.Operation operation = ownership.reserve(
                afterReset::incrementAndGet,
                onFailure::incrementAndGet
        );

        operation.commitLaunch();

        assertFalse(ownership.cancelBeforeLaunch(operation));
        assertTrue(ownership.isReserved());
        assertTrue(ownership.complete(operation));
        assertFalse(ownership.isReserved());
        assertEquals(0, afterReset.get());
        assertEquals(0, onFailure.get());
    }
}
