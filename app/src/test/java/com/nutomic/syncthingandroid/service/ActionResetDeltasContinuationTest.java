package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        AtomicReference<SyncthingService.State> serviceState =
                new AtomicReference<>(SyncthingService.State.ACTIVE);
        ActionResetDeltasContinuation reset = new ActionResetDeltasContinuation(
                resetLaunches::incrementAndGet,
                () -> stopAfterDeltaReset.set(false)
        );

        // ACTION_RESET_DELTAS has moved the service to DISABLED and queued the reset behind its
        // exact-owned shutdown.
        shutdownRequests.incrementAndGet();
        serviceState.set(SyncthingService.State.DISABLED);

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
        assertEquals(SyncthingService.State.DISABLED, serviceState.get());
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
