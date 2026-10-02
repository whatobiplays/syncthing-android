package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class OwnedExecutionShutdownTest {
    private static final ExecutionIdentity IDENTITY = new ExecutionIdentity(
            41, 9001, "boot-a", "/data/app/lib/libsyncthingnative.so", "run-a"
    );

    @Test
    public void escalatesInOrderAndReverifiesImmediatelyBeforeEachSignal() throws Exception {
        RecordingControl control = new RecordingControl();
        RecordingWaiter waiter = new RecordingWaiter(control, -1);

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY,
                () -> control.events.add("rest"),
                control,
                waiter
        );

        assertEquals(OwnedExecutionShutdown.Outcome.EXIT_NOT_PROVEN, result);
        assertEquals(Arrays.asList(
                "observe", "rest", "wait:10000", "observe", "observe",
                "verify:SIGINT", "signal:SIGINT", "wait:5000", "observe",
                "observe", "verify:SIGKILL", "signal:SIGKILL", "wait:5000", "observe"
        ), control.events);
        assertEquals(Arrays.asList(10000L, 5000L, 5000L), waiter.timeouts);
    }

    @Test
    public void provenExitDuringRestWaitPreventsEscalation() throws Exception {
        RecordingControl control = new RecordingControl();
        RecordingWaiter waiter = new RecordingWaiter(control, 0);

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY,
                () -> control.events.add("rest"),
                control,
                waiter
        );

        assertEquals(OwnedExecutionShutdown.Outcome.EXITED, result);
        assertFalse(control.events.contains("signal:SIGINT"));
        assertFalse(control.events.contains("signal:SIGKILL"));
        assertEquals(Arrays.asList(10000L), waiter.timeouts);
    }

    @Test
    public void provenExitAfterSigintPreventsSigkill() throws Exception {
        RecordingControl control = new RecordingControl();
        RecordingWaiter waiter = new RecordingWaiter(control, 1);

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY,
                () -> control.events.add("rest"),
                control,
                waiter
        );

        assertEquals(OwnedExecutionShutdown.Outcome.EXITED, result);
        assertTrue(control.events.contains("signal:SIGINT"));
        assertFalse(control.events.contains("signal:SIGKILL"));
        assertEquals(Arrays.asList(10000L, 5000L), waiter.timeouts);
    }

    @Test
    public void nonOwnedExecutionNeverReceivesRestOrProcessSignals() throws Exception {
        RecordingControl control = new RecordingControl();
        control.observation = ExecutionOwnershipManager.Observation.NOT_OWNED;
        RecordingWaiter waiter = new RecordingWaiter(control, -1);

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY,
                () -> control.events.add("rest"),
                control,
                waiter
        );

        assertEquals(OwnedExecutionShutdown.Outcome.OWNERSHIP_LOST, result);
        assertEquals(Arrays.asList("observe"), control.events);
        assertTrue(waiter.timeouts.isEmpty());
    }

    @Test
    public void sigintTransportFailureStillWaitsAndEscalatesWhileOwnershipRemainsExact()
            throws Exception {
        RecordingControl control = new RecordingControl();
        control.failedSignal = ExecutionOwnershipManager.Signal.SIGINT;
        RecordingWaiter waiter = new RecordingWaiter(control, -1);

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY,
                () -> control.events.add("rest"),
                control,
                waiter
        );

        assertEquals(OwnedExecutionShutdown.Outcome.EXIT_NOT_PROVEN, result);
        assertEquals(Arrays.asList(
                "observe", "rest", "wait:10000", "observe", "observe",
                "verify:SIGINT", "observe", "wait:5000", "observe", "observe",
                "verify:SIGKILL", "signal:SIGKILL", "wait:5000", "observe"
        ), control.events);
        assertEquals(Arrays.asList(10000L, 5000L, 5000L), waiter.timeouts);
    }

    @Test
    public void waiterProvenExitShortCircuitsStaleOwnedObservation() throws Exception {
        RecordingControl control = new RecordingControl();
        List<Long> waits = new ArrayList<>();

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY,
                () -> control.events.add("rest"),
                control,
                (identity, timeout, ignored) -> {
                    waits.add(timeout);
                    return true;
                }
        );

        assertEquals(OwnedExecutionShutdown.Outcome.EXITED, result);
        assertEquals(Arrays.asList(10000L), waits);
        assertFalse(control.events.contains("signal:SIGINT"));
        assertFalse(control.events.contains("signal:SIGKILL"));
    }

    @Test
    public void sigkillTransportFailureIsReturnedAfterItsBoundedWait() throws Exception {
        RecordingControl control = new RecordingControl();
        control.failedSignal = ExecutionOwnershipManager.Signal.SIGKILL;
        RecordingWaiter waiter = new RecordingWaiter(control, -1);

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY, () -> control.events.add("rest"), control, waiter
        );

        assertEquals(OwnedExecutionShutdown.Outcome.SIGNAL_FAILED, result);
        assertTrue(control.events.contains("verify:SIGKILL"));
        assertFalse(control.events.contains("signal:SIGKILL"));
        assertEquals(Arrays.asList(10000L, 5000L, 5000L), waiter.timeouts);
    }

    @Test
    public void throwingRestRequestStillUsesBoundedObservationAndEscalation() throws Exception {
        RecordingControl control = new RecordingControl();
        RecordingWaiter waiter = new RecordingWaiter(control, -1);

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY,
                () -> { throw new IllegalStateException("REST unavailable"); },
                control,
                waiter
        );

        assertEquals(OwnedExecutionShutdown.Outcome.EXIT_NOT_PROVEN, result);
        assertTrue(control.events.contains("signal:SIGINT"));
        assertTrue(control.events.contains("signal:SIGKILL"));
        assertEquals(Arrays.asList(10000L, 5000L, 5000L), waiter.timeouts);
    }

    @Test
    public void unknownOwnershipNeverReceivesRestOrProcessSignals() throws Exception {
        RecordingControl control = new RecordingControl();
        control.observation = ExecutionOwnershipManager.Observation.UNKNOWN;

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY,
                () -> control.events.add("rest"),
                control,
                new RecordingWaiter(control, -1)
        );

        assertEquals(OwnedExecutionShutdown.Outcome.OWNERSHIP_LOST, result);
        assertEquals(Arrays.asList("observe"), control.events);
    }

    @Test
    public void ownershipLostDuringWaitPreventsEverySignal() throws Exception {
        RecordingControl control = new RecordingControl();
        List<Long> waits = new ArrayList<>();
        OwnedExecutionShutdown.Waiter waiter = (identity, timeout, ignored) -> {
            waits.add(timeout);
            control.observation = ExecutionOwnershipManager.Observation.NOT_OWNED;
            return false;
        };

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY, () -> control.events.add("rest"), control, waiter
        );

        assertEquals(OwnedExecutionShutdown.Outcome.OWNERSHIP_LOST, result);
        assertFalse(control.events.contains("signal:SIGINT"));
        assertFalse(control.events.contains("signal:SIGKILL"));
        assertEquals(Arrays.asList(10000L), waits);
    }

    @Test
    public void failedSignalVerificationCanRetryOnlyAfterFreshOwnedObservation()
            throws Exception {
        List<String> events = new ArrayList<>();
        List<ExecutionOwnershipManager.Signal> signals = new ArrayList<>();
        final int[] signalAttempts = {0};
        OwnedExecutionShutdown.ExecutionControl control =
                new OwnedExecutionShutdown.ExecutionControl() {
                    @Override
                    public ExecutionOwnershipManager.Observation observe(
                            ExecutionIdentity identity
                    ) {
                        events.add("observe");
                        return ExecutionOwnershipManager.Observation.OWNED;
                    }

                    @Override
                    public ExecutionOwnershipManager.SignalAttempt signalIfOwned(
                            ExecutionIdentity identity,
                            ExecutionOwnershipManager.Signal signal
                    ) {
                        signalAttempts[0]++;
                        events.add("attempt:" + signal);
                        if (signalAttempts[0] == 1) {
                            return ExecutionOwnershipManager.SignalAttempt.NOT_OWNED;
                        }
                        signals.add(signal);
                        return ExecutionOwnershipManager.SignalAttempt.SIGNALED;
                    }
                };
        List<Long> waits = new ArrayList<>();
        final int[] waitCount = {0};
        OwnedExecutionShutdown.Waiter waiter = (identity, timeout, ignored) -> {
            waits.add(timeout);
            waitCount[0]++;
            return waitCount[0] == 3;
        };

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY, () -> events.add("rest"), control, waiter
        );

        assertEquals(OwnedExecutionShutdown.Outcome.EXITED, result);
        assertEquals(Arrays.asList(10000L, 5000L, 5000L), waits);
        assertEquals(Arrays.asList(
                ExecutionOwnershipManager.Signal.SIGKILL
        ), signals);
        int sigintAttempt = events.indexOf("attempt:SIGINT");
        int sigkillAttempt = events.indexOf("attempt:SIGKILL");
        assertTrue(sigintAttempt >= 0);
        assertTrue(sigkillAttempt > sigintAttempt);
        assertTrue(events.subList(sigintAttempt + 1, sigkillAttempt).contains("observe"));
    }

    private static final class RecordingControl
            implements OwnedExecutionShutdown.ExecutionControl {
        private final List<String> events = new ArrayList<>();
        private ExecutionOwnershipManager.Observation observation =
                ExecutionOwnershipManager.Observation.OWNED;
        private ExecutionOwnershipManager.Signal failedSignal;

        @Override
        public ExecutionOwnershipManager.Observation observe(ExecutionIdentity identity) {
            events.add("observe");
            return observation;
        }

        @Override
        public ExecutionOwnershipManager.SignalAttempt signalIfOwned(
                ExecutionIdentity identity,
                ExecutionOwnershipManager.Signal signal
        ) {
            events.add("verify:" + signal);
            if (observation != ExecutionOwnershipManager.Observation.OWNED) {
                return ExecutionOwnershipManager.SignalAttempt.NOT_OWNED;
            }
            if (signal == failedSignal) {
                return ExecutionOwnershipManager.SignalAttempt.SIGNAL_FAILED;
            }
            events.add("signal:" + signal);
            return ExecutionOwnershipManager.SignalAttempt.SIGNALED;
        }
    }

    private static final class RecordingWaiter implements OwnedExecutionShutdown.Waiter {
        private final RecordingControl control;
        private final int exitAtWait;
        private final List<Long> timeouts = new ArrayList<>();

        private RecordingWaiter(RecordingControl control, int exitAtWait) {
            this.control = control;
            this.exitAtWait = exitAtWait;
        }

        @Override
        public boolean awaitExit(
                ExecutionIdentity identity,
                long timeoutMillis,
                OwnedExecutionShutdown.ExecutionControl ignored
        ) {
            timeouts.add(timeoutMillis);
            control.events.add("wait:" + timeoutMillis);
            if (timeouts.size() - 1 == exitAtWait) {
                control.observation = ExecutionOwnershipManager.Observation.EXITED;
                return true;
            }
            return false;
        }
    }
}
