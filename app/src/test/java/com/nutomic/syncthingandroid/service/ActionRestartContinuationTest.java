package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class ActionRestartContinuationTest {

    @Test
    public void stopDuringActionRestartShutdownPreventsReplacementAfterOwnedExit() {
        AtomicInteger serveLaunches = new AtomicInteger();
        AtomicInteger shutdownCalls = new AtomicInteger();
        AtomicReference<SyncthingService.State> serviceState =
                new AtomicReference<>(SyncthingService.State.ACTIVE);
        ActionRestartContinuation restart = new ActionRestartContinuation(
                serveLaunches::incrementAndGet
        );

        // ACTION_RESTART moves the service to INIT and leaves its continuation pending.
        serviceState.set(SyncthingService.State.INIT);

        assertEquals(SyncthingService.State.INIT, serviceState.get());
        assertTrue(restart.cancel()); // ACTION_STOP while the old owned process is stopping.
        assertTrue(SyncthingStopPolicy.stopForNormalAction(
                serviceState.get(), false, true, () -> {
                    shutdownCalls.incrementAndGet();
                    serviceState.set(SyncthingService.State.DISABLED);
                }
        ));
        assertFalse(restart.complete()); // Old execution completion keeps STOP's cancellation.

        assertEquals(1, shutdownCalls.get());
        assertEquals(SyncthingService.State.DISABLED, serviceState.get());
        assertEquals(0, serveLaunches.get());
    }

    @Test
    public void stopAfterRestartContinuationCompletesIsHarmless() {
        AtomicInteger serveLaunches = new AtomicInteger();
        ActionRestartContinuation restart = new ActionRestartContinuation(
                serveLaunches::incrementAndGet
        );

        assertTrue(restart.complete());
        assertFalse(restart.cancel());
        assertEquals(1, serveLaunches.get());
    }
}
