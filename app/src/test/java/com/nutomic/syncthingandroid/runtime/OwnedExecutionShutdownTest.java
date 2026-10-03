package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
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
                () -> { control.events.add("rest"); return null; },
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
                () -> { control.events.add("rest"); return null; },
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
                () -> { control.events.add("rest"); return null; },
                control,
                waiter
        );

        assertEquals(OwnedExecutionShutdown.Outcome.EXITED, result);
        assertTrue(control.events.contains("signal:SIGINT"));
        assertFalse(control.events.contains("signal:SIGKILL"));
        assertEquals(Arrays.asList(10000L, 5000L), waiter.timeouts);
    }

    @Test
    public void shutdownRequestIsCanceledAndDrainedBeforeReplacementLaunch() throws Exception {
        List<String> events = new ArrayList<>();
        RecordingControl control = new RecordingControl();
        RecordingShutdownRequest request = new RecordingShutdownRequest(events);

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY,
                () -> {
                    events.add("rest-request-issued");
                    return request;
                },
                control,
                (identity, timeout, ignored) -> {
                    events.add("old-execution-exited-before-retry");
                    control.observation = ExecutionOwnershipManager.Observation.EXITED;
                    return true;
                }
        );

        assertEquals(OwnedExecutionShutdown.Outcome.EXITED, result);
        OwnedExecutionShutdown.requireNoUnquiescedRestShutdownRequests();
        events.add("replacement-launched");
        assertFalse(request.canRetryAgainstReplacement());
        assertEquals(0, request.replacementAttempts);
        assertEquals(Arrays.asList(
                "rest-request-issued",
                "shutdown-request-sent",
                "old-execution-exited-before-retry",
                "shutdown-request-await:5000",
                "shutdown-request-canceled",
                "shutdown-request-await:5000",
                "shutdown-request-terminal",
                "replacement-launched"
        ), events);
    }

    @Test
    public void shutdownAdmissionBlockIsInstalledBeforeRequestCanBeDelivered()
            throws Exception {
        RecordingControl control = new RecordingControl();
        RecordingShutdownRequest request = new RecordingShutdownRequest(new ArrayList<>());

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY,
                () -> request,
                control,
                (identity, timeout, ignored) -> true
        );
        assertEquals(OwnedExecutionShutdown.Outcome.EXITED, result);
        assertEquals(1, request.sendCount);
        assertFalse(OwnedExecutionShutdown.hasUnquiescedRestShutdownRequests());
    }

    @Test
    public void processStartPermitPreventsShutdownRequestFromBecomingDeliverable()
            throws Exception {
        RecordingControl control = new RecordingControl();
        RecordingShutdownRequest request = new RecordingShutdownRequest(new ArrayList<>());

        try (OwnedExecutionShutdown.LaunchPermit ignored =
                     OwnedExecutionShutdown.acquireLaunchPermit(false)) {
            OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                    IDENTITY,
                    () -> request,
                    control,
                    (identity, timeout, ignoredControl) -> {
                        control.observation = ExecutionOwnershipManager.Observation.EXITED;
                        return true;
                    }
            );

            assertEquals(OwnedExecutionShutdown.Outcome.EXITED, result);
            assertEquals(0, request.sendCount);
            assertFalse(OwnedExecutionShutdown.hasUnquiescedRestShutdownRequests());
        }
    }

    @Test
    public void requestProvenNotDeliverableReleasesBlockerAndContinuesShutdown()
            throws Exception {
        RecordingControl control = new RecordingControl();
        RecordingShutdownRequest request = new RecordingShutdownRequest(new ArrayList<>()) {
            @Override
            public boolean send() {
                assertTrue(OwnedExecutionShutdown.hasUnquiescedRestShutdownRequests());
                return false;
            }
        };

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY,
                () -> request,
                control,
                (identity, timeout, ignored) -> {
                    assertFalse(OwnedExecutionShutdown.hasUnquiescedRestShutdownRequests());
                    return true;
                }
        );

        assertEquals(OwnedExecutionShutdown.Outcome.EXITED, result);
        assertFalse(OwnedExecutionShutdown.hasUnquiescedRestShutdownRequests());
    }

    @Test
    public void sendErrorKeepsLeaseAndStillCompletesBoundedSignalEscalation()
            throws Exception {
        RecordingControl control = new RecordingControl();
        RecordingShutdownRequest request = new RecordingShutdownRequest(new ArrayList<>()) {
            @Override
            public boolean send() {
                throw new AssertionError("queue admission outcome is uncertain");
            }
        };
        request.neverTerminal = true;

        try {
            OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                    IDENTITY,
                    () -> request,
                    control,
                    (identity, timeout, ignored) -> false
            );

            assertEquals(
                    OwnedExecutionShutdown.Outcome.REST_SHUTDOWN_NOT_QUIESCENT,
                    result
            );
            assertTrue(control.events.contains("signal:SIGINT"));
            assertTrue(control.events.contains("signal:SIGKILL"));
            assertTrue(OwnedExecutionShutdown.hasUnquiescedRestShutdownRequests());
        } finally {
            request.cancel();
            request.neverTerminal = false;
            assertFalse(OwnedExecutionShutdown.hasUnquiescedRestShutdownRequests());
        }
    }

    @Test
    public void nonQuiescentShutdownRequestPreventsReplacementAuthorization()
            throws Exception {
        RecordingControl control = new RecordingControl();
        RecordingShutdownRequest request = new RecordingShutdownRequest(new ArrayList<>());
        request.neverTerminal = true;

        try {
            OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                    IDENTITY,
                    () -> request,
                    control,
                    (identity, timeout, ignored) -> true
            );

            assertEquals(
                    OwnedExecutionShutdown.Outcome.REST_SHUTDOWN_NOT_QUIESCENT,
                    result
            );
            assertTrue(OwnedExecutionShutdown.hasUnquiescedRestShutdownRequests());
            assertThrows(
                    RecoveryShutdownRequestPendingException.class,
                    () -> {
                        OwnedExecutionShutdown.requireNoUnquiescedRestShutdownRequests();
                        request.canRetryAgainstReplacement();
                    }
            );
            assertEquals(0, request.replacementAttempts);
        } finally {
            request.cancel();
            request.neverTerminal = false;
            assertFalse(OwnedExecutionShutdown.hasUnquiescedRestShutdownRequests());
        }
        OwnedExecutionShutdown.requireNoUnquiescedRestShutdownRequests();
    }

    @Test
    public void terminalObservationDoesNotHoldCoordinatorWhileCallingRequest()
            throws Exception {
        RecordingControl control = new RecordingControl();
        java.util.concurrent.CountDownLatch pollEntered =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releasePoll =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch terminalCallbackFinished =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean blockTerminalPoll =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean terminalObserved =
                new java.util.concurrent.atomic.AtomicBoolean();
        RecordingShutdownRequest request = new RecordingShutdownRequest(new ArrayList<>()) {
            @Override
            public boolean awaitTerminal(long timeoutMillis) {
                if (timeoutMillis == 0 && blockTerminalPoll.get()) {
                    pollEntered.countDown();
                    try {
                        releasePoll.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    return terminalObserved.get();
                }
                return timeoutMillis == 0 && terminalObserved.get();
            }
        };
        Runnable finishTerminal = () -> {
            terminalObserved.set(true);
            request.notifyTerminal();
        };

        try {
            OwnedExecutionShutdown.Outcome outcome = OwnedExecutionShutdown.stop(
                    IDENTITY,
                    () -> request,
                    control,
                    (identity, timeout, ignored) -> true
            );
            assertEquals(
                    OwnedExecutionShutdown.Outcome.REST_SHUTDOWN_NOT_QUIESCENT,
                    outcome
            );
            blockTerminalPoll.set(true);
            Thread observer = new Thread(OwnedExecutionShutdown::hasUnquiescedRestShutdownRequests);
            observer.start();
            assertTrue(pollEntered.await(1, java.util.concurrent.TimeUnit.SECONDS));

            Thread terminal = new Thread(() -> {
                finishTerminal.run();
                terminalCallbackFinished.countDown();
            });
            terminal.start();
            boolean callbackWasNotBlocked = terminalCallbackFinished.await(
                    1, java.util.concurrent.TimeUnit.SECONDS
            );
            releasePoll.countDown();
            observer.join(1_000);
            terminal.join(1_000);

            assertTrue(callbackWasNotBlocked);
            assertFalse(observer.isAlive());
            assertFalse(terminal.isAlive());
            assertFalse(OwnedExecutionShutdown.hasUnquiescedRestShutdownRequests());
        } finally {
            releasePoll.countDown();
            request.cancel();
            finishTerminal.run();
            OwnedExecutionShutdown.hasUnquiescedRestShutdownRequests();
        }
    }

    @Test
    public void nonOwnedExecutionNeverReceivesRestOrProcessSignals() throws Exception {
        RecordingControl control = new RecordingControl();
        control.observation = ExecutionOwnershipManager.Observation.NOT_OWNED;
        RecordingWaiter waiter = new RecordingWaiter(control, -1);

        OwnedExecutionShutdown.Outcome result = OwnedExecutionShutdown.stop(
                IDENTITY,
                () -> { control.events.add("rest"); return null; },
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
                () -> { control.events.add("rest"); return null; },
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
                () -> { control.events.add("rest"); return null; },
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
                IDENTITY, () -> { control.events.add("rest"); return null; }, control, waiter
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
                () -> { control.events.add("rest"); return null; },
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
                IDENTITY, () -> { control.events.add("rest"); return null; }, control, waiter
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
                IDENTITY, () -> {
                    events.add("rest");
                    return null;
                }, control, waiter
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

    private static class RecordingShutdownRequest
            implements OwnedExecutionShutdown.RestShutdownRequest {
        private final List<String> events;
        private boolean cancelled;
        private boolean terminal;
        private boolean neverTerminal;
        private int awaitCount;
        private int replacementAttempts;
        private int sendCount;
        private Runnable terminalListener;

        private RecordingShutdownRequest(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean send() {
            assertTrue(OwnedExecutionShutdown.hasUnquiescedRestShutdownRequests());
            sendCount++;
            events.add("shutdown-request-sent");
            return true;
        }

        @Override
        public void setTerminalListener(Runnable listener) {
            terminalListener = listener;
        }

        @Override
        public void cancel() {
            cancelled = true;
            events.add("shutdown-request-canceled");
        }

        @Override
        public boolean awaitTerminal(long timeoutMillis) {
            if (timeoutMillis == 0) {
                if (!neverTerminal && cancelled) {
                    terminal = true;
                    notifyTerminal();
                }
                return terminal;
            }
            events.add("shutdown-request-await:" + timeoutMillis);
            awaitCount++;
            if (neverTerminal) return false;
            if (cancelled || awaitCount > 1) {
                terminal = true;
                events.add("shutdown-request-terminal");
                notifyTerminal();
                return true;
            }
            return false;
        }

        private boolean canRetryAgainstReplacement() {
            if (terminal) return false;
            replacementAttempts++;
            return true;
        }

        private void notifyTerminal() {
            if (terminalListener != null) terminalListener.run();
        }
    }
}
