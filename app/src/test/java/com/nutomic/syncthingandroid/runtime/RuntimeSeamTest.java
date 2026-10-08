package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class RuntimeSeamTest {

    @Test
    public void commandsOwnTheSupportedSyncthingArgv() {
        String binary = "/data/app/lib/libsyncthingnative.so";

        assertArrayEquals(
                new String[]{binary, "serve", "--no-browser"},
                SyncthingCommand.SERVE.argv(binary)
        );
        assertArrayEquals(
                new String[]{binary, "device-id"},
                SyncthingCommand.DEVICE_ID.argv(binary)
        );
        assertArrayEquals(
                new String[]{binary, "generate"},
                SyncthingCommand.GENERATE.argv(binary)
        );
        assertArrayEquals(
                new String[]{binary, "debug", "reset-database"},
                SyncthingCommand.RESET_DATABASE.argv(binary)
        );
        assertArrayEquals(
                new String[]{binary, "serve", "--debug-reset-delta-idxs"},
                SyncthingCommand.RESET_DELTAS.argv(binary)
        );
    }

    @Test
    public void environmentPreservesTheNormalModeContract() {
        SyncthingEnvironment environment = SyncthingEnvironment.builder()
                .home("/data/user/0/app/syncthing")
                .syncthingHome("/data/user/0/app/files")
                .trace("fs,connections")
                .monitored()
                .noUpgrade()
                .versionExtra("Syncthing")
                .sqliteTemporaryDirectory("/data/user/0/app/cache")
                .fallbackGatewayIpv4("192.0.2.1")
                .torProxy()
                .gogc(75)
                .customVariables(Collections.singletonMap("CUSTOM", "value"))
                .build();

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("HOME", "/data/user/0/app/syncthing");
        expected.put("STHOMEDIR", "/data/user/0/app/files");
        expected.put("STTRACE", "fs,connections");
        expected.put("STMONITORED", "1");
        expected.put("STNOUPGRADE", "1");
        expected.put("STVERSIONEXTRA", "Syncthing");
        expected.put("SQLITE_TMPDIR", "/data/user/0/app/cache");
        expected.put("FALLBACK_NET_GATEWAY_IPV4", "192.0.2.1");
        expected.put("all_proxy", "socks5://localhost:9050");
        expected.put("ALL_PROXY_NO_FALLBACK", "1");
        expected.put("GOGC", "75");
        expected.put("CUSTOM", "value");

        assertEquals(expected, environment.values());
    }

    @Test
    public void environmentSupportsNormalModeProxyVariants() {
        SyncthingEnvironment environment = SyncthingEnvironment.builder()
                .home("/home")
                .syncthingHome("/state")
                .trace("")
                .monitored()
                .noUpgrade()
                .versionExtra("app")
                .sqliteTemporaryDirectory("/tmp")
                .socksProxy("socks5://127.0.0.1:9050")
                .httpProxy("http://127.0.0.1:8080")
                .gogc(100)
                .build();

        assertEquals("socks5://127.0.0.1:9050", environment.values().get("all_proxy"));
        assertEquals("http://127.0.0.1:8080", environment.values().get("http_proxy"));
        assertEquals("http://127.0.0.1:8080", environment.values().get("https_proxy"));
    }

    @Test
    public void runtimeAdmitsOnlyOneSyncthingInvocationAndDelegatesEnvironment() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        SyncthingEnvironment environment = SyncthingEnvironment.builder()
                .home("/home")
                .syncthingHome("/state")
                .trace("")
                .monitored()
                .noUpgrade()
                .versionExtra("app")
                .sqliteTemporaryDirectory("/tmp")
                .gogc(100)
                .build();

        SyncthingExecution first = runtime.start(SyncthingCommand.SERVE, environment);

        assertSame(SyncthingCommand.SERVE, backend.command);
        assertSame(environment, backend.environment);
        assertThrows(
                ExecutionAdmissionException.class,
                () -> runtime.start(SyncthingCommand.DEVICE_ID, environment)
        );

        first.await();
        SyncthingExecution second = runtime.start(SyncthingCommand.DEVICE_ID, environment);
        second.await();
    }

    @Test
    public void recoveryShutdownLeaseBlocksReplacementUntilRequestIsTerminal() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        GatedExecution oldProcess = new GatedExecution();
        backend.execution = oldProcess;
        backend.observation = ExecutionOwnershipManager.Observation.OWNED;
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        SyncthingExecution oldExecution = runtime.start(
                SyncthingCommand.SERVE,
                normalModeEnvironment()
        );
        List<String> events = new ArrayList<>();
        AtomicBoolean requestTerminal = new AtomicBoolean();
        AtomicInteger terminalWaits = new AtomicInteger();
        Runnable[] terminalListener = {null};
        OwnedExecutionShutdown.RestShutdownRequest preparedRequest =
                new OwnedExecutionShutdown.RestShutdownRequest() {
                    @Override
                    public void setTerminalListener(Runnable listener) {
                        terminalListener[0] = listener;
                    }

                    @Override
                    public boolean send() {
                        assertTrue(OwnedExecutionShutdown.hasUnquiescedRestShutdownRequests());
                        events.add("blocked-before-request-delivery");
                        return true;
                    }

                    @Override
                    public void cancel() {
                        events.add("request-canceled");
                    }

                    @Override
                    public boolean awaitTerminal(long timeoutMillis) {
                        if (timeoutMillis == 0) return requestTerminal.get();
                        if (terminalWaits.incrementAndGet() == 1) {
                            assertThrows(
                                    RecoveryShutdownRequestPendingException.class,
                                    () -> runtime.start(
                                            SyncthingCommand.DEVICE_ID,
                                            normalModeEnvironment()
                                    )
                            );
                            assertEquals(1, backend.startCount);
                            events.add("replacement-rejected-before-terminal");
                            return false;
                        }
                        requestTerminal.set(true);
                        if (terminalListener[0] != null) terminalListener[0].run();
                        events.add("request-terminal");
                        return true;
                    }
                };

        OwnedExecutionShutdown.Outcome outcome = OwnedExecutionShutdown.stop(
                new ExecutionIdentity(
                        41, 9001, "boot-a", "/data/app/lib/libsyncthingnative.so", "run-a"
                ),
                () -> preparedRequest,
                runtime,
                (identity, timeout, ignored) -> {
                    oldProcess.exit(0);
                    assertEquals(0, oldExecution.await());
                    events.add("old-admission-released");
                    return true;
                }
        );

        assertEquals(OwnedExecutionShutdown.Outcome.EXITED, outcome);
        assertTrue(requestTerminal.get());
        backend.execution = new ImmediateExecution();
        SyncthingExecution replacement = runtime.start(
                SyncthingCommand.DEVICE_ID,
                normalModeEnvironment()
        );
        assertEquals(0, replacement.await());
        assertEquals(2, backend.startCount);
        assertEquals(Arrays.asList(
                "blocked-before-request-delivery",
                "old-admission-released",
                "replacement-rejected-before-terminal",
                "request-canceled",
                "request-terminal"
        ), events);
    }

    @Test
    public void destructionWinningAtOneShotLaunchBoundaryPreventsResetProcessCreation()
            throws Exception {
        RecordingBackend backend = new RecordingBackend();
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        LifecycleLaunchPermit permit = new LifecycleLaunchPermit();
        CountDownLatch finalCheckReached = new CountDownLatch(1);
        CountDownLatch allowFinalCheck = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            Future<String> launch = worker.submit(() -> {
                try {
                    runtime.startOneShot(
                            SyncthingCommand.RESET_DATABASE,
                            normalModeEnvironment(),
                            null,
                            () -> {
                                finalCheckReached.countDown();
                                awaitLatch(allowFinalCheck);
                                permit.commitLaunch();
                            }
                    );
                    return "started";
                } catch (LifecycleLaunchPermit.CancelledException expected) {
                    return "cancelled";
                }
            });

            assertTrue(finalCheckReached.await(5, TimeUnit.SECONDS));
            assertEquals(LifecycleLaunchPermit.State.REVOKED, permit.revoke());
            allowFinalCheck.countDown();

            assertEquals("cancelled", launch.get(5, TimeUnit.SECONDS));
            assertEquals(0, backend.startCount);
            assertNull(backend.command);
        } finally {
            allowFinalCheck.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void committedResetCreationIsObservedAndRecoveredBeforeShutdownScan()
            throws Exception {
        ExecutionIdentity identity = RecoveryAssessmentFixture.ownedIdentity();
        GatedProcessCreationBackend backend = new GatedProcessCreationBackend(identity);
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        LifecycleLaunchPermit permit = new LifecycleLaunchPermit();
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<SyncthingExecution> launch = workers.submit(() -> runtime.startOneShot(
                    SyncthingCommand.RESET_DATABASE,
                    normalModeEnvironment(),
                    null,
                    permit::commitLaunch
            ));

            assertTrue(backend.startEntered.await(5, TimeUnit.SECONDS));
            assertEquals(LifecycleLaunchPermit.State.LAUNCH_COMMITTED, permit.revoke());

            CountDownLatch recoveryRequested = new CountDownLatch(1);
            Future<ExecutionOwnershipManager.RecoveryAssessment> recovery = workers.submit(() -> {
                recoveryRequested.countDown();
                return runtime.recoverExecutions();
            });
            assertTrue(recoveryRequested.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> recovery.get(5, TimeUnit.SECONDS));
            assertEquals("The pending process creation must not be classified as absent",
                    2, backend.recoveryChecks);

            backend.allowProcessCreation.countDown();
            SyncthingExecution execution = launch.get(5, TimeUnit.SECONDS);
            ExecutionOwnershipManager.RecoveryAssessment recovered = recovery.get(
                    5, TimeUnit.SECONDS
            );
            assertTrue(recovered.ownedExecution().sameProcess(identity));
            assertEquals(1, backend.startCount);
            assertSame(SyncthingCommand.RESET_DATABASE, backend.command);

            List<ExecutionOwnershipManager.Signal> signals = new ArrayList<>();
            OwnedExecutionShutdown.Outcome outcome = OwnedExecutionShutdown.stop(
                    identity,
                    () -> null,
                    new OwnedExecutionShutdown.ExecutionControl() {
                        @Override
                        public ExecutionOwnershipManager.Observation observe(
                                ExecutionIdentity observed
                        ) {
                            assertTrue(identity.sameProcess(observed));
                            return backend.processExited
                                    ? ExecutionOwnershipManager.Observation.EXITED
                                    : ExecutionOwnershipManager.Observation.OWNED;
                        }

                        @Override
                        public ExecutionOwnershipManager.SignalAttempt signalIfOwned(
                                ExecutionIdentity observed,
                                ExecutionOwnershipManager.Signal signal
                        ) {
                            assertTrue(identity.matches(observed));
                            signals.add(signal);
                            backend.markExited();
                            return ExecutionOwnershipManager.SignalAttempt.SIGNALED;
                        }
                    },
                    (observed, timeout, ignored) -> {
                        assertTrue(identity.sameProcess(observed));
                        return timeout != OwnedExecutionShutdown.REST_SHUTDOWN_WAIT_MS;
                    }
            );

            assertEquals(OwnedExecutionShutdown.Outcome.EXITED, outcome);
            assertEquals(Collections.singletonList(ExecutionOwnershipManager.Signal.SIGINT),
                    signals);
            assertEquals(0, execution.await());
            assertTrue(runtime.recoverExecutions().mayLaunch());
        } finally {
            backend.allowProcessCreation.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    public void stalePrePermitRecoveryCannotAuthorizeLaunchAfterCompetingExecutionStarts()
            throws Exception {
        AtomicBoolean processPresent = new AtomicBoolean();
        ExecutionIdentity competingIdentity = RecoveryAssessmentFixture.ownedIdentity();
        RacingRecoveryBackend runtimeABackend = new RacingRecoveryBackend(
                processPresent,
                competingIdentity
        );
        RacingRecoveryBackend runtimeBBackend = new RacingRecoveryBackend(
                processPresent,
                competingIdentity
        );
        CountDownLatch initialRecoveryComplete = new CountDownLatch(1);
        CountDownLatch allowPermitAcquisition = new CountDownLatch(1);
        DefaultSyncthingRuntime runtimeA = new DefaultSyncthingRuntime(
                runtimeABackend,
                waitForPendingRequests -> {
                    initialRecoveryComplete.countDown();
                    awaitLatch(allowPermitAcquisition);
                    return OwnedExecutionShutdown.acquireLaunchPermit(waitForPendingRequests);
                }
        );
        DefaultSyncthingRuntime runtimeB = new DefaultSyncthingRuntime(runtimeBBackend);
        AtomicInteger shutdownHandlerCalls = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            Future<ExecutionRecoveryException> launchA = worker.submit(() -> {
                try {
                    SyncthingExecution unexpected = runtimeA.startServiceLifecycle(
                            SyncthingCommand.SERVE,
                            normalModeEnvironment(),
                            identity -> {
                                shutdownHandlerCalls.incrementAndGet();
                                return true;
                            },
                            null
                    );
                    unexpected.await();
                    return null;
                } catch (ExecutionRecoveryException blocked) {
                    return blocked;
                }
            });

            assertTrue("Runtime A did not finish its initial launchable classification",
                    initialRecoveryComplete.await(5, TimeUnit.SECONDS));
            assertEquals(1, runtimeABackend.recoveryChecks);

            SyncthingExecution competingExecution = runtimeB.startServiceLifecycle(
                    SyncthingCommand.SERVE,
                    normalModeEnvironment()
            );
            assertEquals(1, runtimeBBackend.startCount);
            assertTrue(processPresent.get());

            allowPermitAcquisition.countDown();
            ExecutionRecoveryException blocked = launchA.get(5, TimeUnit.SECONDS);

            assertNotNull("The stale initial assessment must not authorize a replacement", blocked);
            assertEquals(
                    ExecutionOwnershipManager.Classification.OWNED_EXECUTION,
                    blocked.assessment().classification()
            );
            assertEquals(0, runtimeABackend.startCount);
            assertEquals(0, shutdownHandlerCalls.get());
            assertEquals(2, runtimeABackend.recoveryChecks);

            assertEquals(0, competingExecution.await());
            processPresent.set(false);

            SyncthingExecution retry = runtimeA.startServiceLifecycle(
                    SyncthingCommand.SERVE,
                    normalModeEnvironment()
            );
            assertEquals(0, retry.await());
            assertEquals(1, runtimeABackend.startCount);
        } finally {
            allowPermitAcquisition.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void revokedLifecycleLaunchWinsOverCompetingExecutionFoundUnderPermit()
            throws Exception {
        AtomicBoolean processPresent = new AtomicBoolean();
        ExecutionIdentity competingIdentity = RecoveryAssessmentFixture.ownedIdentity();
        RacingRecoveryBackend runtimeABackend = new RacingRecoveryBackend(
                processPresent,
                competingIdentity
        );
        RacingRecoveryBackend runtimeBBackend = new RacingRecoveryBackend(
                processPresent,
                competingIdentity
        );
        CountDownLatch initialRecoveryComplete = new CountDownLatch(1);
        CountDownLatch allowPermitAcquisition = new CountDownLatch(1);
        LifecycleLaunchPermit startupPermit = new LifecycleLaunchPermit();
        AtomicInteger finalLaunchChecks = new AtomicInteger();
        DefaultSyncthingRuntime.LifecycleLaunchCheck lifecycleLaunchCheck =
                new DefaultSyncthingRuntime.LifecycleLaunchCheck() {
                    @Override
                    public void commitRecoveryBlocked() {
                        startupPermit.commitRecoveryBlocked();
                    }

                    @Override
                    public void check() {
                        finalLaunchChecks.incrementAndGet();
                        startupPermit.commitLaunch();
                    }
                };
        DefaultSyncthingRuntime runtimeA = new DefaultSyncthingRuntime(
                runtimeABackend,
                waitForPendingRequests -> {
                    initialRecoveryComplete.countDown();
                    awaitLatch(allowPermitAcquisition);
                    return OwnedExecutionShutdown.acquireLaunchPermit(waitForPendingRequests);
                }
        );
        DefaultSyncthingRuntime runtimeB = new DefaultSyncthingRuntime(runtimeBBackend);
        AtomicInteger shutdownHandlerCalls = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            Future<RuntimeException> launchA = worker.submit(() -> {
                try {
                    SyncthingExecution unexpected = runtimeA.startServiceLifecycle(
                            SyncthingCommand.SERVE,
                            normalModeEnvironment(),
                            identity -> {
                                shutdownHandlerCalls.incrementAndGet();
                                return true;
                            },
                            lifecycleLaunchCheck
                    );
                    unexpected.await();
                    return null;
                } catch (RuntimeException outcome) {
                    return outcome;
                }
            });

            assertTrue("Runtime A did not finish its initial launchable classification",
                    initialRecoveryComplete.await(5, TimeUnit.SECONDS));
            assertEquals(1, runtimeABackend.recoveryChecks);
            assertEquals(LifecycleLaunchPermit.State.REVOKED, startupPermit.revoke());

            SyncthingExecution competingExecution = runtimeB.startServiceLifecycle(
                    SyncthingCommand.SERVE,
                    normalModeEnvironment()
            );
            assertEquals(1, runtimeBBackend.startCount);
            assertTrue(processPresent.get());

            allowPermitAcquisition.countDown();
            RuntimeException outcome = launchA.get(5, TimeUnit.SECONDS);

            assertTrue("Revocation must take precedence over final competing-owner evidence: "
                            + outcome,
                    outcome instanceof LifecycleLaunchPermit.CancelledException);
            assertEquals(0, runtimeABackend.startCount);
            assertEquals(0, shutdownHandlerCalls.get());
            assertEquals(0, finalLaunchChecks.get());
            assertEquals(2, runtimeABackend.recoveryChecks);

            assertEquals(0, competingExecution.await());
            processPresent.set(false);
            SyncthingExecution retry = runtimeA.startServiceLifecycle(
                    SyncthingCommand.SERVE,
                    normalModeEnvironment()
            );
            assertEquals(0, retry.await());
            assertEquals(1, runtimeABackend.startCount);
        } finally {
            allowPermitAcquisition.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void revokedResetOneShotWinsOverCompetingExecutionFoundUnderPermit()
            throws Exception {
        AtomicBoolean processPresent = new AtomicBoolean();
        ExecutionIdentity competingIdentity = RecoveryAssessmentFixture.ownedIdentity();
        RacingRecoveryBackend runtimeABackend = new RacingRecoveryBackend(
                processPresent,
                competingIdentity
        );
        RacingRecoveryBackend runtimeBBackend = new RacingRecoveryBackend(
                processPresent,
                competingIdentity
        );
        CountDownLatch initialRecoveryComplete = new CountDownLatch(1);
        CountDownLatch allowPermitAcquisition = new CountDownLatch(1);
        LifecycleLaunchPermit resetPermit = new LifecycleLaunchPermit();
        DefaultSyncthingRuntime.LifecycleLaunchCheck resetLaunchCheck =
                new DefaultSyncthingRuntime.LifecycleLaunchCheck() {
                    @Override
                    public void commitRecoveryBlocked() {
                        resetPermit.commitRecoveryBlocked();
                    }

                    @Override
                    public void check() {
                        resetPermit.commitLaunch();
                    }
                };
        DefaultSyncthingRuntime runtimeA = new DefaultSyncthingRuntime(
                runtimeABackend,
                waitForPendingRequests -> {
                    initialRecoveryComplete.countDown();
                    awaitLatch(allowPermitAcquisition);
                    return OwnedExecutionShutdown.acquireLaunchPermit(waitForPendingRequests);
                }
        );
        DefaultSyncthingRuntime runtimeB = new DefaultSyncthingRuntime(runtimeBBackend);
        AtomicInteger shutdownHandlerCalls = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            Future<RuntimeException> resetA = worker.submit(() -> {
                try {
                    SyncthingExecution unexpected = runtimeA.startOneShotWithLifecycleCheck(
                            SyncthingCommand.RESET_DATABASE,
                            normalModeEnvironment(),
                            identity -> {
                                shutdownHandlerCalls.incrementAndGet();
                                return true;
                            },
                            resetLaunchCheck
                    );
                    unexpected.await();
                    return null;
                } catch (RuntimeException outcome) {
                    return outcome;
                }
            });

            assertTrue("Reset A did not finish its initial launchable classification",
                    initialRecoveryComplete.await(5, TimeUnit.SECONDS));
            assertEquals(1, runtimeABackend.recoveryChecks);
            assertEquals(LifecycleLaunchPermit.State.REVOKED, resetPermit.revoke());

            SyncthingExecution competingExecution = runtimeB.startServiceLifecycle(
                    SyncthingCommand.SERVE,
                    normalModeEnvironment()
            );
            assertEquals(1, runtimeBBackend.startCount);
            assertTrue(processPresent.get());

            allowPermitAcquisition.countDown();
            RuntimeException outcome = resetA.get(5, TimeUnit.SECONDS);

            assertTrue("Reset cancellation must precede competing-owner recovery failure: "
                            + outcome,
                    outcome instanceof LifecycleLaunchPermit.CancelledException);
            assertFalse(outcome instanceof ExecutionRecoveryException);
            assertEquals(0, runtimeABackend.startCount);
            assertEquals(0, shutdownHandlerCalls.get());
            assertEquals(2, runtimeABackend.recoveryChecks);

            assertEquals(0, competingExecution.await());
            processPresent.set(false);
            SyncthingExecution retry = runtimeA.startOneShot(
                    SyncthingCommand.RESET_DATABASE,
                    normalModeEnvironment(),
                    null,
                    () -> { }
            );
            assertEquals(0, retry.await());
            assertEquals(1, runtimeABackend.startCount);
        } finally {
            allowPermitAcquisition.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void launchPrerequisiteFailureHappensBeforeRecoveryCanStopAnOwner() {
        RecordingBackend backend = new RecordingBackend();
        backend.launchPrerequisiteFailure = new ExecutableNotFoundException("missing binary");
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);

        ExecutableNotFoundException failure = assertThrows(
                ExecutableNotFoundException.class,
                () -> runtime.start(SyncthingCommand.SERVE, normalModeEnvironment())
        );

        assertSame(backend.launchPrerequisiteFailure, failure);
        assertEquals(0, backend.recoveryChecks);
        assertTrue(backend.events.isEmpty());
    }

    @Test
    public void launchCommitRunsAfterRecoveryAndBeforeReplacementStart()
            throws Exception {
        RecordingBackend backend = new RecordingBackend();
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        LifecycleLaunchPermit permit = new LifecycleLaunchPermit();

        SyncthingExecution execution = runtime.startServiceLifecycle(
                SyncthingCommand.SERVE,
                normalModeEnvironment(),
                null,
                new DefaultSyncthingRuntime.LifecycleLaunchCheck() {
                    @Override
                    public void check() {
                        backend.events.add("port-check");
                        permit.commitLaunch();
                    }
                }
        );
        execution.await();

        assertEquals(Arrays.asList(
                        "validate", "recover", "recover", "port-check", "start"
                ),
                backend.events);
    }

    @Test
    public void revokedStartupPermitAfterExactOwnerRecoveryPreventsReplacementLaunch()
            throws Exception {
        assertStartupCancelledDuringExactRecovery("Run Conditions false");
        assertStartupCancelledDuringExactRecovery("explicit STOP");
        assertStartupCancelledDuringExactRecovery("service destruction");
    }

    @Test
    public void cancellationWinningAtFinalLaunchBoundaryPreventsBackendStart() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        backend.recoveryAssessments.add(RecoveryAssessmentFixture.ownedExecution());
        backend.recoveryAssessments.add(ExecutionOwnershipManager.RecoveryAssessment.noCandidate());
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        LifecycleLaunchPermit permit = new LifecycleLaunchPermit();

        assertThrows(
                LifecycleLaunchPermit.CancelledException.class,
                () -> runtime.startServiceLifecycle(
                        SyncthingCommand.SERVE,
                        normalModeEnvironment(),
                        identity -> {
                            assertEquals(RecoveryAssessmentFixture.ownedIdentity(), identity);
                            backend.events.add("old-owner-exit-proven");
                            return true;
                        },
                        () -> {
                            backend.events.add("cancel-before-launch-commit");
                            assertEquals(LifecycleLaunchPermit.State.REVOKED, permit.revoke());
                            permit.commitLaunch();
                        }
                )
        );

        assertEquals(0, backend.startCount);
        assertEquals(Arrays.asList(
                "validate",
                "recover",
                "old-owner-exit-proven",
                "recover",
                "recover",
                "cancel-before-launch-commit"
        ), backend.events);
    }

    @Test
    public void cancellationWinsFinalRecoveryBlockedSettlement() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        backend.recoveryAssessments.add(ExecutionOwnershipManager.RecoveryAssessment.noCandidate());
        backend.recoveryAssessments.add(RecoveryAssessmentFixture.ambiguousMissingRecord());
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        LifecycleLaunchPermit permit = new LifecycleLaunchPermit();
        CountDownLatch settlementStarted = new CountDownLatch(1);
        CountDownLatch allowSettlement = new CountDownLatch(1);
        AtomicInteger shutdownHandlerCalls = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            Future<RuntimeException> launch = worker.submit(() -> {
                try {
                    runtime.startServiceLifecycle(
                            SyncthingCommand.SERVE,
                            normalModeEnvironment(),
                            identity -> {
                                shutdownHandlerCalls.incrementAndGet();
                                return true;
                            },
                            new DefaultSyncthingRuntime.LifecycleLaunchCheck() {
                                @Override
                                public void commitRecoveryBlocked() {
                                    assertEquals(2, backend.recoveryChecks);
                                    settlementStarted.countDown();
                                    awaitLatch(allowSettlement);
                                    permit.commitRecoveryBlocked();
                                }

                                @Override
                                public void check() {
                                    throw new AssertionError(
                                            "A blocked recovery cannot launch"
                                    );
                                }
                            }
                    );
                    return null;
                } catch (RuntimeException outcome) {
                    return outcome;
                }
            });

            assertTrue("Final recovery did not reach blocked settlement",
                    settlementStarted.await(5, TimeUnit.SECONDS));
            assertEquals("STOP wins while the permit is still open",
                    LifecycleLaunchPermit.State.REVOKED, permit.revoke());
            allowSettlement.countDown();

            RuntimeException outcome = launch.get(5, TimeUnit.SECONDS);
            assertTrue("Cancellation must win recovery failure settlement: " + outcome,
                    outcome instanceof LifecycleLaunchPermit.CancelledException);
            assertFalse(outcome instanceof ExecutionRecoveryException);
            assertEquals(0, backend.startCount);
            assertEquals(0, shutdownHandlerCalls.get());
            assertEquals(2, backend.recoveryChecks);
        } finally {
            allowSettlement.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void recoveryBlockedSettlementWinsAgainstLaterCancellation() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        backend.recoveryAssessments.add(ExecutionOwnershipManager.RecoveryAssessment.noCandidate());
        backend.recoveryAssessments.add(RecoveryAssessmentFixture.ambiguousMissingRecord());
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        LifecycleLaunchPermit permit = new LifecycleLaunchPermit();
        AtomicInteger shutdownHandlerCalls = new AtomicInteger();

        ExecutionRecoveryException blocked = assertThrows(
                ExecutionRecoveryException.class,
                () -> runtime.startServiceLifecycle(
                        SyncthingCommand.SERVE,
                        normalModeEnvironment(),
                        identity -> {
                            shutdownHandlerCalls.incrementAndGet();
                            return true;
                        },
                        new DefaultSyncthingRuntime.LifecycleLaunchCheck() {
                            @Override
                            public void commitRecoveryBlocked() {
                                assertEquals(2, backend.recoveryChecks);
                                permit.commitRecoveryBlocked();
                                assertEquals("A later STOP cannot rewrite the settled outcome",
                                        LifecycleLaunchPermit.State.RECOVERY_BLOCKED,
                                        permit.revoke());
                            }

                            @Override
                            public void check() {
                                throw new AssertionError(
                                        "A blocked recovery cannot launch"
                                );
                            }
                        }
                )
        );

        assertEquals(ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                blocked.assessment().classification());
        assertEquals(0, backend.startCount);
        assertEquals(0, shutdownHandlerCalls.get());
        assertEquals(2, backend.recoveryChecks);
    }

    @Test
    public void revocationDuringInitialRecoveryBeatsBlockedRecoverySettlement() throws Exception {
        BlockedInitialRecoveryBackend backend = new BlockedInitialRecoveryBackend();
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        LifecycleLaunchPermit permit = new LifecycleLaunchPermit();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeException> launch = startCancellableLaunch(
                    worker,
                    runtime,
                    true,
                    permitLifecycleCheck(permit, new AtomicInteger())
            );
            assertTrue(
                    "the launch must enter its initial recovery classification",
                    backend.recoveryEntered.await(5, TimeUnit.SECONDS)
            );
            assertEquals(LifecycleLaunchPermit.State.REVOKED, permit.revoke());
            backend.allowBlockedRecovery.countDown();

            RuntimeException outcome = launch.get(5, TimeUnit.SECONDS);
            assertTrue(
                    "a STOP that already revoked startup must stay the terminal outcome: " + outcome,
                    outcome instanceof LifecycleLaunchPermit.CancelledException
            );
            assertEquals(0, backend.startCount);
        } finally {
            backend.allowBlockedRecovery.countDown();
            worker.shutdownNow();
        }
    }

    /**
     * A recovery failure raised while the launch is being prepared must settle the lifecycle permit
     * exactly like the final classification taken under the process-start reservation, so a STOP
     * that arrives afterwards still reports the recovery failure instead of a cancellation.
     */
    @Test
    public void preparationRecoveryBlockedSettlesTheServiceLifecyclePermit() throws Exception {
        assertPreparationRecoveryBlockedSettlesThePermit(true);
    }

    /** The one-shot adapter settles a preparation-time recovery failure the same way. */
    @Test
    public void preparationRecoveryBlockedSettlesTheOneShotPermit() throws Exception {
        assertPreparationRecoveryBlockedSettlesThePermit(false);
    }

    private void assertPreparationRecoveryBlockedSettlesThePermit(boolean serviceLifecycle)
            throws Exception {
        BlockedPreparationBackend backend = new BlockedPreparationBackend();
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        LifecycleLaunchPermit permit = new LifecycleLaunchPermit();
        AtomicInteger finalLaunchChecks = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeException> launch = startCancellableLaunch(
                    worker, runtime, serviceLifecycle, permitLifecycleCheck(permit, finalLaunchChecks)
            );
            assertTrue(
                    "the launch must reach backend preparation",
                    backend.preparationEntered.await(5, TimeUnit.SECONDS)
            );
            backend.allowPreparationFailure.countDown();
            RuntimeException outcome = launch.get(5, TimeUnit.SECONDS);
            assertTrue(
                    "a preparation-time recovery failure stays the reported outcome: " + outcome,
                    outcome instanceof ExecutionRecoveryException
            );
            assertEquals(
                    "a STOP after the settled recovery failure reports that failure, not a"
                            + " cancellation",
                    LifecycleLaunchPermit.State.RECOVERY_BLOCKED,
                    permit.revoke()
            );
            assertEquals("no process is created", 0, backend.startCount);
            assertEquals("the final launch check never runs", 0, finalLaunchChecks.get());
        } finally {
            backend.allowPreparationFailure.countDown();
            worker.shutdownNow();
        }
    }

    /**
     * A STOP that revokes the lifecycle permit before preparation reports its recovery failure
     * keeps cancellation as the reported outcome, exactly like the final settlement under the
     * reservation.
     */
    @Test
    public void revocationDuringPreparationBeatsTheServiceLifecycleSettlement() throws Exception {
        assertRevocationDuringPreparationWins(true);
    }

    /** The one-shot adapter reports a revocation that already won the same way. */
    @Test
    public void revocationDuringPreparationBeatsTheOneShotSettlement() throws Exception {
        assertRevocationDuringPreparationWins(false);
    }

    private void assertRevocationDuringPreparationWins(boolean serviceLifecycle) throws Exception {
        BlockedPreparationBackend backend = new BlockedPreparationBackend();
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        LifecycleLaunchPermit permit = new LifecycleLaunchPermit();
        AtomicInteger finalLaunchChecks = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeException> launch = startCancellableLaunch(
                    worker, runtime, serviceLifecycle, permitLifecycleCheck(permit, finalLaunchChecks)
            );
            assertTrue(
                    "the launch must reach backend preparation",
                    backend.preparationEntered.await(5, TimeUnit.SECONDS)
            );
            assertEquals(LifecycleLaunchPermit.State.REVOKED, permit.revoke());
            backend.allowPreparationFailure.countDown();
            RuntimeException outcome = launch.get(5, TimeUnit.SECONDS);
            assertTrue(
                    "a revocation that already won stays the reported outcome: " + outcome,
                    outcome instanceof LifecycleLaunchPermit.CancelledException
            );
            assertEquals("no process is created", 0, backend.startCount);
            assertEquals("the final launch check never runs", 0, finalLaunchChecks.get());
        } finally {
            backend.allowPreparationFailure.countDown();
            worker.shutdownNow();
        }
    }

    private static Future<RuntimeException> startCancellableLaunch(
            ExecutorService worker,
            DefaultSyncthingRuntime runtime,
            boolean serviceLifecycle,
            DefaultSyncthingRuntime.LifecycleLaunchCheck launchCheck
    ) {
        return worker.submit(() -> {
            try {
                SyncthingExecution execution = serviceLifecycle
                        ? runtime.startServiceLifecycle(
                                SyncthingCommand.SERVE,
                                normalModeEnvironment(),
                                null,
                                launchCheck
                        )
                        : runtime.startOneShotWithLifecycleCheck(
                                SyncthingCommand.DEVICE_ID,
                                normalModeEnvironment(),
                                null,
                                launchCheck
                        );
                execution.await();
                return null;
            } catch (RuntimeException outcome) {
                return outcome;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("The launch was interrupted unexpectedly", interrupted);
            }
        });
    }

    private static DefaultSyncthingRuntime.LifecycleLaunchCheck permitLifecycleCheck(
            LifecycleLaunchPermit permit,
            AtomicInteger finalLaunchChecks
    ) {
        return new DefaultSyncthingRuntime.LifecycleLaunchCheck() {
            @Override
            public void commitRecoveryBlocked() {
                permit.commitRecoveryBlocked();
            }

            @Override
            public void check() {
                finalLaunchChecks.incrementAndGet();
                permit.commitLaunch();
            }
        };
    }

    @Test
    public void launchCommitWinningMakesLaterStopTooLateToPreventExactOwnedStart()
            throws Exception {
        RecordingBackend backend = new RecordingBackend();
        backend.recoveryAssessments.add(ExecutionOwnershipManager.RecoveryAssessment.noCandidate());
        ExecutionIdentity launchedIdentity = RecoveryAssessmentFixture.ownedIdentity();
        backend.execution = new ImmediateExecution(launchedIdentity);
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        LifecycleLaunchPermit permit = new LifecycleLaunchPermit();
        CountDownLatch launchCommitted = new CountDownLatch(1);
        CountDownLatch allowBackendStart = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            Future<SyncthingExecution> launch = worker.submit(() ->
                    runtime.startServiceLifecycle(
                            SyncthingCommand.SERVE,
                            normalModeEnvironment(),
                            null,
                            () -> {
                                permit.commitLaunch();
                                launchCommitted.countDown();
                                try {
                                    allowBackendStart.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(e);
                                }
                            }
                    )
            );

            assertTrue("Launch did not reach its commit boundary",
                    launchCommitted.await(5, TimeUnit.SECONDS));
            assertEquals("STOP must observe that launch already committed",
                    LifecycleLaunchPermit.State.LAUNCH_COMMITTED, permit.revoke());
            allowBackendStart.countDown();

            SyncthingExecution execution = launch.get(5, TimeUnit.SECONDS);
            assertEquals(1, backend.startCount);
            assertSame("The started execution must retain identity for exact shutdown",
                    launchedIdentity, execution.identity());

            List<ExecutionOwnershipManager.Signal> signals = new ArrayList<>();
            int[] waitCalls = {0};
            OwnedExecutionShutdown.Outcome shutdown = OwnedExecutionShutdown.stop(
                    execution.identity(),
                    () -> null,
                    new OwnedExecutionShutdown.ExecutionControl() {
                        @Override
                        public ExecutionOwnershipManager.Observation observe(
                                ExecutionIdentity identity
                        ) {
                            assertSame(launchedIdentity, identity);
                            return ExecutionOwnershipManager.Observation.OWNED;
                        }

                        @Override
                        public ExecutionOwnershipManager.SignalAttempt signalIfOwned(
                                ExecutionIdentity identity,
                                ExecutionOwnershipManager.Signal signal
                        ) {
                            assertSame(launchedIdentity, identity);
                            signals.add(signal);
                            return ExecutionOwnershipManager.SignalAttempt.SIGNALED;
                        }
                    },
                    (identity, timeoutMillis, control) -> {
                        assertSame(launchedIdentity, identity);
                        if (waitCalls[0]++ == 0) {
                            assertEquals(OwnedExecutionShutdown.REST_SHUTDOWN_WAIT_MS,
                                    timeoutMillis);
                            return false;
                        }
                        assertEquals(OwnedExecutionShutdown.SIGINT_WAIT_MS, timeoutMillis);
                        return true;
                    }
            );

            assertEquals(OwnedExecutionShutdown.Outcome.EXITED, shutdown);
            assertEquals(Collections.singletonList(ExecutionOwnershipManager.Signal.SIGINT),
                    signals);
        } finally {
            allowBackendStart.countDown();
            worker.shutdownNow();
        }
    }

    private static void assertStartupCancelledDuringExactRecovery(String cancellationReason)
            throws Exception {
        RecordingBackend backend = new RecordingBackend();
        backend.recoveryAssessments.add(RecoveryAssessmentFixture.ownedExecution());
        backend.recoveryAssessments.add(ExecutionOwnershipManager.RecoveryAssessment.noCandidate());
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        LifecycleLaunchPermit permit = new LifecycleLaunchPermit();
        CountDownLatch recoveryStarted = new CountDownLatch(1);
        CountDownLatch allowRecoveryToFinish = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            Future<String> launch = worker.submit(() -> {
                try {
                    runtime.startServiceLifecycle(
                            SyncthingCommand.SERVE,
                            normalModeEnvironment(),
                            identity -> {
                                assertEquals(RecoveryAssessmentFixture.ownedIdentity(), identity);
                                recoveryStarted.countDown();
                                allowRecoveryToFinish.await();
                                backend.events.add("old-owner-exit-proven");
                                return true;
                            },
                            permit::commitLaunch
                    );
                    return "launched";
                } catch (LifecycleLaunchPermit.CancelledException expected) {
                    return "cancelled";
                }
            });

            assertTrue("Recovery did not start for " + cancellationReason,
                    recoveryStarted.await(5, TimeUnit.SECONDS));
            // Each service stop boundary revokes this one startup's permit while exact recovery is
            // still in progress.
            permit.revoke();
            allowRecoveryToFinish.countDown();

            assertEquals("cancelled", launch.get(5, TimeUnit.SECONDS));
            assertEquals(0, backend.startCount);
            assertTrue(backend.events.contains("old-owner-exit-proven"));
            assertTrue(backend.events.indexOf("old-owner-exit-proven")
                    < backend.events.lastIndexOf("recover"));
        } finally {
            allowRecoveryToFinish.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void destroyingAnInvocationKeepsAdmissionUntilExecutionExit() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        DelayedTerminationExecution execution = new DelayedTerminationExecution();
        backend.execution = execution;
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        SyncthingEnvironment environment = SyncthingEnvironment.builder()
                .home("/home")
                .syncthingHome("/state")
                .trace("")
                .monitored()
                .noUpgrade()
                .versionExtra("app")
                .sqliteTemporaryDirectory("/tmp")
                .gogc(100)
                .build();

        SyncthingExecution first = runtime.start(SyncthingCommand.SERVE, environment);
        first.destroy();
        assertTrue(execution.destroyRequested);

        assertThrows(
                ExecutionAdmissionException.class,
                () -> runtime.start(SyncthingCommand.DEVICE_ID, environment)
        );

        execution.exit(137);
        assertEquals(137, first.await());

        backend.execution = new ImmediateExecution();
        SyncthingExecution second = runtime.start(SyncthingCommand.DEVICE_ID, environment);
        second.await();
    }

    @Test
    public void resetDatabaseCannotOverlapServeUntilServeActuallyExits() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        DelayedTerminationExecution execution = new DelayedTerminationExecution();
        backend.execution = execution;
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        SyncthingEnvironment environment = SyncthingEnvironment.builder()
                .home("/home")
                .syncthingHome("/state")
                .trace("")
                .monitored()
                .noUpgrade()
                .versionExtra("app")
                .sqliteTemporaryDirectory("/tmp")
                .gogc(100)
                .build();

        SyncthingExecution serve = runtime.start(SyncthingCommand.SERVE, environment);
        serve.destroy();

        assertThrows(
                ExecutionAdmissionException.class,
                () -> runtime.start(SyncthingCommand.RESET_DATABASE, environment)
        );

        execution.exit(0);
        assertEquals(0, serve.await());

        backend.execution = new ImmediateExecution();
        SyncthingExecution reset = runtime.start(SyncthingCommand.RESET_DATABASE, environment);
        assertEquals(0, reset.await());
    }

    @Test
    public void interruptedAwaitKeepsAdmissionUntilExecutionExit() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        InterruptedThenDelayedExitExecution execution = new InterruptedThenDelayedExitExecution();
        backend.execution = execution;
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        SyncthingEnvironment environment = SyncthingEnvironment.builder()
                .home("/home")
                .syncthingHome("/state")
                .trace("")
                .monitored()
                .noUpgrade()
                .versionExtra("app")
                .sqliteTemporaryDirectory("/tmp")
                .gogc(100)
                .build();

        SyncthingExecution first = runtime.start(SyncthingCommand.SERVE, environment);

        assertThrows(InterruptedException.class, first::await);
        assertThrows(
                ExecutionAdmissionException.class,
                () -> runtime.start(SyncthingCommand.DEVICE_ID, environment)
        );

        first.destroy();
        assertTrue(execution.destroyRequested);
        assertThrows(
                ExecutionAdmissionException.class,
                () -> runtime.start(SyncthingCommand.DEVICE_ID, environment)
        );

        execution.exit(0);
        assertEquals(0, first.await());

        backend.execution = new ImmediateExecution();
        SyncthingExecution second = runtime.start(SyncthingCommand.DEVICE_ID, environment);
        second.await();
    }

    @Test
    public void waitingServiceLifecycleStartKeepsAdmissionPriorityOverOneShotStarts()
            throws Exception {
        QueuedExecutionBackend backend = new QueuedExecutionBackend();
        GatedExecution firstExecution = new GatedExecution();
        GatedExecution serveExecution = new GatedExecution();
        backend.queueExecution(firstExecution);
        backend.queueExecution(serveExecution);
        backend.queueExecution(new ImmediateExecution());
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        SyncthingEnvironment environment = normalModeEnvironment();

        SyncthingExecution first = runtime.start(SyncthingCommand.RESET_DATABASE, environment);

        ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<SyncthingExecution> serveStart = lifecycleExecutor.submit(
                    () -> runtime.startServiceLifecycle(SyncthingCommand.SERVE, environment)
            );

            // The registered lifecycle start keeps the next admission: one-shot attempts are
            // rejected while it waits, while the active invocation is only destroyed, and in the
            // handoff window after that invocation's exit has been observed.
            runtime.whileServiceLifecycleStartWaits(() -> {
                assertThrows(
                        ExecutionAdmissionException.class,
                        () -> runtime.start(SyncthingCommand.DEVICE_ID, environment)
                );

                first.destroy();
                assertTrue(firstExecution.destroyRequested);
                assertThrows(
                        ExecutionAdmissionException.class,
                        () -> runtime.start(SyncthingCommand.DEVICE_ID, environment)
                );
                assertFalse(serveStart.isDone());
                assertEquals(1, backend.startCount());

                firstExecution.exit(0);
                assertEquals(0, first.await());
                assertThrows(
                        ExecutionAdmissionException.class,
                        () -> runtime.start(SyncthingCommand.DEVICE_ID, environment)
                );
            });

            // The waiting lifecycle start receives the next admission, not a one-shot.
            SyncthingExecution serve = serveStart.get(5, TimeUnit.SECONDS);
            assertEquals(2, backend.startCount());
            assertSame(SyncthingCommand.SERVE, backend.startedCommand(1));

            // One-shots stay rejected while the lifecycle invocation owns admission.
            assertThrows(
                    ExecutionAdmissionException.class,
                    () -> runtime.start(SyncthingCommand.DEVICE_ID, environment)
            );

            // After the lifecycle invocation exits, one-shots are admitted again.
            serveExecution.exit(137);
            assertEquals(137, serve.await());
            SyncthingExecution deviceId = runtime.start(SyncthingCommand.DEVICE_ID, environment);
            assertEquals(0, deviceId.await());
            assertEquals(3, backend.startCount());
            assertSame(SyncthingCommand.DEVICE_ID, backend.startedCommand(2));
        } finally {
            lifecycleExecutor.shutdownNow();
        }
    }

    @Test
    public void serviceLifecycleStartsAcquireAdmissionOnlyAfterThePreviousExecutionExits()
            throws Exception {
        QueuedExecutionBackend backend = new QueuedExecutionBackend();
        GatedExecution firstExecution = new GatedExecution();
        GatedExecution serveExecution = new GatedExecution();
        backend.queueExecution(firstExecution);
        backend.queueExecution(serveExecution);
        backend.queueExecution(new ImmediateExecution());
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        SyncthingEnvironment environment = normalModeEnvironment();

        SyncthingExecution first = runtime.start(SyncthingCommand.RESET_DATABASE, environment);
        ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch serveRequested = new CountDownLatch(1);
            Future<SyncthingExecution> serveStart = lifecycleExecutor.submit(() -> {
                serveRequested.countDown();
                return runtime.startServiceLifecycle(SyncthingCommand.SERVE, environment);
            });
            assertTrue(serveRequested.await(5, TimeUnit.SECONDS));
            assertFalse(serveStart.isDone());

            firstExecution.exit(0);
            assertEquals(0, first.await());

            SyncthingExecution serve = serveStart.get(5, TimeUnit.SECONDS);
            assertThrows(
                    ExecutionAdmissionException.class,
                    () -> runtime.start(SyncthingCommand.DEVICE_ID, environment)
            );

            CountDownLatch deltasRequested = new CountDownLatch(1);
            Future<SyncthingExecution> deltasStart = lifecycleExecutor.submit(() -> {
                deltasRequested.countDown();
                return runtime.startServiceLifecycle(
                        SyncthingCommand.RESET_DELTAS,
                        environment
                );
            });
            assertTrue(deltasRequested.await(5, TimeUnit.SECONDS));
            assertFalse(deltasStart.isDone());

            serveExecution.exit(137);
            assertEquals(137, serve.await());

            SyncthingExecution deltas = deltasStart.get(5, TimeUnit.SECONDS);
            assertEquals(0, deltas.await());
            assertEquals(3, backend.startCount());
            assertSame(SyncthingCommand.RESET_DATABASE, backend.startedCommand(0));
            assertSame(SyncthingCommand.SERVE, backend.startedCommand(1));
            assertSame(SyncthingCommand.RESET_DELTAS, backend.startedCommand(2));
        } finally {
            lifecycleExecutor.shutdownNow();
        }
    }

    @Test
    public void runtimeKeepsConfigStorageAndFolderOperationsSemantic() throws IOException {
        RecordingBackend backend = new RecordingBackend();
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        ConfigStorage storage = backend.storage;
        ConfiguredFolderReference folder = ConfiguredFolderReference.of("folder-id", "/configured/folder");

        assertSame(storage, runtime.configStorage());
        storage.save(new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, storage.load());
        assertEquals(
                FolderWriteability.WRITABLE,
                runtime.validateCandidateFolder("/candidate/folder")
        );
        assertEquals(
                Arrays.asList("nested/file.sync-conflict-20260101-010101-DEVICE"),
                runtime.discoverConflicts(folder).relativePaths()
        );
        assertArrayEquals(
                new String[]{"*.tmp"},
                runtime.loadFolderIgnoreList(folder).lines()
        );
        runtime.saveFolderIgnoreList(folder, new String[]{"*.tmp", "*.bak"});
        assertArrayEquals(new String[]{"*.tmp", "*.bak"}, backend.ignore);
        runtime.runFolderScripts(folder, FolderEvent.SYNC_COMPLETE);
        assertEquals("folder-id", backend.folder.id());
        assertEquals("/configured/folder", backend.folder.path());
        assertEquals(FolderEvent.SYNC_COMPLETE, backend.event);
        assertEquals("sync_complete", FolderEvent.SYNC_COMPLETE.argument());
    }

    @Test
    public void appUidBackendPreservesProcessTransportOutputAndCleanup() throws Exception {
        File binary = File.createTempFile("syncthing", ".bin");
        binary.deleteOnExit();
        RecordingProcess process = new RecordingProcess("stdout", "stderr", 23);
        RecordingProcessLauncher launcher = new RecordingProcessLauncher(process);
        AppUidBackend backend = new AppUidBackend(
                binary,
                launcher,
                unownedExecutionManager(),
                new InMemoryConfigStorage()
        );
        SyncthingEnvironment environment = SyncthingEnvironment.builder()
                .home("/home")
                .syncthingHome("/state")
                .trace("")
                .monitored()
                .noUpgrade()
                .versionExtra("app")
                .sqliteTemporaryDirectory("/tmp")
                .gogc(100)
                .customVariables(Collections.singletonMap("CUSTOM", "value"))
                .build();

        PrivilegeBackend.Execution execution = backend.start(
                SyncthingCommand.DEVICE_ID,
                environment
        );

        assertArrayEquals(
                new String[]{binary.getPath(), "device-id"},
                launcher.argv
        );
        assertTrue(launcher.environment.entrySet().containsAll(environment.values().entrySet()));
        assertFalse(launcher.environment.get(ProcExecutionInspector.RUN_TOKEN_ENVIRONMENT).isEmpty());
        assertEquals("stdout", new String(execution.stdout().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("stderr", new String(execution.stderr().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(23, execution.await());
        execution.destroy();
        assertNull(execution.identity());
        assertFalse(process.destroyed);
    }

    @Test
    public void appUidBackendRecordsTokenAndExactLiveIdentityAfterLaunch() throws Exception {
        File binary = File.createTempFile("syncthing", ".bin");
        binary.deleteOnExit();
        ExecutionIdentity[] liveIdentity = new ExecutionIdentity[1];
        ExecutionIdentity[] durableIdentity = new ExecutionIdentity[1];
        ExecutionRecordStore records = new ExecutionRecordStore() {
            @Override
            public ReadResult read() {
                return durableIdentity[0] == null
                        ? ReadResult.missing()
                        : ReadResult.valid(durableIdentity[0]);
            }

            @Override
            public void write(ExecutionIdentity identity) {
                durableIdentity[0] = identity;
            }

            @Override
            public boolean deleteIfRunTokenMatches(String runToken) {
                if (durableIdentity[0] == null
                        || !durableIdentity[0].runToken().equals(runToken)) {
                    return false;
                }
                durableIdentity[0] = null;
                return true;
            }
        };
        ExecutionInspector inspector = new ExecutionInspector() {
            @Override
            public String currentBootId() {
                return "boot-a";
            }

            @Override
            public InspectionResult inspect(int pid) {
                return liveIdentity[0] != null && liveIdentity[0].pid() == pid
                        ? InspectionResult.live(liveIdentity[0])
                        : InspectionResult.processAbsent();
            }

            @Override
            public List<ExecutionIdentity> findBundledCandidates(String executablePath) {
                return liveIdentity[0] != null
                        && executablePath.equals(liveIdentity[0].executablePath())
                        ? Collections.singletonList(liveIdentity[0])
                        : Collections.emptyList();
            }

            @Override
            public ExecutionIdentity findLaunchedProcess(String executablePath, String runToken) {
                return liveIdentity[0] != null
                        && executablePath.equals(liveIdentity[0].executablePath())
                        && runToken.equals(liveIdentity[0].runToken())
                        ? liveIdentity[0]
                        : null;
            }
        };
        ExecutionOwnershipManager ownershipManager = new ExecutionOwnershipManager(
                binary.getAbsolutePath(), records, inspector,
                (pid, signal) -> {
                    throw new AssertionError("Launch verification must not signal the child");
                }
        );
        AppUidBackend backend = new AppUidBackend(
                binary,
                (argv, environment) -> {
                    liveIdentity[0] = new ExecutionIdentity(
                            123,
                            456,
                            "boot-a",
                            binary.getAbsolutePath(),
                            environment.get(ProcExecutionInspector.RUN_TOKEN_ENVIRONMENT)
                    );
                    return new RecordingProcess("", "", 0);
                },
                ownershipManager,
                new InMemoryConfigStorage()
        );

        PrivilegeBackend.Execution execution = backend.start(
                SyncthingCommand.DEVICE_ID,
                normalModeEnvironment()
        );

        assertNotNull(execution.identity());
        assertEquals(36, execution.identity().runToken().length());
        assertSame(liveIdentity[0], durableIdentity[0]);
        assertSame(liveIdentity[0], execution.identity());
        assertEquals(ExecutionOwnershipManager.Classification.OWNED_EXECUTION,
                ownershipManager.recover().classification());
    }

    @Test
    public void appUidBackendDoesNotSignalChildWhenDurableIdentityRecordingFails()
            throws Exception {
        File binary = File.createTempFile("syncthing", ".bin");
        binary.deleteOnExit();
        GatedRecordingProcess process = new GatedRecordingProcess();
        ExecutionIdentity[] launchedIdentity = new ExecutionIdentity[1];
        int[] signalCount = new int[1];
        ExecutionRecordStore records = new ExecutionRecordStore() {
            @Override
            public ReadResult read() {
                return ReadResult.missing();
            }

            @Override
            public void write(ExecutionIdentity identity) throws IOException {
                throw new IOException("simulated durable write failure");
            }

            @Override
            public boolean deleteIfRunTokenMatches(String runToken) {
                return false;
            }
        };
        ExecutionInspector inspector = new ExecutionInspector() {
            @Override
            public String currentBootId() {
                return "boot-a";
            }

            @Override
            public InspectionResult inspect(int pid) {
                return launchedIdentity[0] == null
                        ? InspectionResult.processAbsent()
                        : InspectionResult.live(launchedIdentity[0]);
            }

            @Override
            public List<ExecutionIdentity> findBundledCandidates(String executablePath) {
                return Collections.emptyList();
            }

            @Override
            public ExecutionIdentity findLaunchedProcess(String executablePath, String runToken) {
                return launchedIdentity[0];
            }
        };
        ExecutionOwnershipManager ownershipManager = new ExecutionOwnershipManager(
                binary.getAbsolutePath(),
                records,
                inspector,
                (pid, signal) -> {
                    signalCount[0]++;
                    return ExecutionOwnershipManager.SignalResult.SIGNALED;
                }
        );
        AppUidBackend backend = new AppUidBackend(
                binary,
                (argv, environment) -> {
                    launchedIdentity[0] = new ExecutionIdentity(
                            123,
                            456,
                            "boot-a",
                            binary.getAbsolutePath(),
                            environment.get(ProcExecutionInspector.RUN_TOKEN_ENVIRONMENT)
                    );
                    return process;
                },
                ownershipManager,
                new InMemoryConfigStorage()
        );
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);

        SyncthingEnvironment environment = normalModeEnvironment();
        SyncthingExecution execution = runtime.start(
                SyncthingCommand.DEVICE_ID,
                environment
        );

        assertNotNull(launchedIdentity[0]);
        assertNull(execution.identity());
        execution.destroy();
        assertEquals(0, signalCount[0]);
        assertFalse(process.destroyed);

        CountDownLatch awaitStarted = new CountDownLatch(1);
        AtomicReference<Throwable> oneShotFailure = new AtomicReference<>();
        AtomicReference<Integer> oneShotResult = new AtomicReference<>();
        Thread oneShot = new Thread(() -> {
            awaitStarted.countDown();
            try {
                int exitCode = execution.await();
                execution.requireIdentityForSuccessfulOneShotResult();
                oneShotResult.set(exitCode);
            } catch (Throwable failure) {
                oneShotFailure.set(failure);
            }
        });
        try {
            oneShot.start();
            assertTrue(awaitStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(process.waitStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(
                    ExecutionAdmissionException.class,
                    () -> runtime.start(SyncthingCommand.RESET_DATABASE, environment)
            );
        } finally {
            process.exit(0);
            awaitStarted.countDown();
            oneShot.join(1_000);
            if (oneShot.isAlive()) {
                oneShot.interrupt();
                oneShot.join(1_000);
            }
        }
        assertFalse(oneShot.isAlive());

        assertNull(oneShotResult.get());
        assertNotNull(oneShotFailure.get());
        assertEquals(
                "ExecutionIdentityUnavailableException",
                oneShotFailure.get().getClass().getSimpleName()
        );
        assertEquals(0, signalCount[0]);

        SyncthingExecution afterExit = runtime.start(
                SyncthingCommand.RESET_DATABASE,
                environment
        );
        assertEquals(0, afterExit.await());
    }

    private static ExecutionOwnershipManager unownedExecutionManager() {
        ExecutionRecordStore records = new ExecutionRecordStore() {
            @Override
            public ReadResult read() {
                return ReadResult.missing();
            }

            @Override
            public void write(ExecutionIdentity identity) {
                throw new AssertionError("The fixture cannot identify a launched process");
            }

            @Override
            public boolean deleteIfRunTokenMatches(String runToken) {
                return false;
            }
        };
        ExecutionInspector inspector = new ExecutionInspector() {
            @Override
            public String currentBootId() {
                return "boot-a";
            }

            @Override
            public InspectionResult inspect(int pid) {
                return InspectionResult.processAbsent();
            }

            @Override
            public List<ExecutionIdentity> findBundledCandidates(String executablePath) {
                return Collections.emptyList();
            }

            @Override
            public ExecutionIdentity findLaunchedProcess(String executablePath, String runToken) {
                return null;
            }
        };
        return new ExecutionOwnershipManager(
                "/expected/syncthing",
                records,
                inspector,
                (pid, signal) -> {
                    throw new AssertionError("An unidentified process must never be signaled");
                }
        );
    }

    private static class RecordingBackend implements PrivilegeBackend {
        private final ConfigStorage storage = new InMemoryConfigStorage();
        protected final List<String> events = new ArrayList<>();
        private final Deque<ExecutionOwnershipManager.RecoveryAssessment> recoveryAssessments =
                new ConcurrentLinkedDeque<>();
        protected Execution execution = new ImmediateExecution();
        private ExecutableNotFoundException launchPrerequisiteFailure;
        protected volatile int recoveryChecks;
        protected SyncthingCommand command;
        private SyncthingEnvironment environment;
        private ConfiguredFolderReference folder;
        private FolderEvent event;
        private String[] ignore;
        protected volatile int startCount;
        private ExecutionOwnershipManager.Observation observation =
                ExecutionOwnershipManager.Observation.UNKNOWN;

        @Override
        public void validateLaunchPrerequisites() throws ExecutableNotFoundException {
            if (launchPrerequisiteFailure != null) throw launchPrerequisiteFailure;
            events.add("validate");
        }

        @Override
        public Execution start(SyncthingCommand command, SyncthingEnvironment environment)
                throws IOException, ExecutableNotFoundException {
            startCount++;
            events.add("start");
            this.command = command;
            this.environment = environment;
            return execution;
        }

        @Override
        public ExecutionOwnershipManager.RecoveryAssessment recoverExecutions() {
            events.add("recover");
            recoveryChecks++;
            ExecutionOwnershipManager.RecoveryAssessment assessment = recoveryAssessments.poll();
            return assessment != null
                    ? assessment
                    : ExecutionOwnershipManager.RecoveryAssessment.noCandidate();
        }

        @Override
        public ExecutionOwnershipManager.Observation observe(ExecutionIdentity identity) {
            return observation;
        }

        @Override
        public ConfigStorage configStorage() {
            return storage;
        }

        @Override
        public FolderWriteability validateCandidateFolder(String path) {
            return FolderWriteability.WRITABLE;
        }

        @Override
        public ConflictDiscoveryResult discoverConflicts(ConfiguredFolderReference folder) {
            this.folder = folder;
            return ConflictDiscoveryResult.of(
                    Collections.singletonList("nested/file.sync-conflict-20260101-010101-DEVICE")
            );
        }

        @Override
        public FolderIgnoreResult loadFolderIgnoreList(ConfiguredFolderReference folder) {
            this.folder = folder;
            return FolderIgnoreResult.of(new String[]{"*.tmp"});
        }

        @Override
        public void saveFolderIgnoreList(ConfiguredFolderReference folder, String[] ignore) {
            this.folder = folder;
            this.ignore = ignore.clone();
        }

        @Override
        public void runFolderScripts(
                ConfiguredFolderReference folder,
                FolderEvent event
        ) {
            this.folder = folder;
            this.event = event;
        }

    }

    private static final class GatedProcessCreationBackend extends RecordingBackend {
        private final CountDownLatch startEntered = new CountDownLatch(1);
        private final CountDownLatch allowProcessCreation = new CountDownLatch(1);
        private final ExecutionIdentity identity;
        private volatile boolean processCreated;
        private volatile boolean processExited;

        private GatedProcessCreationBackend(ExecutionIdentity identity) {
            this.identity = identity;
        }

        @Override
        public Execution start(SyncthingCommand command, SyncthingEnvironment environment)
                throws IOException, ExecutableNotFoundException {
            startEntered.countDown();
            awaitLatch(allowProcessCreation);
            execution = new ImmediateExecution(identity);
            processCreated = true;
            return super.start(command, environment);
        }

        @Override
        public ExecutionOwnershipManager.RecoveryAssessment recoverExecutions() {
            events.add("recover");
            recoveryChecks++;
            if (processCreated && !processExited) {
                return RecoveryAssessmentFixture.ownedExecution();
            }
            return ExecutionOwnershipManager.RecoveryAssessment.noCandidate();
        }

        private void markExited() {
            processExited = true;
        }
    }

    private static final class RacingRecoveryBackend extends RecordingBackend {
        private final AtomicBoolean processPresent;
        private final ExecutionIdentity identity;

        private RacingRecoveryBackend(
                AtomicBoolean processPresent,
                ExecutionIdentity identity
        ) {
            this.processPresent = processPresent;
            this.identity = identity;
        }

        @Override
        public ExecutionOwnershipManager.RecoveryAssessment recoverExecutions() {
            events.add("recover");
            recoveryChecks++;
            return processPresent.get()
                    ? RecoveryAssessmentFixture.ownedExecution()
                    : ExecutionOwnershipManager.RecoveryAssessment.noCandidate();
        }

        @Override
        public Execution start(SyncthingCommand command, SyncthingEnvironment environment)
                throws IOException, ExecutableNotFoundException {
            processPresent.set(true);
            execution = new ImmediateExecution(identity);
            return super.start(command, environment);
        }
    }

    /**
     * A backend whose launch preparation performs the recovery classification that forbids a
     * launch, exactly as both production backends do before they prepare root capability.
     */
    private static final class BlockedInitialRecoveryBackend extends RecordingBackend {
        private final CountDownLatch recoveryEntered = new CountDownLatch(1);
        private final CountDownLatch allowBlockedRecovery = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ExecutionOwnershipManager.RecoveryAssessment recoverExecutions() {
            events.add("recover");
            recoveryChecks++;
            if (calls.getAndIncrement() == 0) {
                recoveryEntered.countDown();
                awaitLatch(allowBlockedRecovery);
                return RecoveryAssessmentFixture.ambiguousMissingRecord();
            }
            return ExecutionOwnershipManager.RecoveryAssessment.noCandidate();
        }
    }

    private static final class BlockedPreparationBackend extends RecordingBackend {
        private final CountDownLatch preparationEntered = new CountDownLatch(1);
        private final CountDownLatch allowPreparationFailure = new CountDownLatch(1);

        @Override
        public PrivilegeBackend.LaunchPreparation prepareLaunch(
                SyncthingCommand command,
                SyncthingEnvironment environment
        ) throws IOException, ExecutableNotFoundException {
            events.add("prepare");
            preparationEntered.countDown();
            awaitLatch(allowPreparationFailure);
            throw new ExecutionRecoveryException(RecoveryAssessmentFixture.ownedExecution());
        }
    }

    private static final class ImmediateExecution implements PrivilegeBackend.Execution {
        private final ExecutionIdentity identity;

        private ImmediateExecution() {
            this(null);
        }

        private ImmediateExecution(ExecutionIdentity identity) {
            this.identity = identity;
        }

        @Override
        public InputStream stdout() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream stderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int await() {
            return 0;
        }

        @Override
        public void destroy() {
        }

        @Override
        public ExecutionIdentity identity() {
            return identity;
        }
    }

    private static final class DelayedTerminationExecution implements PrivilegeBackend.Execution {
        private boolean destroyRequested;
        private boolean exited;
        private int exitCode;

        @Override
        public InputStream stdout() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream stderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int await() {
            if (!exited) {
                throw new AssertionError("test execution has not exited");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyRequested = true;
        }

        private void exit(int exitCode) {
            this.exitCode = exitCode;
            exited = true;
        }
    }

    private static final class InterruptedThenDelayedExitExecution
            implements PrivilegeBackend.Execution {
        private boolean interrupted = true;
        private boolean destroyRequested;
        private boolean exited;
        private int exitCode;

        @Override
        public InputStream stdout() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream stderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int await() throws InterruptedException {
            if (interrupted) {
                interrupted = false;
                throw new InterruptedException("test interruption");
            }
            if (!exited) {
                throw new AssertionError("test execution has not exited");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyRequested = true;
        }

        private void exit(int exitCode) {
            this.exitCode = exitCode;
            exited = true;
        }
    }

    /**
     * Builds the Normal Mode environment contract shared by the admission tests.
     */
    private static SyncthingEnvironment normalModeEnvironment() {
        return SyncthingEnvironment.builder()
                .home("/home")
                .syncthingHome("/state")
                .trace("")
                .monitored()
                .noUpgrade()
                .versionExtra("app")
                .sqliteTemporaryDirectory("/tmp")
                .gogc(100)
                .build();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * Backend that hands out queued executions and fails the test when two bundled invocations
     * were active at the same time.
     */
    private static final class QueuedExecutionBackend implements PrivilegeBackend {
        private final AtomicInteger activeInvocations = new AtomicInteger();
        private final List<SyncthingCommand> startedCommands =
                Collections.synchronizedList(new ArrayList<>());
        private final Deque<PrivilegeBackend.Execution> queuedExecutions =
                new ConcurrentLinkedDeque<>();

        void queueExecution(PrivilegeBackend.Execution execution) {
            queuedExecutions.addLast(execution);
        }

        @Override
        public void validateLaunchPrerequisites() {
        }

        @Override
        public ExecutionOwnershipManager.RecoveryAssessment recoverExecutions() {
            return ExecutionOwnershipManager.RecoveryAssessment.noCandidate();
        }

        int startCount() {
            return startedCommands.size();
        }

        SyncthingCommand startedCommand(int index) {
            return startedCommands.get(index);
        }

        @Override
        public Execution start(SyncthingCommand command, SyncthingEnvironment environment) {
            if (activeInvocations.incrementAndGet() > 1) {
                throw new AssertionError("Two bundled Syncthing invocations were active at once");
            }
            startedCommands.add(command);
            PrivilegeBackend.Execution delegate = queuedExecutions.removeFirst();
            return new CountedExecution(delegate, activeInvocations);
        }

        @Override
        public ConfigStorage configStorage() {
            return new InMemoryConfigStorage();
        }

        @Override
        public FolderWriteability validateCandidateFolder(String path) {
            return FolderWriteability.WRITABLE;
        }

        @Override
        public ConflictDiscoveryResult discoverConflicts(ConfiguredFolderReference folder) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FolderIgnoreResult loadFolderIgnoreList(ConfiguredFolderReference folder) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveFolderIgnoreList(ConfiguredFolderReference folder, String[] ignore) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void runFolderScripts(ConfiguredFolderReference folder, FolderEvent event) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Execution that releases the backend's active-invocation counter once its exit is observed.
     */
    private static final class CountedExecution implements PrivilegeBackend.Execution {
        private final PrivilegeBackend.Execution delegate;
        private final AtomicInteger activeInvocations;

        private CountedExecution(
                PrivilegeBackend.Execution delegate,
                AtomicInteger activeInvocations
        ) {
            this.delegate = delegate;
            this.activeInvocations = activeInvocations;
        }

        @Override
        public InputStream stdout() {
            return delegate.stdout();
        }

        @Override
        public InputStream stderr() {
            return delegate.stderr();
        }

        @Override
        public int await() throws InterruptedException {
            try {
                return delegate.await();
            } finally {
                activeInvocations.decrementAndGet();
            }
        }

        @Override
        public void destroy() {
            delegate.destroy();
        }
    }

    /**
     * Execution that stays alive until the test releases its exit gate.
     */
    private static final class GatedExecution implements PrivilegeBackend.Execution {
        private final CountDownLatch exitGate = new CountDownLatch(1);
        private volatile boolean destroyRequested;
        private volatile int exitCode;

        @Override
        public InputStream stdout() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream stderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int await() throws InterruptedException {
            exitGate.await();
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyRequested = true;
        }

        void exit(int exitCode) {
            this.exitCode = exitCode;
            exitGate.countDown();
        }
    }

    private static final class RecordingProcessLauncher implements AppUidProcessLauncher {
        private final Process process;
        private String[] argv;
        private Map<String, String> environment;

        private RecordingProcessLauncher(Process process) {
            this.process = process;
        }

        @Override
        public Process start(String[] argv, Map<String, String> environment) {
            this.argv = argv.clone();
            this.environment = environment;
            return process;
        }
    }

    private static final class RecordingProcess extends Process {
        private final InputStream stdout;
        private final InputStream stderr;
        private final int exitCode;
        private boolean destroyed;

        private RecordingProcess(String stdout, String stderr, int exitCode) {
            this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            this.stderr = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
            this.exitCode = exitCode;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }
    }

    private static final class InMemoryConfigStorage implements ConfigStorage {
        private byte[] contents = new byte[0];

        @Override
        public boolean canRead() {
            return true;
        }

        @Override
        public byte[] load() {
            return contents.clone();
        }

        @Override
        public boolean canWrite() {
            return true;
        }

        @Override
        public void save(byte[] contents) {
            this.contents = contents.clone();
        }
    }

    private static final class GatedRecordingProcess extends Process {
        private final InputStream stdout = new ByteArrayInputStream(new byte[0]);
        private final InputStream stderr = new ByteArrayInputStream(new byte[0]);
        private final CountDownLatch exitGate = new CountDownLatch(1);
        private final CountDownLatch waitStarted = new CountDownLatch(1);
        private volatile int exitCode;
        private volatile boolean destroyed;

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            waitStarted.countDown();
            exitGate.await();
            return exitCode;
        }

        @Override
        public int exitValue() {
            if (exitGate.getCount() != 0) throw new IllegalThreadStateException();
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        private void exit(int exitCode) {
            this.exitCode = exitCode;
            exitGate.countDown();
        }
    }
}
