package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class StartupReadinessTest {
    @Test
    public void activeRequiresOwnershipEndpointAndCompletedConfiguration() {
        FakeScheduler scheduler = new FakeScheduler();
        StartupReadiness readiness = new StartupReadiness(scheduler, () -> { });

        assertFalse(readiness.markOwnershipVerified());
        assertFalse(readiness.markEndpointReady());
        assertFalse(readiness.isReady());
        assertTrue(readiness.markConfigurationInitialized());
        assertTrue(readiness.isReady());
        assertTrue(scheduler.task.cancelled);
    }

    @Test
    public void deadlineCoversAllStartupStagesAndRejectsLateCallbacks() {
        FakeScheduler scheduler = new FakeScheduler();
        List<String> events = new ArrayList<>();
        StartupReadiness readiness = new StartupReadiness(
                scheduler,
                () -> events.add("timeout")
        );

        assertEquals(60_000L, scheduler.delayMillis);
        readiness.markOwnershipVerified();
        readiness.markEndpointReady();
        scheduler.task.fire();

        assertEquals(StartupReadiness.State.TIMED_OUT, readiness.state());
        assertEquals("timeout", events.get(0));
        assertFalse(readiness.markConfigurationInitialized());
        assertFalse(readiness.isReady());
    }

    @Test
    public void cancellationDetachesTheScheduledTimeout() {
        FakeScheduler scheduler = new FakeScheduler();
        List<String> events = new ArrayList<>();
        StartupReadiness readiness = new StartupReadiness(
                scheduler,
                () -> events.add("timeout")
        );

        readiness.cancel();
        scheduler.task.fire();

        assertEquals(StartupReadiness.State.CANCELLED, readiness.state());
        assertTrue(events.isEmpty());
    }

    private static final class FakeScheduler implements StartupReadiness.Scheduler {
        private long delayMillis;
        private FakeTask task;

        @Override
        public StartupReadiness.ScheduledTask schedule(long delayMillis, Runnable task) {
            this.delayMillis = delayMillis;
            this.task = new FakeTask(task);
            return this.task;
        }
    }

    private static final class FakeTask implements StartupReadiness.ScheduledTask {
        private final Runnable callback;
        private boolean cancelled;

        private FakeTask(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private void fire() {
            if (!cancelled) callback.run();
        }
    }
}
