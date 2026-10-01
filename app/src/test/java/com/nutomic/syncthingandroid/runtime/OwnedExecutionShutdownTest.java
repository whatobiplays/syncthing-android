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
        public ExecutionOwnershipManager.SignalResult signalIfOwned(
                ExecutionIdentity identity,
                ExecutionOwnershipManager.Signal signal
        ) {
            events.add("verify:" + signal);
            if (observation != ExecutionOwnershipManager.Observation.OWNED) {
                return ExecutionOwnershipManager.SignalResult.NOT_OWNED;
            }
            if (signal == failedSignal) {
                return ExecutionOwnershipManager.SignalResult.SIGNAL_FAILED;
            }
            events.add("signal:" + signal);
            return ExecutionOwnershipManager.SignalResult.SIGNALED;
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
