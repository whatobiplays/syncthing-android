package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.runtime.ExecutionIdentity;
import com.nutomic.syncthingandroid.runtime.ExecutionOwnershipManager;
import com.nutomic.syncthingandroid.runtime.OwnedExecutionShutdown;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class ActionResetDeltasContinuationTest {

    @Test
    public void normalStopCancelsQueuedDeltaResetBeforeOwnedShutdownCompletes()
            throws Exception {
        AtomicInteger resetLaunches = new AtomicInteger();
        AtomicInteger shutdownRequests = new AtomicInteger();
        AtomicInteger unrelatedCompletions = new AtomicInteger();
        AtomicBoolean stopAfterDeltaReset = new AtomicBoolean(true);
        ActionResetDeltasContinuation reset = new ActionResetDeltasContinuation(
                resetLaunches::incrementAndGet,
                () -> stopAfterDeltaReset.set(false)
        );

        // ACTION_RESET_DELTAS has moved the service to DISABLED and queued the reset behind its
        // exact-owned shutdown.
        shutdownRequests.incrementAndGet();

        // Normal ACTION_STOP cancels only this queued continuation; shutdown remains in progress.
        assertTrue(reset.cancel());
        assertFalse(reset.cancel());
        assertFalse(stopAfterDeltaReset.get());
        assertEquals(1, shutdownRequests.get());
        assertEquals(OwnedExecutionShutdown.Outcome.EXITED, stopOldOwnedExecution());
        Runnable shutdownCompletion = () -> {
            reset.complete();
            unrelatedCompletions.incrementAndGet();
        };
        shutdownCompletion.run();

        assertEquals(0, resetLaunches.get());
        assertEquals(1, unrelatedCompletions.get());
    }

    @Test
    public void secondDeltaResetIsRejectedWhileFirstWaitsForStartingShutdownAndStopCancelsIt()
            throws Exception {
        AtomicInteger resetLaunches = new AtomicInteger();
        AtomicInteger acceptedAfterCancellation = new AtomicInteger();
        AtomicBoolean stopAfterDeltaReset = new AtomicBoolean(false);
        AtomicReference<ActionResetDeltasContinuation> pendingReset = new AtomicReference<>();
        AtomicReference<Runnable> delayedShutdownCompletion = new AtomicReference<>();
        StartingShutdownDeferral startingShutdown = new StartingShutdownDeferral();

        boolean firstAccepted = SyncthingResetPolicy.runExternalDeltaResetIfUnowned(
                false, false, false, false, pendingReset.get() != null,
                () -> {
                    stopAfterDeltaReset.set(true);
                    ActionResetDeltasContinuation first = new ActionResetDeltasContinuation(
                            resetLaunches::incrementAndGet,
                            () -> stopAfterDeltaReset.set(false)
                    );
                    pendingReset.set(first);
                    startingShutdown.defer();
                    delayedShutdownCompletion.set(() -> {
                        startingShutdown.transferToShutdown();
                        if (pendingReset.get() == first) pendingReset.set(null);
                        first.complete();
                    });
                }
        );

        assertTrue(firstAccepted);
        assertTrue(startingShutdown.isPending());
        ActionResetDeltasContinuation first = pendingReset.get();
        assertTrue(first != null);

        boolean secondAccepted = SyncthingResetPolicy.runExternalDeltaResetIfUnowned(
                false, false, false, false, pendingReset.get() != null,
                () -> resetLaunches.incrementAndGet()
        );

        assertFalse(secondAccepted);
        assertSame(first, pendingReset.get());
        assertTrue(stopAfterDeltaReset.get());

        // Normal STOP cancels the sole queued request; the exact-owned shutdown still completes.
        assertTrue(first.cancel());
        assertFalse(first.cancel());
        assertFalse(stopAfterDeltaReset.get());
        assertSame(first, pendingReset.get());
        assertTrue(pendingReset.compareAndSet(first, null));
        assertNull(pendingReset.get());
        assertEquals(OwnedExecutionShutdown.Outcome.EXITED, stopOldOwnedExecution());
        delayedShutdownCompletion.get().run();

        assertNull(pendingReset.get());
        assertEquals(0, resetLaunches.get());
        assertFalse(startingShutdown.isPending());
        assertTrue(SyncthingResetPolicy.runExternalDeltaResetIfUnowned(
                false, false, false, false, pendingReset.get() != null,
                acceptedAfterCancellation::incrementAndGet
        ));
        assertEquals(1, acceptedAfterCancellation.get());
    }

    @Test
    public void queuedDeltaResetRunsOnceAfterOwnedShutdownWhenStopDoesNotArrive()
            throws Exception {
        AtomicInteger resetLaunches = new AtomicInteger();
        ActionResetDeltasContinuation reset = new ActionResetDeltasContinuation(
                resetLaunches::incrementAndGet,
                () -> { }
        );

        assertEquals(OwnedExecutionShutdown.Outcome.EXITED, stopOldOwnedExecution());
        assertTrue(reset.complete());
        assertFalse(reset.complete());

        assertEquals(1, resetLaunches.get());
    }

    private static OwnedExecutionShutdown.Outcome stopOldOwnedExecution()
            throws InterruptedException {
        ExecutionIdentity identity = new ExecutionIdentity(
                101,
                202,
                "boot-id",
                "/app/libsyncthingnative.so",
                "reset-deltas-run"
        );
        return OwnedExecutionShutdown.stop(
                identity,
                () -> null,
                new OwnedExecutionShutdown.ExecutionControl() {
                    @Override
                    public ExecutionOwnershipManager.Observation observe(
                            ExecutionIdentity observed
                    ) {
                        assertEquals(identity, observed);
                        return ExecutionOwnershipManager.Observation.OWNED;
                    }

                    @Override
                    public ExecutionOwnershipManager.SignalAttempt signalIfOwned(
                            ExecutionIdentity signaled,
                            ExecutionOwnershipManager.Signal signal
                    ) {
                        assertEquals(identity, signaled);
                        return ExecutionOwnershipManager.SignalAttempt.NOT_OWNED;
                    }
                },
                (observed, timeoutMillis, control) -> {
                    assertEquals(identity, observed);
                    return true;
                }
        );
    }
}
