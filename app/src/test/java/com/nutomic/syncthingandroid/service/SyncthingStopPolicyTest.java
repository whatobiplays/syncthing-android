package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.runtime.ExecutionIdentity;
import com.nutomic.syncthingandroid.runtime.ExecutionOwnershipManager;
import com.nutomic.syncthingandroid.runtime.OwnedExecutionShutdown;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class SyncthingStopPolicyTest {
    private static final ExecutionIdentity RETAINED_OWNER = new ExecutionIdentity(
            51, 12345, "boot-a", "/data/app/lib/libsyncthingnative.so", "run-a"
    );

    @Test
    public void normalStopRetriesOnlyAnExactRetainedErrorOwner() {
        assertTrue(SyncthingStopPolicy.shouldStopForNormalAction(
                SyncthingService.State.ERROR, true
        ));
        assertFalse(SyncthingStopPolicy.shouldStopForNormalAction(
                SyncthingService.State.ERROR, false
        ));
        assertTrue(SyncthingStopPolicy.shouldStopForNormalAction(
                SyncthingService.State.ACTIVE, false
        ));
        assertTrue(SyncthingStopPolicy.shouldStopForNormalAction(
                SyncthingService.State.STARTING, false
        ));
        assertFalse(SyncthingStopPolicy.shouldStopForNormalAction(
                SyncthingService.State.DISABLED, false
        ));
    }

    @Test
    public void normalStopRetriesExactErrorOwnerWithBoundedEscalation() throws Exception {
        List<String> events = new ArrayList<>();
        AtomicInteger observations = new AtomicInteger();
        AtomicInteger signals = new AtomicInteger();
        AtomicInteger shutdownCalls = new AtomicInteger();
        OwnedExecutionShutdown.Outcome[] shutdownOutcome = {null};

        boolean stopped = SyncthingStopPolicy.stopForNormalAction(
                SyncthingService.State.ERROR,
                true,
                () -> {
                    shutdownCalls.incrementAndGet();
                    try {
                        shutdownOutcome[0] = OwnedExecutionShutdown.stop(
                                RETAINED_OWNER,
                                () -> null,
                                new OwnedExecutionShutdown.ExecutionControl() {
                                    @Override
                                    public ExecutionOwnershipManager.Observation observe(
                                            ExecutionIdentity identity
                                    ) {
                                        assertSame(RETAINED_OWNER, identity);
                                        events.add("verify-exact-owner");
                                        observations.incrementAndGet();
                                        return ExecutionOwnershipManager.Observation.OWNED;
                                    }

                                    @Override
                                    public ExecutionOwnershipManager.SignalAttempt signalIfOwned(
                                            ExecutionIdentity identity,
                                            ExecutionOwnershipManager.Signal signal
                                    ) {
                                        assertSame(RETAINED_OWNER, identity);
                                        events.add("signal:" + signal);
                                        signals.incrementAndGet();
                                        return ExecutionOwnershipManager.SignalAttempt.SIGNALED;
                                    }
                                },
                                (identity, timeout, ignored) -> {
                                    events.add("bounded-wait:" + timeout);
                                    return events.stream()
                                            .filter(item -> item.startsWith("bounded-wait"))
                                            .count() == 2;
                                }
                        );
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
        );

        assertTrue(stopped);
        assertEquals(1, shutdownCalls.get());
        assertEquals(OwnedExecutionShutdown.Outcome.EXITED, shutdownOutcome[0]);
        assertEquals(1, signals.get());
        assertEquals(3, observations.get());
        assertTrue(events.contains("verify-exact-owner"));
        assertTrue(events.contains("signal:SIGINT"));
        assertFalse(events.contains("signal:SIGKILL"));
        assertTrue(events.contains(
                "bounded-wait:" + OwnedExecutionShutdown.REST_SHUTDOWN_WAIT_MS
        ));
        assertTrue(events.contains(
                "bounded-wait:" + OwnedExecutionShutdown.SIGINT_WAIT_MS
        ));
    }
}
