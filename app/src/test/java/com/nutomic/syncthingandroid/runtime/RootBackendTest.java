package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * Covers the hidden root backend end to end on a deterministic fake transport.
 *
 * <p>The tests prove the behaviours the canonical superuser design depends on: construction and
 * passive calls never acquire root, exactly one audited raw {@code exec} script transports a bundled
 * invocation, the real kernel exit status reaches the existing service policy unchanged, an
 * unverified or ambiguous process is never signaled, lost root authorization fails closed instead of
 * launching a replacement, and privileged operations that belong to later slices refuse to run
 * rather than silently falling back to application-UID behaviour.</p>
 */
public class RootBackendTest {

    private static final String RECORD_FILE = "root-execution-v1.txt";

    @Test
    public void constructionAndPassiveCallsAcquireNoRoot() throws Exception {
        Fixture fixture = new Fixture();
        try {
            assertEquals(0, fixture.factory.acquireCalls());

            fixture.backend.validateLaunchPrerequisites();

            assertEquals(
                    "passive checks must stay free of root side effects",
                    0,
                    fixture.factory.acquireCalls()
            );
            assertEquals(0, fixture.device.launchScripts.size());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void serveLaunchReportsTheRealKernelExitStatus() throws Exception {
        Fixture fixture = new Fixture();
        try {
            PrivilegeBackend.Execution execution =
                    fixture.backend.start(SyncthingCommand.SERVE, environment());
            String script = onlyLaunchScript(fixture);
            FakeRootTransport.Entry launched = fixture.device.onlyLiveProcess();

            assertEquals(fixture.binary.getAbsolutePath(), execution.identity().executablePath());
            assertEquals(launched.pid, execution.identity().pid());
            assertEquals(FakeRootTransport.Device.BOOT_ID, execution.identity().bootId());
            assertEquals(FakeRootTransport.runTokenOf(script), execution.identity().runToken());
            assertFalse(execution.exitedBeforeIdentityCapture());

            fixture.device.exit(launched, 3);
            assertEquals(
                    "Syncthing's requested-restart status must reach the service policy unchanged",
                    3,
                    execution.await()
            );

            assertEquals(
                    ExecutionRecordStore.ReadResult.Status.MISSING,
                    fixture.recordStore().read().status()
            );
            assertFalse(new File(spoolDirectoryOf(fixture, script)).exists());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void launchTransportIsExactlyOneAuditedRawExecScript() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.backend.start(SyncthingCommand.SERVE, environment());
            String script = onlyLaunchScript(fixture);

            assertEquals("the launch primitive is one terminal exec", 1, occurrences(script, "exec '"));
            assertTrue(script.contains("export HOME='/home'\n"));
            assertTrue(script.contains("[ \"$(id -u)\" = \"0\" ] || exit 90\n"));
            assertTrue(
                    script.endsWith(
                            " > '" + spoolDirectoryOf(fixture, script) + "/output' 2>&1\n"
                    )
            );
            assertFalse(script.contains("--mount-master"));
            assertFalse(script.contains("sh -c"));
        } finally {
            fixture.close();
        }
    }

    @Test
    public void admissionKeepsExactlyOneBundledInvocationAtATime() throws Exception {
        Fixture fixture = new Fixture();
        try {
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            SyncthingExecution first = runtime.start(SyncthingCommand.SERVE, environment());

            assertThrows(
                    ExecutionAdmissionException.class,
                    () -> runtime.start(SyncthingCommand.GENERATE, environment())
            );

            fixture.device.exit(fixture.device.onlyLiveProcess(), 0);
            assertEquals(0, first.await());

            SyncthingExecution second = runtime.start(SyncthingCommand.GENERATE, environment());
            fixture.device.exit(fixture.device.onlyLiveProcess(), 0);
            assertEquals(0, second.await());

            assertEquals("each admitted launch transported one script", 2, fixture.device.launchScripts.size());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void oneShotLaunchKeepsOutputOutOfTheSharedLog() throws Exception {
        Fixture fixture = new Fixture();
        try {
            PrivilegeBackend.Execution execution =
                    fixture.backend.start(SyncthingCommand.DEVICE_ID, environment());
            fixture.device.exit(fixture.device.onlyLiveProcess(), 0);
            assertEquals(0, execution.await());

            fixture.backend.recoverExecutions();

            assertFalse("one-shot output must not reach the long-running log", fixture.logFile.exists());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void unverifiedLaunchLeavesTheProcessUntouched() throws Exception {
        // The creation confirmation is bounded, so the regression uses a short bound instead of
        // waiting the production deadline for a state that never becomes recognizable.
        Fixture fixture = new Fixture(60_000, 250);
        try {
            fixture.device.spawnOnLaunch = false;
            fixture.device.spawnCompetingOnLaunch = true;

            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    () -> fixture.backend.start(SyncthingCommand.SERVE, environment())
            );

            assertEquals(RootFailure.EXECUTION_VERIFICATION_FAILED, failure.failure());
            assertTrue("no signal may reach an unverified process", fixture.device.signals.isEmpty());
            assertTrue(
                    "an unverified possible launch is left alive instead of being closed",
                    liveProcessWithPath(
                            fixture.device, FakeRootTransport.Device.ROOT_SHELL_PATH
                    ).alive
            );
            assertEquals(
                    "the shell that became the unverified process must not be closed",
                    0,
                    fixture.device.launchShellCloses
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    public void candidateWithoutExactEvidenceBlocksLaunchWithoutSignal() throws Exception {
        Fixture fixture = new Fixture();
        try {
            FakeRootTransport.Entry candidate = fixture.device.addBundledCandidate("foreign-token");

            ExecutionOwnershipManager.RecoveryAssessment assessment = fixture.backend
                    .recoverExecutions();

            assertEquals(
                    ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                    assessment.classification()
            );
            assertFalse(assessment.mayLaunch());

            ExecutionRecoveryException failure = assertThrows(
                    ExecutionRecoveryException.class,
                    () -> fixture.backend.start(SyncthingCommand.SERVE, environment())
            );
            assertEquals(
                    ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                    failure.assessment().classification()
            );
            assertTrue(fixture.device.signals.isEmpty());
            assertTrue(candidate.alive);
            assertEquals(0, fixture.device.launchScripts.size());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void corruptEvidenceWithCandidateBlocksLaunch() throws Exception {
        Fixture fixture = new Fixture();
        try {
            writeText(fixture.recordFile(), "garbage");
            fixture.device.addBundledCandidate(null);

            ExecutionOwnershipManager.RecoveryAssessment assessment = fixture.backend
                    .recoverExecutions();

            assertEquals(
                    ExecutionOwnershipManager.RecordEvidence.CORRUPT,
                    assessment.recordEvidence()
            );
            assertEquals(
                    ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                    assessment.classification()
            );
            assertFalse(assessment.mayLaunch());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void missingEvidenceWithoutRootCapabilityNeverReportsLaunchableAbsence() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.device.rootAvailable = false;

            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    fixture.backend::recoverExecutions
            );

            assertEquals(RootFailure.ROOT_UNAVAILABLE, failure.failure());
            assertThrows(
                    RootTransportException.class,
                    () -> fixture.backend.start(SyncthingCommand.SERVE, environment())
            );
            assertEquals("no launch may be attempted", 0, fixture.device.launchScripts.size());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void missingEvidenceWithLiveCandidateAndNoRootAlsoFailsClosed() throws Exception {
        Fixture fixture = new Fixture();
        try {
            FakeRootTransport.Entry candidate = fixture.device.addBundledCandidate(null);
            fixture.device.rootAvailable = false;

            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    fixture.backend::recoverExecutions
            );

            assertEquals(RootFailure.ROOT_UNAVAILABLE, failure.failure());
            assertTrue(candidate.alive);
            assertTrue(fixture.device.signals.isEmpty());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void recordedExecutionWithLostAuthorizationReportsAuthorizationLost() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.recordStore().write(
                    new ExecutionIdentity(
                            4242,
                            9001,
                            FakeRootTransport.Device.BOOT_ID,
                            fixture.binary.getAbsolutePath(),
                            "token-a"
                    )
            );
            fixture.device.rootAvailable = false;

            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    fixture.backend::recoverExecutions
            );

            assertEquals(RootFailure.ROOT_AUTHORIZATION_LOST, failure.failure());
            assertEquals(
                    "durable evidence must survive a failed recovery attempt",
                    ExecutionRecordStore.ReadResult.Status.VALID,
                    fixture.recordStore().read().status()
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    public void recordedProcessThatIsGoneClearsEvidenceAndAllowsLaunch() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.recordStore().write(
                    new ExecutionIdentity(
                            4242,
                            9001,
                            FakeRootTransport.Device.BOOT_ID,
                            fixture.binary.getAbsolutePath(),
                            "token-a"
                    )
            );

            ExecutionOwnershipManager.RecoveryAssessment assessment = fixture.backend
                    .recoverExecutions();

            assertEquals(
                    ExecutionOwnershipManager.Classification.RECORDED_PROCESS_GONE,
                    assessment.classification()
            );
            assertTrue(assessment.mayLaunch());
            assertEquals(
                    ExecutionRecordStore.ReadResult.Status.MISSING,
                    fixture.recordStore().read().status()
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    public void oneRecoverySessionUsesOneBoundedShellAndClosesIt() throws Exception {
        Fixture fixture = new Fixture();
        try {
            ExecutionOwnershipManager.RecoveryAssessment assessment = fixture.backend
                    .recoverExecutions();

            assertEquals(
                    ExecutionOwnershipManager.Classification.NO_BUNDLED_CANDIDATE,
                    assessment.classification()
            );
            assertEquals(
                    "one bounded root session serves the whole recovery",
                    1,
                    fixture.device.acquisitions
            );
            assertEquals(
                    "the session shell is closed once when the session ends",
                    1,
                    fixture.device.shellCloses
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    public void privilegedOperationsFailClosedWithoutAcquiringRoot() throws Exception {
        Fixture fixture = new Fixture();
        try {
            assertNotImplemented(fixture.backend::configStorage);
            assertNotImplemented(() -> fixture.backend.validateCandidateFolder("/data"));
            assertNotImplemented(() -> fixture.backend.discoverConflicts(null));
            assertNotImplemented(() -> fixture.backend.loadFolderIgnoreList(null));
            assertNotImplemented(() -> fixture.backend.saveFolderIgnoreList(null, new String[] {"x"}));
            assertNotImplemented(() -> fixture.backend.runFolderScripts(null, FolderEvent.SYNC_COMPLETE));

            assertEquals(0, fixture.factory.acquireCalls());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void publicSurfaceExposesNoGenericRootCommandApi() throws Exception {
        for (Method method : RootBackend.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            assertNoLibsuType(method.getReturnType(), method.getName());
            for (Class<?> parameter : method.getParameterTypes()) {
                assertNoLibsuType(parameter, method.getName());
            }
        }
        for (Constructor<?> constructor : RootBackend.class.getDeclaredConstructors()) {
            if (!Modifier.isPublic(constructor.getModifiers())) {
                continue;
            }
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertNoLibsuType(parameter, "RootBackend");
            }
        }

        // The raw launch-text seam and every other direct shell mechanic live on package-private
        // types, so no caller above the transport boundary can reach a generic command or script
        // API, and the only public execution entry point speaks the closed command vocabulary.
        Method launch = RootBackend.class.getMethod(
                "start", SyncthingCommand.class, SyncthingEnvironment.class
        );
        assertTrue(Modifier.isPublic(launch.getModifiers()));
        assertTrue(SyncthingCommand.class.isEnum());
        assertFalse(
                "the raw launch seam must stay inside the transport boundary",
                Modifier.isPublic(RootShell.class.getModifiers())
        );
        assertFalse(Modifier.isPublic(LibsuRootShell.class.getModifiers()));
        assertFalse(Modifier.isPublic(LibsuRootShellFactory.class.getModifiers()));
        for (Class<?> transportType : new Class<?>[] {
                RootShell.class, LibsuRootShell.class, LibsuRootShellFactory.class
        }) {
            for (Method method : transportType.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                assertNoLibsuType(method.getReturnType(), method.getName());
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertNoLibsuType(parameter, method.getName());
                }
            }
        }
    }

    @Test
    public void revokedLifecyclePermitCreatesNoProcessWhileActivationIsPending() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.device.activationPaused = new CountDownLatch(1);
            fixture.device.activationRelease = new CountDownLatch(1);
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            LifecycleLaunchPermit permit = new LifecycleLaunchPermit();
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    runtime.startOneShotWithLifecycleCheck(
                            SyncthingCommand.SERVE,
                            environment(),
                            null,
                            permit::commitLaunch
                    );
                } catch (Throwable failure) {
                    outcome.set(failure);
                }
            }, "revoked-root-launch");
            worker.start();

            assertTrue(
                    "the root preparation must be pending before the permit is revoked",
                    fixture.device.activationPaused.await(5, TimeUnit.SECONDS)
            );
            assertEquals(LifecycleLaunchPermit.State.REVOKED, permit.revoke());
            fixture.device.activationRelease.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse("the launch attempt must finish", worker.isAlive());
            assertTrue(
                    "the revoked launch must be reported as cancelled",
                    outcome.get() instanceof LifecycleLaunchPermit.CancelledException
            );
            assertTrue(
                    "no launch script may be transported after the launch was revoked",
                    fixture.device.launchScripts.isEmpty()
            );
            assertTrue("no root process may exist", fixture.device.processes.isEmpty());
            assertEquals(
                    "the prepared launch shell must be discarded like every other acquired shell",
                    fixture.device.acquisitions,
                    fixture.device.shellCloses
            );
            assertEquals(
                    "the discarded preparation must also remove its run directory",
                    0,
                    runDirectories(fixture).length
            );
            try (OwnedExecutionShutdown.LaunchPermit reservation =
                         OwnedExecutionShutdown.acquireLaunchPermit(false)) {
                assertNotNull(
                        "the discarded preparation must release the process-start reservation",
                        reservation
                );
            }
        } finally {
            fixture.close();
        }
    }

    @Test
    public void postLaunchVerificationUsesOneBoundedHelperSession() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.backend.start(SyncthingCommand.SERVE, environment());

            assertEquals(
                    "one recovery session, the dedicated launch shell, and one verification session",
                    3,
                    fixture.device.acquisitions
            );
            assertEquals(
                    "the recovery and verification shells are closed; the launch shell stays open",
                    2,
                    fixture.device.shellCloses
            );
            assertTrue(fixture.device.onlyLiveProcess().alive);
        } finally {
            fixture.close();
        }
    }

    @Test
    public void observeSignalAndCleanupEachUseOneBoundedHelperSession() throws Exception {
        Fixture fixture = new Fixture();
        try {
            PrivilegeBackend.Execution execution =
                    fixture.backend.start(SyncthingCommand.SERVE, environment());
            ExecutionIdentity identity = execution.identity();
            int launchedPid = fixture.device.onlyLiveProcess().pid;
            int afterLaunch = fixture.device.acquisitions;

            assertEquals(ExecutionOwnershipManager.Observation.OWNED, execution.observe());
            assertEquals(afterLaunch + 1, fixture.device.acquisitions);

            assertEquals(
                    ExecutionOwnershipManager.SignalAttempt.SIGNALED,
                    execution.signalIfOwned(ExecutionOwnershipManager.Signal.SIGINT)
            );
            assertEquals(afterLaunch + 2, fixture.device.acquisitions);
            assertEquals(
                    launchedPid + ":" + ExecutionOwnershipManager.Signal.SIGINT.value(),
                    fixture.device.signals.get(0)
            );

            assertTrue(fixture.backend.clearAfterExit(identity));
            assertEquals(afterLaunch + 3, fixture.device.acquisitions);
            assertEquals(
                    "every helper session shell is closed when its operation ends",
                    fixture.device.acquisitions - 1,
                    fixture.device.shellCloses
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    public void activationDeadlineIsTheConfiguredBoundAndLateShellsAreClosed() throws Exception {
        assertEquals(
                "the caller-visible activation deadline is the canonical bound",
                60_000,
                RootBackend.ACTIVATION_TIMEOUT_MILLIS
        );
        Fixture fixture = new Fixture(250);
        try {
            fixture.device.activationPaused = new CountDownLatch(1);
            fixture.device.activationRelease = new CountDownLatch(1);

            long startedNanos = System.nanoTime();
            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    fixture.backend::recoverExecutions
            );
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;

            assertEquals(RootFailure.ROOT_ACTIVATION_TIMEOUT, failure.failure());
            assertTrue(
                    "the deadline must be the configured bound, not a longer one: " + elapsedMillis,
                    elapsedMillis < 5_000
            );
            assertEquals("no launch may be attempted", 0, fixture.device.launchScripts.size());

            fixture.device.activationRelease.countDown();
            awaitShellClosure(fixture.device, 1);
            assertEquals(
                    "a shell that arrives after the deadline must be closed",
                    1,
                    fixture.device.shellCloses
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    public void aStalledActivationDoesNotBlockLaterRootOperations() throws Exception {
        Fixture fixture = new Fixture(250);
        try {
            // The abandoned activation keeps waiting for a root prompt that never answers.
            fixture.device.activationPaused = new CountDownLatch(1);
            fixture.device.activationRelease = new CountDownLatch(1);
            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    fixture.backend::recoverExecutions
            );
            assertEquals(RootFailure.ROOT_ACTIVATION_TIMEOUT, failure.failure());

            // A later root operation must still acquire its own shell while the abandoned
            // activation is still occupying its transport call.
            fixture.device.activationPaused = null;
            ExecutionOwnershipManager.RecoveryAssessment assessment =
                    fixture.backend.recoverExecutions();
            assertEquals(
                    ExecutionOwnershipManager.Classification.NO_BUNDLED_CANDIDATE,
                    assessment.classification()
            );

            fixture.device.activationRelease.countDown();
            awaitShellClosure(fixture.device, 2);
            assertEquals(
                    "the abandoned activation's late shell is closed too",
                    fixture.device.acquisitions,
                    fixture.device.shellCloses
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    public void preExecEvidenceClassifiesASurvivingLaunchAfterAppDeath() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.backend.start(SyncthingCommand.SERVE, environment());
            String script = onlyLaunchScript(fixture);
            String runToken = FakeRootTransport.runTokenOf(script);

            // The application died after the raw exec wrote its pre-exec evidence but before the
            // canonical record was persisted.
            assertTrue(fixture.recordFile().delete());

            RootBackend recovered = fixture.newBackend();
            ExecutionOwnershipManager.RecoveryAssessment assessment =
                    recovered.recoverExecutions();

            assertEquals(
                    ExecutionOwnershipManager.Classification.OWNED_EXECUTION,
                    assessment.classification()
            );
            assertFalse(assessment.mayLaunch());
            assertEquals(runToken, assessment.ownedExecution().runToken());
            assertEquals(
                    fixture.device.onlyLiveProcess().pid,
                    assessment.ownedExecution().pid()
            );
            assertTrue("the surviving execution must not be signaled", fixture.device.signals.isEmpty());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void candidateWithoutPreExecEvidenceStaysAmbiguousAfterAppDeath() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.backend.start(SyncthingCommand.SERVE, environment());
            String script = onlyLaunchScript(fixture);

            assertTrue(fixture.recordFile().delete());
            assertTrue(
                    new File(spoolDirectoryOf(fixture, script), "evidence").delete()
            );

            RootBackend recovered = fixture.newBackend();
            ExecutionOwnershipManager.RecoveryAssessment assessment =
                    recovered.recoverExecutions();

            assertEquals(
                    ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                    assessment.classification()
            );
            assertFalse(assessment.mayLaunch());
            assertTrue(fixture.device.onlyLiveProcess().alive);
            assertTrue("an ambiguous candidate is never signaled", fixture.device.signals.isEmpty());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void serveLaunchStreamsItsOutputIntoTheSharedLogExactlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        try {
            PrivilegeBackend.Execution execution =
                    fixture.backend.start(SyncthingCommand.SERVE, environment());
            String script = onlyLaunchScript(fixture);
            File spoolDirectory = new File(spoolDirectoryOf(fixture, script));
            writeText(new File(spoolDirectory, "output"), "serve output\n");

            assertEquals("serve output is owned by the backend, not the caller", -1, execution.stdout().read());
            fixture.device.exit(fixture.device.onlyLiveProcess(), 0);
            assertEquals(0, execution.await());

            assertEquals("serve output\n", readText(fixture.logFile));
            assertFalse("a drained and exited run removes its spool", spoolDirectory.exists());

            fixture.backend.recoverExecutions();
            assertEquals(
                    "recovery must not append output that already reached the log",
                    "serve output\n",
                    readText(fixture.logFile)
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    public void failedServeReconciliationKeepsTheSpoolSoOutputIsNotLost() throws Exception {
        Fixture fixture = new Fixture();
        try {
            // A shared log that cannot be appended to models a reconciliation failure.
            File unappendableLog = new File(fixture.directory, "unappendable-log");
            assertTrue(unappendableLog.mkdirs());
            RootBackend backend = new RootBackend(
                    null,
                    fixture.directory,
                    fixture.binary,
                    unappendableLog,
                    fixture.directory,
                    fixture.factory,
                    60_000,
                    RootBackend.CREATION_CONFIRMATION_TIMEOUT_MILLIS
            );

            PrivilegeBackend.Execution execution =
                    backend.start(SyncthingCommand.SERVE, environment());
            String script = onlyLaunchScript(fixture);
            File spoolDirectory = new File(spoolDirectoryOf(fixture, script));
            writeText(new File(spoolDirectory, "output"), "serve output\n");

            fixture.device.exit(fixture.device.onlyLiveProcess(), 0);
            assertEquals(0, execution.await());

            // A repeated await must not turn the pump's I/O failure into a "reconciled" result and
            // delete output that has not reached the shared log yet.
            assertEquals(0, execution.await());

            assertTrue(
                    "unreconciled serve output keeps its spool",
                    spoolDirectory.exists()
            );

            // A later recovery with a usable log appends the retained output exactly once.
            RootBackend recovered = fixture.newBackend();
            assertTrue(recovered.recoverExecutions().mayLaunch());
            assertEquals("serve output\n", readText(fixture.logFile));
            assertFalse(
                    "the retained spool is removed once its output reached the log",
                    spoolDirectory.exists()
            );
        } finally {
            fixture.close();
        }
    }


    @Test
    public void corruptPreExecEvidenceIsReportedInsteadOfMissing() throws Exception {
        Fixture fixture = new Fixture();
        try {
            RootRunSpool spool = RootRunSpool.create(
                    new File(fixture.directory, "runs"),
                    "0f0f0f0f-1111-2222-3333-444444444444",
                    SyncthingCommand.SERVE.name()
            );
            // A partial pre-exec write: the header and first fields reached the file, the rest
            // never did.
            writeText(spool.evidenceFile(), "version=1\npid=4242\nstart_ticks=99\n");

            RootEvidenceStore store = new RootEvidenceStore(
                    fixture.recordFile(),
                    new File(fixture.directory, "runs")
            );
            assertEquals(
                    ExecutionRecordStore.ReadResult.Status.CORRUPT,
                    store.read().status()
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    public void corruptPreExecEvidenceWithUnavailableRootReportsAuthorizationLost() throws Exception {
        Fixture fixture = new Fixture();
        try {
            RootRunSpool spool = RootRunSpool.create(
                    new File(fixture.directory, "runs"),
                    "0f0f0f0f-1111-2222-3333-444444444444",
                    SyncthingCommand.SERVE.name()
            );
            writeText(spool.evidenceFile(), "version=1\npid=4242\nstart_ticks=99\n");
            fixture.device.rootAvailable = false;

            RootBackend backend = fixture.newBackend();
            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    backend::recoverExecutions
            );
            assertEquals(RootFailure.ROOT_AUTHORIZATION_LOST, failure.failure());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void invalidCustomEnvironmentNameFailsBeforeTheLifecycleCommits() throws Exception {
        Fixture fixture = new Fixture();
        try {
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            LifecycleLaunchPermit permit = new LifecycleLaunchPermit();
            Map<String, String> customVariables = new HashMap<>();
            // Custom variable names come from user configuration, so they are not guaranteed to be
            // encodable into the audited launch script.
            customVariables.put("BAD-NAME", "value");
            SyncthingEnvironment invalidEnvironment = SyncthingEnvironment.builder()
                    .home("/home")
                    .syncthingHome("/state")
                    .trace("")
                    .monitored()
                    .noUpgrade()
                    .versionExtra("app")
                    .sqliteTemporaryDirectory("/tmp")
                    .gogc(100)
                    .customVariables(customVariables)
                    .build();

            assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.startOneShotWithLifecycleCheck(
                            SyncthingCommand.SERVE,
                            invalidEnvironment,
                            null,
                            permit::commitLaunch
                    )
            );

            assertEquals(
                    "an unencodable launch must fail before the lifecycle layer commits",
                    LifecycleLaunchPermit.State.REVOKED,
                    permit.revoke()
            );
            assertTrue(
                    "an unencodable launch must transport nothing",
                    fixture.device.launchScripts.isEmpty()
            );
            assertTrue(
                    "an unencodable launch must create no process",
                    fixture.device.processes.isEmpty()
            );
            assertEquals(
                    "an unencodable launch must leave no run spool behind",
                    0,
                    runDirectories(fixture).length
            );
            assertEquals(
                    "every helper shell that ran for recovery is closed",
                    fixture.device.acquisitions,
                    fixture.device.shellCloses
            );
        } finally {
            fixture.close();
        }
    }

    /**
     * A failure while the durable pre-delivery state is armed must be settled before the lifecycle
     * layer commits to process creation: no script may be transported and no process may exist.
     */
    @Test
    public void armedLaunchStateFailureHappensBeforeTheLifecycleCheck() throws Exception {
        Fixture fixture = new Fixture();
        try {
            AtomicBoolean lifecycleCheckRan = new AtomicBoolean();
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(
                    fixture.backend,
                    waitForPendingRequests -> {
                        // Preparation only plans the run spool, so nothing exists on disk when the
                        // process-start reservation is taken. Blocking the spool directory creation
                        // here models the filesystem failure the arm step must survive before the
                        // lifecycle layer commits to process creation.
                        assertEquals(
                                "an unarmed launch must not materialize its run spool",
                                0,
                                runDirectories(fixture).length
                        );
                        assertFalse(new File(fixture.directory, "runs").exists());
                        try {
                            assertTrue(new File(fixture.directory, "runs").createNewFile());
                        } catch (IOException failure) {
                            throw new AssertionError(failure);
                        }
                        return OwnedExecutionShutdown.acquireLaunchPermit(waitForPendingRequests);
                    }
            );

            assertThrows(
                    RootTransportException.class,
                    () -> runtime.startOneShotWithLifecycleCheck(
                            SyncthingCommand.SERVE,
                            environment(),
                            null,
                            () -> lifecycleCheckRan.set(true)
                    )
            );

            assertFalse(
                    "the lifecycle layer must never commit a launch that could not be armed",
                    lifecycleCheckRan.get()
            );
            assertTrue(
                    "a launch that could not be armed transports nothing",
                    fixture.device.launchScripts.isEmpty()
            );
            assertTrue(
                    "a launch that could not be armed creates no process",
                    fixture.device.processes.isEmpty()
            );
            assertEquals(
                    "every shell acquired for the failed launch is closed",
                    fixture.device.acquisitions,
                    fixture.device.shellCloses
            );
            assertEquals(
                    "the failed launch removes its run spool",
                    0,
                    runDirectories(fixture).length
            );
        } finally {
            fixture.close();
        }
    }

    /**
     * A lifecycle cancellation that arrives after the launch was armed but before the terminal
     * transport must remove that run's durable pre-delivery state without creating a process.
     */
    @Test
    public void lifecycleRejectionAfterArmingRemovesTheArmedState() throws Exception {
        Fixture fixture = new Fixture();
        try {
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            LifecycleLaunchPermit permit = new LifecycleLaunchPermit();
            AtomicBoolean armedStateObserved = new AtomicBoolean();
            File newerRun = new File(new File(fixture.directory, "runs"), "newer-run");
            assertTrue("the newer run spool must exist before the launch is discarded", newerRun.mkdirs());

            assertThrows(
                    LifecycleLaunchPermit.CancelledException.class,
                    () -> runtime.startOneShotWithLifecycleCheck(
                            SyncthingCommand.SERVE,
                            environment(),
                            null,
                            () -> {
                                // The armed state has to exist before the lifecycle layer settles
                                // the prospective launch, because the check sits directly in front
                                // of the terminal transport.
                                armedStateObserved.set(pendingRunDirectory(fixture) != null);
                                permit.revoke();
                                permit.commitLaunch();
                            }
                    )
            );

            assertTrue(
                    "the durable pre-delivery state must exist before the lifecycle check runs",
                    armedStateObserved.get()
            );
            assertEquals(LifecycleLaunchPermit.State.REVOKED, permit.revoke());
            assertTrue(
                    "a revoked launch transports nothing",
                    fixture.device.launchScripts.isEmpty()
            );
            assertTrue("a revoked launch creates no process", fixture.device.processes.isEmpty());
            assertEquals(
                    "the prepared launch shell is discarded",
                    fixture.device.acquisitions,
                    fixture.device.shellCloses
            );
            assertEquals(
                    "the revoked launch removes only its own armed run spool",
                    newerRun,
                    runDirectories(fixture)[0]
            );
            assertEquals(
                    "no other run directory is removed",
                    1,
                    runDirectories(fixture).length
            );
            assertTrue(
                    "a fresh recovery reports the discarded launch as launchable",
                    fixture.newBackend().recoverExecutions().mayLaunch()
            );
        } finally {
            fixture.close();
        }
    }

    /**
     * While one launch is armed but not yet committed, a competing runtime that reads the durable
     * pre-delivery state must never transport a second bundled invocation.
     */
    @Test
    public void anotherRuntimeCannotLaunchWhileTheLaunchIsArmed() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch armed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            AtomicReference<SyncthingExecution> started = new AtomicReference<>();
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    started.set(runtime.startOneShotWithLifecycleCheck(
                            SyncthingCommand.SERVE,
                            environment(),
                            null,
                            () -> {
                                armed.countDown();
                                awaitLatchIgnoringInterrupts(release);
                            }
                    ));
                } catch (Throwable failure) {
                    outcome.set(failure);
                }
            }, "armed-root-launch");
            worker.start();
            try {
                assertTrue(
                        "the launch must reach its lifecycle check armed",
                        armed.await(5, TimeUnit.SECONDS)
                );
                File[] runs = runDirectories(fixture);
                assertEquals(1, runs.length);
                assertEquals(
                        "the durable pre-delivery state exists while the launch is armed",
                        ExecutionRecordStore.PendingLaunch.Status.PENDING,
                        RootSpoolEvidence.readPendingLaunch(runs[0]).status()
                );

                RootBackend competitor = fixture.newBackend();
                ExecutionOwnershipManager.RecoveryAssessment assessment =
                        competitor.recoverExecutions();
                assertEquals(
                        ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                        assessment.classification()
                );
                assertFalse("an armed launch is never launchable", assessment.mayLaunch());
                assertThrows(
                        ExecutionRecoveryException.class,
                        () -> competitor.prepareLaunch(SyncthingCommand.SERVE, environment())
                );
                assertEquals(
                        "a competing runtime transports nothing while the launch is armed",
                        0,
                        fixture.device.launchScripts.size()
                );
            } finally {
                release.countDown();
                worker.join(TimeUnit.SECONDS.toMillis(10));
            }
            assertFalse("the armed launch must finish", worker.isAlive());
            assertNull("the armed launch must complete normally", outcome.get());
            assertEquals(
                    "the armed launch transports exactly one script once the lifecycle layer commits",
                    1,
                    fixture.device.launchScripts.size()
            );

            fixture.device.exit(fixture.device.onlyLiveProcess(), 0);
            assertEquals(0, started.get().await());
            assertEquals(
                    "helper shells and launch shells stay balanced",
                    fixture.device.acquisitions,
                    fixture.device.shellCloses
            );
        } finally {
            release.countDown();
            fixture.close();
        }
    }

    @Test
    public void pausedRootActivationDoesNotHoldTheProcessStartReservation() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.device.activationPaused = new CountDownLatch(1);
            fixture.device.activationRelease = new CountDownLatch(1);
            // Pause only the activation that acquires the dedicated launch transport. The runtime's
            // recovery classification and the preparation's recovery classification each acquired
            // one helper shell before it, so the third acquisition is the launch transport.
            fixture.device.pauseWhen = () -> fixture.device.acquisitions == 3;
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    runtime.startOneShotWithLifecycleCheck(
                            SyncthingCommand.SERVE,
                            environment(),
                            null,
                            () -> { }
                    );
                } catch (Throwable failure) {
                    outcome.set(failure);
                }
            }, "paused-root-preparation");
            worker.start();

            assertTrue(
                    "the root preparation must be pending before the reservation is inspected",
                    fixture.device.activationPaused.await(5, TimeUnit.SECONDS)
            );

            // Preparation only planned this run: while the dedicated launch transport is still
            // being acquired, no run spool may exist on disk for a concurrent recovery to classify
            // and reconcile away as an attributed leftover.
            assertEquals(
                    "an unarmed launch must not materialize its run spool",
                    0,
                    runDirectories(fixture).length
            );

            // A root prompt takes seconds, so the process-start reservation must stay free while the
            // privileged preparation is still pending; taking it here must not fail closed.
            try (OwnedExecutionShutdown.LaunchPermit permit =
                         OwnedExecutionShutdown.acquireLaunchPermit(false)) {
                assertNotNull(
                        "a pending root activation must not hold the process-start reservation",
                        permit
                );
            }

            fixture.device.activationRelease.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse("the paused launch must finish", worker.isAlive());
            assertNull("the paused launch must complete normally", outcome.get());
            assertEquals(
                    "the launch must transport exactly one script",
                    1,
                    fixture.device.launchScripts.size()
            );
        } finally {
            fixture.device.activationRelease.countDown();
            fixture.close();
        }
    }

    /**
     * A preparation that is still waiting for root must not materialize its run spool, so a
     * concurrent recovery of the same installation can neither reconcile it away nor destroy any
     * other resource the preparation needs. Once root arrives, the same preparation arms its
     * complete app-owned spool under the process-start reservation and launches.
     */
    @Test
    public void pausedPreparationSurvivesAConcurrentRecoveryAndStillLaunches() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.device.activationPaused = new CountDownLatch(1);
            fixture.device.activationRelease = new CountDownLatch(1);
            fixture.device.pauseWhen = () -> fixture.device.acquisitions == 3;
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            AtomicReference<SyncthingExecution> launched = new AtomicReference<>();
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    launched.set(runtime.startOneShotWithLifecycleCheck(
                            SyncthingCommand.SERVE,
                            environment(),
                            null,
                            () -> { }
                    ));
                } catch (Throwable failure) {
                    outcome.set(failure);
                }
            }, "paused-preparation");
            worker.start();

            assertTrue(
                    "the preparation must still be waiting for its launch transport",
                    fixture.device.activationPaused.await(5, TimeUnit.SECONDS)
            );
            assertEquals(
                    "a preparation that is still waiting for root materializes nothing",
                    0,
                    runDirectories(fixture).length
            );

            // A restarted runtime recovers and reconciles the same installation while the first
            // preparation still waits for root. No run state exists yet, so the recovery must not
            // remove anything this preparation owns, and it must find no bundled candidate.
            RootBackend restarted = fixture.newBackend();
            ExecutionOwnershipManager.RecoveryAssessment assessment = restarted.recoverExecutions();
            assertEquals(
                    ExecutionOwnershipManager.Classification.NO_BUNDLED_CANDIDATE,
                    assessment.classification()
            );
            assertEquals(
                    "a concurrent recovery must not remove the paused preparation's run",
                    0,
                    runDirectories(fixture).length
            );

            fixture.device.activationRelease.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse("the paused launch must finish", worker.isAlive());
            assertNull("the paused launch must complete normally", outcome.get());
            assertNotNull("the paused launch must return its execution", launched.get());
            assertEquals(
                    "the launch must transport exactly one script",
                    1,
                    fixture.device.launchScripts.size()
            );

            // Arming created this run's complete app-owned spool before the launch was transported,
            // and the metadata written then - the command name and the consumption offset - survived
            // the launch.
            File[] runs = runDirectories(fixture);
            assertEquals(1, runs.length);
            assertEquals(SyncthingCommand.SERVE.name(), RootRunSpool.readCommandName(runs[0]));
            assertTrue(new File(runs[0], RootRunSpool.EVIDENCE_FILE).isFile());
            assertTrue(new File(runs[0], RootRunSpool.OUTPUT_FILE).isFile());
            assertTrue(new File(runs[0], RootRunSpool.COMMAND_FILE).isFile());
            assertTrue(new File(runs[0], RootRunSpool.CONSUMED_FILE).isFile());
            assertEquals("0", readText(new File(runs[0], RootRunSpool.CONSUMED_FILE)));

            // Let the served process exit so its log pump drains and stops on its own.
            fixture.device.exit(fixture.device.onlyLiveProcess(), 0);
            assertEquals(0, launched.get().await());
        } finally {
            fixture.device.activationRelease.countDown();
            fixture.close();
        }
    }

    @Test
    public void anotherRuntimeCannotLaunchWhileScriptDeliveryIsUnconfirmed() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.device.deferLaunchExecution = true;
            fixture.device.launchScriptAccepted = new CountDownLatch(1);
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    runtime.startOneShotWithLifecycleCheck(
                            SyncthingCommand.SERVE,
                            environment(),
                            null,
                            () -> { }
                    );
                } catch (Throwable failure) {
                    outcome.set(failure);
                }
            }, "unconfirmed-root-launch");
            worker.start();

            assertTrue(
                    "the shell must have accepted the script bytes before the gap is inspected",
                    fixture.device.launchScriptAccepted.await(5, TimeUnit.SECONDS)
            );
            // The transport only proves that the shell accepted the bytes, so creation is not
            // confirmed yet. The durable pre-delivery state was written before those bytes, so a
            // competing runtime cannot classify this launch as launchable even after the
            // process-start reservation was released once the handoff became recognizable.
            RootBackend competitor = fixture.newBackend();
            ExecutionOwnershipManager.RecoveryAssessment unconfirmed =
                    competitor.recoverExecutions();
            assertEquals(
                    ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                    unconfirmed.classification()
            );
            assertFalse("an unconfirmed launch is never launchable", unconfirmed.mayLaunch());
            ExecutionRecoveryException unconfirmedBlocked = assertThrows(
                    ExecutionRecoveryException.class,
                    () -> competitor.prepareLaunch(SyncthingCommand.SERVE, environment())
            );
            assertEquals(
                    ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                    unconfirmedBlocked.assessment().classification()
            );
            assertEquals(
                    "a competing launch transports nothing while creation is unconfirmed",
                    1,
                    fixture.device.launchScripts.size()
            );

            // Once the shell executes its durable evidence block, the pre-exec handoff itself
            // blocks every concurrent classifier even though the bundled process does not exist
            // yet: the evidence names the bundled executable and the recorded kernel process is
            // still the shell that wrote it.
            fixture.device.writeDeferredEvidence();
            RootBackend competing = fixture.newBackend();
            ExecutionOwnershipManager.RecoveryAssessment handoff = competing.recoverExecutions();
            assertEquals(
                    ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                    handoff.classification()
            );
            assertFalse("an in-flight launch is never launchable", handoff.mayLaunch());
            ExecutionRecoveryException blocked = assertThrows(
                    ExecutionRecoveryException.class,
                    () -> competing.prepareLaunch(SyncthingCommand.SERVE, environment())
            );
            assertEquals(
                    ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                    blocked.assessment().classification()
            );
            assertTrue("no classifier may signal an in-flight launch", fixture.device.signals.isEmpty());

            fixture.device.execDeferredLaunch();
            worker.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse("the confirmed launch must finish", worker.isAlive());
            assertNull("the confirmed launch must complete normally", outcome.get());
            assertEquals(
                    "exactly one bundled invocation may exist",
                    fixture.binary.getAbsolutePath(),
                    fixture.device.onlyLiveProcess().executablePath
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    public void finalRecoveryClassificationUsesTheSessionPreparedBeforeTheReservation()
            throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.device.activationPaused = new CountDownLatch(1);
            fixture.device.activationRelease = new CountDownLatch(1);
            // The launch acquires four shells: the recovery session of the launch request, the
            // recovery session inside prepareLaunch, the dedicated launch shell, and the
            // operation-scoped session the final recovery classification runs on.
            fixture.device.pauseWhen = () -> fixture.factory.acquireCalls() == 4;
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    runtime.startOneShotWithLifecycleCheck(
                            SyncthingCommand.SERVE,
                            environment(),
                            null,
                            () -> { }
                    );
                } catch (Throwable failure) {
                    outcome.set(failure);
                }
            }, "paused-classification-session");
            worker.start();

            assertTrue(
                    "the classification session must be pending before the reservation is inspected",
                    fixture.device.activationPaused.await(5, TimeUnit.SECONDS)
            );
            // The final recovery classification runs under the process-start reservation, so the
            // root capability it needs must already exist when the reservation is taken.
            try (OwnedExecutionShutdown.LaunchPermit permit =
                         OwnedExecutionShutdown.acquireLaunchPermit(false)) {
                assertNotNull(
                        "acquiring the classification session must not hold the process-start"
                                + " reservation",
                        permit
                );
            }
            assertTrue(
                    "no launch may be transported while root preparation is still pending",
                    fixture.device.launchScripts.isEmpty()
            );

            fixture.device.activationRelease.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse("the paused launch must finish", worker.isAlive());
            assertNull("the paused launch must complete normally", outcome.get());
            assertEquals(
                    "the launch must transport exactly one script",
                    1,
                    fixture.device.launchScripts.size()
            );
            assertEquals(
                    "the final classification and process creation must not acquire another root"
                            + " session",
                    4,
                    fixture.device.acquisitions
            );
            assertEquals(
                    "only the dedicated launch shell may stay open after the launch",
                    1,
                    fixture.device.acquisitions - fixture.device.shellCloses
            );
        } finally {
            fixture.device.activationRelease.countDown();
            fixture.close();
        }
    }

    @Test
    public void deliveryFailureAfterEvidenceKeepsTheLaunchUntouched() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.device.deliveryFailure =
                    FakeRootTransport.Device.DeliveryFailure.AFTER_EVIDENCE;

            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    () -> fixture.backend.start(SyncthingCommand.SERVE, environment())
            );

            assertEquals(RootFailure.ROOT_TRANSPORT_FAILED, failure.failure());
            assertEquals(
                    "a delivery failure must not signal an unverified possible execution",
                    0,
                    fixture.device.signals.size()
            );
            assertEquals(
                    "a delivery failure must not close the possible execution",
                    0,
                    fixture.device.launchShellCloses
            );
            File spoolDirectory = new File(
                    spoolDirectoryOf(fixture, onlyLaunchScript(fixture))
            );
            assertTrue(
                    "the durable pre-exec evidence must survive the failed delivery",
                    new File(spoolDirectory, "evidence").exists()
            );

            ExecutionOwnershipManager.RecoveryAssessment assessment =
                    fixture.backend.recoverExecutions();
            assertEquals(
                    ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                    assessment.classification()
            );
            assertFalse("an uncertain launch must never be launchable", assessment.mayLaunch());
            ExecutionRecoveryException blocked = assertThrows(
                    ExecutionRecoveryException.class,
                    () -> fixture.backend.prepareLaunch(SyncthingCommand.SERVE, environment())
            );
            assertEquals(
                    ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                    blocked.assessment().classification()
            );
            assertTrue(fixture.device.onlyLiveProcess().alive);
        } finally {
            fixture.close();
        }
    }

    @Test
    public void deliveryFailureWithoutEvidenceBlocksReplacementLaunch() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.device.deliveryFailure =
                    FakeRootTransport.Device.DeliveryFailure.BEFORE_EVIDENCE;

            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    () -> fixture.backend.start(SyncthingCommand.DEVICE_ID, environment())
            );

            assertEquals(RootFailure.ROOT_TRANSPORT_FAILED, failure.failure());
            assertTrue("nothing may be signaled", fixture.device.signals.isEmpty());
            assertEquals(
                    "an unconfirmed possible execution must not be closed",
                    0,
                    fixture.device.launchShellCloses
            );
            File spoolDirectory = new File(
                    spoolDirectoryOf(fixture, onlyLaunchScript(fixture))
            );
            assertTrue(
                    "the run spool of an unconfirmed launch must be preserved",
                    spoolDirectory.exists()
            );
            // Creation could not be ruled out, so no second process may be created against the
            // same installation while the failed launch keeps its spool. Durable pre-delivery
            // state blocks both the original backend and a fresh one.
            ExecutionRecoveryException blocked = assertThrows(
                    ExecutionRecoveryException.class,
                    () -> fixture.backend.start(SyncthingCommand.SERVE, environment())
            );
            assertEquals(
                    ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                    blocked.assessment().classification()
            );
            ExecutionRecoveryException blockedFresh = assertThrows(
                    ExecutionRecoveryException.class,
                    () -> fixture.newBackend().start(SyncthingCommand.SERVE, environment())
            );
            assertEquals(
                    ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                    blockedFresh.assessment().classification()
            );
            assertTrue(
                    "the blocked replacement launch transports nothing",
                    fixture.device.launchScripts.size() == 1
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    public void deliveryFailureAfterExecIsCleanedUpThroughExactOwnershipOnly() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.device.deliveryFailure = FakeRootTransport.Device.DeliveryFailure.AFTER_EXEC;

            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    () -> fixture.backend.start(SyncthingCommand.SERVE, environment())
            );

            assertEquals(RootFailure.ROOT_TRANSPORT_FAILED, failure.failure());
            assertEquals(
                    "the proven-owned launch is terminated through its exact ownership evidence",
                    1,
                    fixture.device.signals.size()
            );
            assertEquals(
                    "only the proven process may be signaled",
                    1,
                    fixture.device.processes.size()
            );
            assertFalse(
                    "the proven-owned launch must not stay alive",
                    fixture.device.processes.values().iterator().next().alive
            );
            assertEquals(
                    "the proven-gone launch shell is closed exactly once",
                    1,
                    fixture.device.launchShellCloses
            );
            assertEquals(
                    "conclusive cleanup leaves no run spool behind",
                    0,
                    runDirectories(fixture).length
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    public void inFlightOneShotSpoolStaysOwnedWhilePostLaunchVerificationPends() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.device.deferLaunchExecution = true;
            fixture.device.launchScriptAccepted = new CountDownLatch(1);
            // Hold the operation-scoped session of the launch open, which is the session that
            // resolves creation, while another recovery reads the same device state.
            fixture.device.gateShellsFrom = 4;
            fixture.device.gateShellsUpTo = 4;
            fixture.device.gateOnlyAfterLaunch = true;
            fixture.device.gatedShellEntered = new CountDownLatch(1);
            fixture.device.gatedShellRelease = new CountDownLatch(1);
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            AtomicReference<SyncthingExecution> started = new AtomicReference<>();
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    started.set(runtime.start(
                            SyncthingCommand.DEVICE_ID,
                            environment()
                    ));
                } catch (Throwable failure) {
                    outcome.set(failure);
                }
            }, "fast-one-shot");
            worker.start();

            assertTrue(
                    "the launch must transport its script: " + outcome.get(),
                    fixture.device.launchScriptAccepted.await(5, TimeUnit.SECONDS)
            );
            String spoolDirectory = spoolDirectoryOf(fixture, onlyLaunchScript(fixture));
            // The fast one-shot writes its operation-scoped output and exits before the launch has
            // resolved the identity of the process it created.
            fixture.device.writeDeferredEvidence();
            fixture.device.execDeferredLaunch();
            fixture.device.writeDeferredOutput("device identifier\n");
            fixture.device.exitDeferredLaunch(0);

            assertTrue(
                    "the creation confirmation must be pending",
                    fixture.device.gatedShellEntered.await(5, TimeUnit.SECONDS)
            );

            // A concurrent recovery sees a gone process and may replace it, but the run spool still
            // belongs to the pending one-shot and must survive that recovery.
            ExecutionOwnershipManager.RecoveryAssessment assessment =
                    fixture.backend.recoverExecutions();
            assertTrue("a gone one-shot may be replaced", assessment.mayLaunch());
            assertTrue(
                    "an in-flight one-shot keeps its operation-scoped output",
                    new File(spoolDirectory, "output").exists()
            );

            fixture.device.gatedShellRelease.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse("the one-shot must finish", worker.isAlive());
            assertNull("the one-shot must complete normally", outcome.get());
            assertNotNull(started.get());
            assertEquals(
                    "the one-shot still consumes its own output",
                    "device identifier\n",
                    readAll(started.get().stdout())
            );
        } finally {
            fixture.device.gatedShellRelease.countDown();
            fixture.close();
        }
    }

    @Test
    public void activationTimeoutThatRacesTheHandoffClosesTheLateShellExactlyOnce()
            throws Exception {
        Fixture fixture = new Fixture(250);
        try {
            fixture.device.activationPaused = new CountDownLatch(1);
            fixture.device.activationRelease = new CountDownLatch(1);

            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    () -> fixture.backend.start(SyncthingCommand.SERVE, environment())
            );

            assertEquals(RootFailure.ROOT_ACTIVATION_TIMEOUT, failure.failure());
            // The activation finishes after its deadline, so the shell it acquired must be closed
            // by whichever side owns it at that instant.
            fixture.device.activationRelease.countDown();
            awaitShellClosure(fixture.device, 1);
            assertEquals("the late shell is closed exactly once", 1, fixture.device.shellCloses);
            assertEquals(fixture.device.acquisitions, fixture.device.shellCloses);
            assertTrue(
                    "a timed-out activation transports no launch script",
                    fixture.device.launchScripts.isEmpty()
            );
            assertTrue("a timed-out activation creates no process", fixture.device.processes.isEmpty());
        } finally {
            fixture.device.activationRelease.countDown();
            fixture.close();
        }
    }

    @Test
    public void resetDatabaseOneShotRootOutputNeverEntersTheSharedLog() throws Exception {
        Fixture fixture = new Fixture();
        try {
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            LifecycleLaunchPermit permit = new LifecycleLaunchPermit();
            // This is the path SyncthingService.startDatabaseResetWorker uses: a database reset
            // started through the lifecycle check and then run with returnStdOut == false, which is
            // the only path that streams execution output into the shared log.
            SyncthingExecution execution = runtime.startOneShotWithLifecycleCheck(
                    SyncthingCommand.RESET_DATABASE,
                    environment(),
                    null,
                    permit::commitLaunch
            );
            String script = onlyLaunchScript(fixture);
            File spoolDirectory = new File(spoolDirectoryOf(fixture, script));
            writeText(new File(spoolDirectory, "output"), "reset output\n");

            Thread stream = execution.streamOutput(fixture.logFile, error -> { });
            fixture.device.exit(fixture.device.onlyLiveProcess(), 0);
            assertEquals(0, execution.await());
            stream.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse("the reset output stream must finish", stream.isAlive());
            assertFalse(
                    "a rooted database reset must never append its output to the long-running log",
                    fixture.logFile.exists()
            );
            assertFalse("the drained spool is removed after exit", spoolDirectory.exists());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void deviceIdOneShotOutputStaysAvailableToItsCaller() throws Exception {
        Fixture fixture = new Fixture();
        try {
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            SyncthingExecution execution = runtime.start(
                    SyncthingCommand.DEVICE_ID,
                    environment()
            );
            String script = onlyLaunchScript(fixture);
            writeText(
                    new File(new File(spoolDirectoryOf(fixture, script)), "output"),
                    "device id\n"
            );
            fixture.device.exit(fixture.device.onlyLiveProcess(), 0);

            assertEquals("device id\n", readAll(execution.stdout()));
            assertEquals(0, execution.await());
            assertFalse(
                    "one-shot output must still stay out of the long-running log",
                    fixture.logFile.exists()
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    public void applicationUidExecutionOutputStillReachesTheSharedLog() throws Exception {
        Fixture fixture = new Fixture();
        try {
            // A Normal Mode execution keeps the default output policy, so its output is still
            // appended to the shared log.
            PrivilegeBackend.Execution backendExecution = new PrivilegeBackend.Execution() {
                @Override
                public InputStream stdout() {
                    return new ByteArrayInputStream("normal output\n".getBytes(StandardCharsets.UTF_8));
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
            };
            SyncthingExecution execution = new SyncthingExecution(backendExecution, () -> { });
            assertTrue(
                    "an execution without its own output policy keeps application-UID behaviour",
                    execution.outputBelongsToSharedLog()
            );

            Thread stream = execution.streamOutput(fixture.logFile, error -> { });
            stream.join(TimeUnit.SECONDS.toMillis(10));

            assertEquals("normal output\n", readText(fixture.logFile));
        } finally {
            fixture.close();
        }
    }

    @Test
    public void successfulRunsCloseEachDedicatedLaunchShellExactlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        try {
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            for (int run = 1; run <= 3; run++) {
                SyncthingExecution execution = runtime.start(
                        SyncthingCommand.DEVICE_ID,
                        environment()
                );
                fixture.device.exit(fixture.device.onlyLiveProcess(), 0);
                assertEquals(0, execution.await());
                assertEquals(
                        "every successfully observed exit closes its dedicated launch shell",
                        run,
                        fixture.device.launchShellCloses
                );
            }
            assertEquals(
                    "helper shells and launch shells stay balanced across runs",
                    fixture.device.acquisitions,
                    fixture.device.shellCloses
            );
        } finally {
            fixture.close();
        }
    }

    /**
     * A proven process exit must complete its cleanup even when the waiter is interrupted while the
     * serve log pump is still draining: the pump must be stopped and joined before the waiter
     * returns, the run must stop owning runtime admission, the launch shell must close once, and
     * unreconciled output must keep its spool for a later recovery that is then the only writer.
     */
    @Test
    public void interruptedServeLogJoinStillFinalizesTheProvenExit() throws Exception {
        Fixture fixture = new Fixture();
        try {
            // The pump asks the launch shell whether the process exited; holding that answer open
            // parks the pump in a known state for the interrupt.
            fixture.device.pauseLaunchShellLiveness = new CountDownLatch(1);
            Set<Thread> pumpsBefore = liveThreadsNamed("root-serve-log");
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            SyncthingExecution execution = runtime.start(SyncthingCommand.SERVE, environment());
            String script = onlyLaunchScript(fixture);
            File spoolDirectory = new File(spoolDirectoryOf(fixture, script));
            writeText(new File(spoolDirectory, "output"), "serve output\n");

            fixture.device.exit(fixture.device.onlyLiveProcess(), 3);

            Set<Thread> pumpsDuring = liveThreadsNamed("root-serve-log");
            pumpsDuring.removeAll(pumpsBefore);
            assertEquals("the serve launch must own exactly one log pump", 1, pumpsDuring.size());
            Thread pump = pumpsDuring.iterator().next();
            awaitThreadWaiting(pump);

            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    execution.await();
                } catch (Throwable failure) {
                    outcome.set(failure);
                }
            }, "interrupted-root-wait");
            worker.start();
            awaitThreadWaiting(worker);
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse("the interrupted waiter must finish", worker.isAlive());
            assertTrue(
                    "the interruption must stay observable to the caller",
                    outcome.get() instanceof InterruptedException
            );
            assertFalse(
                    "the serve log pump must be stopped before the waiter returns to its caller",
                    pump.isAlive()
            );
            assertEquals(
                    "a proven exit closes its dedicated launch shell exactly once",
                    1,
                    fixture.device.launchShellCloses
            );
            assertTrue(
                    "serve output that the waiter could not confirm reconciliation keeps its spool",
                    spoolDirectory.exists()
            );
            assertTrue(
                    "the retained spool still holds this run's output",
                    new File(spoolDirectory, RootRunSpool.OUTPUT_FILE).isFile()
            );
            assertTrue(
                    "an exited execution never authorizes a signal",
                    fixture.device.signals.isEmpty()
            );

            // SyncthingRunnable retries the same await from its cleanup loop after an interrupted
            // wait. The retry must return the exit status the first wait already proved, and it
            // must not revisit the decision to keep this run's spool for a later reconciliation.
            assertEquals(
                    "a repeated await returns the exit status the first wait already proved",
                    3,
                    execution.await()
            );
            assertTrue(
                    "a repeated await must not delete a spool retained for recovery",
                    spoolDirectory.exists()
            );

            // A later launch means the interrupted wait no longer owns runtime admission.
            SyncthingExecution replacement = runtime.start(
                    SyncthingCommand.DEVICE_ID,
                    environment()
            );
            fixture.device.exit(fixture.device.onlyLiveProcess(), 0);
            assertEquals(0, replacement.await());

            // The stopped pump was the only writer that appended this run's output, so the
            // replacement recovery appends nothing a second time.
            assertEquals(
                    "the retained serve output reaches the shared log exactly once",
                    1,
                    occurrencesOf(readText(fixture.logFile), "serve output\n")
            );
            assertFalse("the reconciled spool is removed", spoolDirectory.exists());
        } finally {
            fixture.device.pauseLaunchShellLiveness.countDown();
            fixture.close();
        }
    }

    /**
     * A second backend that proves the recorded process gone and reaches a launchable recovery
     * state must still leave the run spool of an active execution alone: the original operation is
     * still draining the run's output, and reconciling it here would append that output twice or
     * destroy the part that has not reached the shared log yet.
     */
    @Test
    public void anotherBackendNeverReconcilesTheSpoolOfAnActiveServeExecution() throws Exception {
        Fixture fixture = new Fixture();
        try {
            // The pump asks the launch shell whether the process exited; holding that answer open
            // parks the pump while the run still owns its spool.
            fixture.device.pauseLaunchShellLiveness = new CountDownLatch(1);
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            SyncthingExecution execution = runtime.start(SyncthingCommand.SERVE, environment());
            File spoolDirectory = new File(spoolDirectoryOf(fixture, onlyLaunchScript(fixture)));
            writeText(new File(spoolDirectory, RootRunSpool.OUTPUT_FILE), "serve output\n");
            fixture.device.exit(fixture.device.onlyLiveProcess(), 3);

            RootBackend restarted = fixture.newBackend();
            assertTrue(
                    "the recorded process is gone, so recovery may launch again",
                    restarted.recoverExecutions().mayLaunch()
            );
            assertTrue(
                    "another backend keeps the run an active execution still owns",
                    spoolDirectory.exists()
            );
            assertTrue(
                    "another backend keeps the output an active execution still drains",
                    new File(spoolDirectory, RootRunSpool.OUTPUT_FILE).isFile()
            );
            assertEquals(
                    "another backend must not append output the active execution still drains",
                    0,
                    occurrencesOf(readLogText(fixture.logFile), "serve output\n")
            );

            // The owner finishes its own run: its pump drains the output into the shared log and
            // the proven exit releases everything the run owned.
            fixture.device.pauseLaunchShellLiveness.countDown();
            assertEquals(3, execution.await());
            assertEquals(
                    "the active execution's own drain appends its output exactly once",
                    1,
                    occurrencesOf(readLogText(fixture.logFile), "serve output\n")
            );
            assertFalse("the finished run releases its spool", spoolDirectory.exists());
            assertEquals(
                    "a later reconciliation finds nothing left to reconcile",
                    0,
                    new RootRunSpoolReconciler(
                            new File(fixture.directory, "runs"),
                            fixture.logFile,
                            fixture.directory
                    ).reconcile(null)
            );
            assertEquals(
                    "no reconciliation appended the output a second time",
                    1,
                    occurrencesOf(readLogText(fixture.logFile), "serve output\n")
            );
        } finally {
            if (fixture.device.pauseLaunchShellLiveness != null) {
                fixture.device.pauseLaunchShellLiveness.countDown();
            }
            fixture.close();
        }
    }

    /**
     * A one-shot run whose process exit becomes visible before its operation consumed the output
     * must still own its spool: a second backend that reaches a launchable recovery state may not
     * delete the run the original operation is still using.
     */
    @Test
    public void anotherBackendNeverReconcilesTheSpoolOfAnActiveOneShot() throws Exception {
        Fixture fixture = new Fixture();
        try {
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(fixture.backend);
            SyncthingExecution execution = runtime.start(SyncthingCommand.DEVICE_ID, environment());
            File spoolDirectory = new File(spoolDirectoryOf(fixture, onlyLaunchScript(fixture)));
            assertTrue("the one-shot run owns its spool", spoolDirectory.exists());

            fixture.device.exit(fixture.device.onlyLiveProcess(), 0);
            RootBackend restarted = fixture.newBackend();
            assertTrue(
                    "the recorded process is gone, so recovery may launch again",
                    restarted.recoverExecutions().mayLaunch()
            );
            assertTrue(
                    "the one-shot's run survives another backend's reconciliation",
                    spoolDirectory.exists()
            );

            assertEquals("the one-shot still reports its real exit status", 0, execution.await());
            assertFalse("the finished one-shot releases its spool", spoolDirectory.exists());
        } finally {
            fixture.close();
        }
    }

    /**
     * A one-shot whose output tail cannot be opened must still clean up through exact ownership:
     * the exactly verified process is signaled through the ownership path, its exit is proven, and
     * only then are its durable record, its run spool, and its transport released.
     */
    @Test
    public void unavailableOutputTailCleansUpOnlyTheExactlyVerifiedProcess() throws Exception {
        Fixture fixture = new Fixture();
        try {
            AtomicInteger launchedPid = new AtomicInteger();
            AtomicReference<File> spool = new AtomicReference<>();
            fixture.device.afterProcessSpawned = () -> {
                launchedPid.set(fixture.device.onlyLiveProcess().pid);
                File spoolDirectory = new File(
                        FakeRootTransport.evidencePathOf(onlyLaunchScript(fixture))
                ).getParentFile();
                spool.set(spoolDirectory);
                if (!new File(spoolDirectory, RootRunSpool.OUTPUT_FILE).delete()) {
                    throw new AssertionError("Could not remove the run output before its tail opens");
                }
            };

            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    () -> fixture.backend.start(SyncthingCommand.DEVICE_ID, environment())
            );

            assertEquals(RootFailure.ROOT_TRANSPORT_FAILED, failure.failure());
            assertEquals(
                    "only the exactly verified process is signaled, and only through the"
                            + " ownership path",
                    launchedPid.get() + ":9",
                    fixture.device.signals.get(0)
            );
            assertEquals("no other signal is sent", 1, fixture.device.signals.size());
            for (String operation : fixture.device.shellOperations) {
                if (operation.endsWith(":sendSignal")) {
                    assertFalse(
                            "a signal never travels through the dedicated launch shell",
                            operation.startsWith(launchShellIndex(fixture.device) + ":")
                    );
                }
            }
            assertFalse(
                    "the proven exit leaves no live bundled process",
                    fixture.device.processes.get(launchedPid.get()).alive
            );
            assertFalse("the proven exit releases the run spool", spool.get().exists());
            assertFalse(
                    "the proven exit releases the durable evidence",
                    new File(spool.get(), RootRunSpool.EVIDENCE_FILE).exists()
            );
            assertEquals(
                    "the exited launch shell is closed exactly once",
                    1,
                    fixture.device.launchShellCloses
            );
        } finally {
            fixture.close();
        }
    }

    /**
     * When the exact process cannot be signaled or its exit cannot be proven, an output-tail
     * failure must leave the possible execution completely untouched: no transport is closed, no
     * evidence or spool is deleted, and a later recovery still blocks a replacement launch.
     */
    @Test
    public void unavailableOutputTailKeepsAnUnprovableProcessUntouched() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.device.rejectSignals = true;
            AtomicInteger launchedPid = new AtomicInteger();
            AtomicReference<File> spool = new AtomicReference<>();
            fixture.device.afterProcessSpawned = () -> {
                launchedPid.set(fixture.device.onlyLiveProcess().pid);
                File spoolDirectory = new File(
                        FakeRootTransport.evidencePathOf(onlyLaunchScript(fixture))
                ).getParentFile();
                spool.set(spoolDirectory);
                if (!new File(spoolDirectory, RootRunSpool.OUTPUT_FILE).delete()) {
                    throw new AssertionError("Could not remove the run output before its tail opens");
                }
            };

            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    () -> fixture.backend.start(SyncthingCommand.DEVICE_ID, environment())
            );

            assertEquals(RootFailure.ROOT_TRANSPORT_FAILED, failure.failure());
            assertTrue(
                    "the possible execution is left running",
                    fixture.device.processes.get(launchedPid.get()).alive
            );
            assertTrue("its run spool stays in place", spool.get().exists());
            assertTrue(
                    "its durable evidence stays in place",
                    new File(spool.get(), RootRunSpool.EVIDENCE_FILE).isFile()
            );
            assertEquals(
                    "no launch shell is closed while its process may still be live",
                    0,
                    fixture.device.launchShellCloses
            );
            assertTrue("no signal reached the possible execution", fixture.device.signals.isEmpty());

            // The failed launch gives up its in-process spool ownership, so a later recovery can
            // still take the run, and its durable evidence still blocks a replacement launch.
            RootRunSpool recoverable = RootRunSpool.plan(
                    new File(fixture.directory, "runs"),
                    FakeRootTransport.runTokenOf(onlyLaunchScript(fixture)),
                    SyncthingCommand.DEVICE_ID.name()
            );
            assertTrue(
                    "a failed launch leaks no in-process spool ownership",
                    recoverable.acquireLease()
            );
            recoverable.releaseLease();
            ExecutionOwnershipManager.RecoveryAssessment blocked =
                    fixture.newBackend().recoverExecutions();
            assertFalse("a live possible execution never permits a replacement", blocked.mayLaunch());
            assertEquals(
                    ExecutionOwnershipManager.Classification.OWNED_EXECUTION,
                    blocked.classification()
            );
            assertTrue("recovery signals nothing", fixture.device.signals.isEmpty());
            assertTrue("the run stays recoverable", spool.get().exists());
        } finally {
            fixture.close();
        }
    }

    /**
     * A launch whose transport accepted script bytes but never wrote pre-exec evidence must remain
     * non-launchable after the application process that prepared it is gone.
     */
    @Test
    public void appDeathBeforePreExecEvidenceBlocksReplacement() throws Exception {
        Fixture fixture = new Fixture(60_000, 250);
        try {
            fixture.device.deferLaunchExecution = true;
            fixture.device.launchScriptAccepted = new CountDownLatch(1);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = startAbandonedLaunchOnWorker(fixture, outcome);
            try {
                assertTrue(
                        "the shell must accept the launch script before the application dies",
                        fixture.device.launchScriptAccepted.await(5, TimeUnit.SECONDS)
                );

                // Only the durable files and the live shell that may still execute the accepted
                // script survive the application process.
                RootBackend restarted = fixture.newBackend();
                ExecutionOwnershipManager.RecoveryAssessment assessment =
                        restarted.recoverExecutions();
                assertEquals(
                        ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                        assessment.classification()
                );
                assertFalse("a possible launch is never launchable", assessment.mayLaunch());

                ExecutionRecoveryException blocked = assertThrows(
                        ExecutionRecoveryException.class,
                        () -> restarted.prepareLaunch(SyncthingCommand.SERVE, environment())
                );
                assertEquals(
                        ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                        blocked.assessment().classification()
                );
                assertEquals(
                        "a blocked replacement launch transports nothing",
                        1,
                        fixture.device.launchScripts.size()
                );
                assertTrue("a possible launch is never signaled", fixture.device.signals.isEmpty());
            } finally {
                fixture.device.exitDeferredLaunch(0);
                worker.join(TimeUnit.SECONDS.toMillis(10));
            }
            assertFalse("the abandoned launch attempt must finish", worker.isAlive());
        } finally {
            fixture.close();
        }
    }

    /**
     * A durable pending launch must keep following the same process through evidence, handoff, and
     * terminal {@code exec} after an application restart, without a duplicate launch or a signal.
     */
    @Test
    public void pendingLaunchProgressesThroughEvidenceAndExecAfterRestart() throws Exception {
        Fixture fixture = new Fixture(60_000, 2_000);
        try {
            fixture.device.deferLaunchExecution = true;
            fixture.device.launchScriptAccepted = new CountDownLatch(1);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = startAbandonedLaunchOnWorker(fixture, outcome);
            assertTrue(
                    "the shell must accept the launch script before the restart",
                    fixture.device.launchScriptAccepted.await(5, TimeUnit.SECONDS)
            );

            ExecutionOwnershipManager.RecoveryAssessment pending =
                    fixture.newBackend().recoverExecutions();
            assertEquals(
                    ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                    pending.classification()
            );
            assertFalse("a pre-delivery launch is never launchable", pending.mayLaunch());

            fixture.device.writeDeferredEvidence();
            ExecutionOwnershipManager.RecoveryAssessment handoff =
                    fixture.newBackend().recoverExecutions();
            assertEquals(
                    ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                    handoff.classification()
            );
            assertFalse("an in-flight handoff is never launchable", handoff.mayLaunch());

            fixture.device.execDeferredLaunch();
            ExecutionOwnershipManager.RecoveryAssessment owned =
                    fixture.newBackend().recoverExecutions();
            assertEquals(
                    ExecutionOwnershipManager.Classification.OWNED_EXECUTION,
                    owned.classification()
            );
            assertEquals(
                    fixture.binary.getAbsolutePath(),
                    owned.ownedExecution().executablePath()
            );
            assertEquals(
                    "the restarted process must not launch a duplicate invocation",
                    1,
                    fixture.device.launchScripts.size()
            );
            assertTrue("recovery never signals a launch it recognizes", fixture.device.signals.isEmpty());

            worker.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse("the original launch must complete", worker.isAlive());
            assertNull("the original launch must reach its terminal exec", outcome.get());
        } finally {
            fixture.close();
        }
    }

    /**
     * A pending launch whose exact process is proven gone is cleared token-safely, so a replacement
     * launch becomes possible again.
     */
    /**
     * Pauses the audited launch protocol at its evidence transition: the root shell has written
     * its complete version-1 evidence into the app-owned staging file but has not atomically
     * replaced the durable pending record yet. A restarted application must still see the pending
     * transport state, so there is no interval in which both the durable pending state and the
     * complete pre-exec evidence are absent.
     */
    @Test
    public void stagedEvidenceTransitionKeepsPendingStateAuthoritative() throws Exception {
        Fixture fixture = new Fixture(60_000, 2_000);
        try {
            fixture.device.deferLaunchExecution = true;
            fixture.device.launchScriptAccepted = new CountDownLatch(1);
            fixture.device.launchEvidenceStaged = new CountDownLatch(1);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = startAbandonedLaunchOnWorker(fixture, outcome);
            try {
                assertTrue(
                        "the shell must accept the launch script before the evidence transition",
                        fixture.device.launchScriptAccepted.await(5, TimeUnit.SECONDS)
                );
                File spoolDirectory = new File(spoolDirectoryOf(fixture, onlyLaunchScript(fixture)));
                File evidence = new File(spoolDirectory, RootRunSpool.EVIDENCE_FILE);
                File staging = new File(spoolDirectory, RootRunSpool.EVIDENCE_STAGING_FILE);
                assertTrue("the app pre-creates the staging file", staging.exists());

                fixture.device.stageDeferredEvidence();
                assertTrue(fixture.device.launchEvidenceStaged.await(5, TimeUnit.SECONDS));

                assertTrue(
                        "the staged file holds the complete versioned evidence",
                        readText(staging).contains("version=1")
                );
                assertEquals(
                        "the durable record stays the pending pre-delivery state",
                        ExecutionRecordStore.PendingLaunch.Status.PENDING,
                        RootSpoolEvidence.readPendingLaunch(spoolDirectory).status()
                );
                assertFalse(
                        "the durable record never exposes partial pre-exec evidence",
                        readText(evidence).contains("version=1")
                );

                RootBackend restarted = fixture.newBackend();
                ExecutionOwnershipManager.RecoveryAssessment staged = restarted.recoverExecutions();
                assertFalse("a staged launch is never launchable", staged.mayLaunch());
                assertEquals(
                        ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                        staged.classification()
                );

                RootRunSpoolReconciler reconciler = new RootRunSpoolReconciler(
                        new File(fixture.directory, "runs"),
                        fixture.logFile,
                        fixture.directory
                );
                assertEquals(
                        "a staged launch is not counted as a reconciled leftover run",
                        0,
                        reconciler.reconcile(null)
                );
                assertTrue("reconciliation keeps the staged spool", spoolDirectory.exists());
                assertTrue("reconciliation keeps the staging file", staging.exists());

                fixture.device.replaceDeferredEvidence();
                assertEquals(
                        "the atomic replacement persists the complete evidence",
                        ExecutionRecordStore.PendingLaunch.Status.NONE,
                        RootSpoolEvidence.readPendingLaunch(spoolDirectory).status()
                );
                ExecutionOwnershipManager.RecoveryAssessment handoff =
                        fixture.newBackend().recoverExecutions();
                assertEquals(
                        ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                        handoff.classification()
                );
                assertFalse("a pre-exec handoff is never launchable", handoff.mayLaunch());

                fixture.device.execDeferredLaunch();
                ExecutionOwnershipManager.RecoveryAssessment owned =
                        fixture.newBackend().recoverExecutions();
                assertEquals(
                        ExecutionOwnershipManager.Classification.OWNED_EXECUTION,
                        owned.classification()
                );
                assertEquals(1, fixture.device.launchScripts.size());
                assertTrue("recovery never signals a launch it recognizes", fixture.device.signals.isEmpty());
            } finally {
                fixture.device.exitDeferredLaunch(0);
                worker.join(TimeUnit.SECONDS.toMillis(10));
            }
        } finally {
            fixture.close();
        }
    }

    /**
     * Proves the dedicated launch shell is a raw launch transport: it performs only the activation
     * boundary reads that capture its immutable kernel identity and then exactly one audited
     * terminal script. No procfs, boot-ID, run-token, signal, or generic helper operation runs on
     * the launch shell after it becomes the dedicated launch transport.
     */
    @Test
    public void dedicatedLaunchShellIsARawLaunchTransportOnly() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.backend.start(SyncthingCommand.SERVE, environment());

            int launchShell = launchShellIndex(fixture.device);
            assertEquals(
                    "the launch transport captures identity once, then executes one raw script",
                    java.util.Arrays.asList(
                            "pid",
                            "listProcesses",
                            "readBootId",
                            "execTerminalScript"
                    ),
                    operationsOf(fixture.device, launchShell)
            );
        } finally {
            fixture.close();
        }
    }

    /**
     * A pending launch proven gone is still not launchable while a bundled candidate exists:
     * recovery rescans bundled candidates after the token-safe cleanup, reports ambiguity, and
     * keeps the durable evidence so a possible bundled invocation is never forgotten.
     */
    @Test
    public void provenGonePendingLaunchWithRefreshedCandidateStaysBlocked() throws Exception {
        Fixture fixture = new Fixture(60_000, 5_000);
        try {
            fixture.device.deferLaunchExecution = true;
            fixture.device.launchScriptAccepted = new CountDownLatch(1);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = startAbandonedLaunchOnWorker(fixture, outcome);
            assertTrue(
                    "the shell must accept the launch script before it disappears",
                    fixture.device.launchScriptAccepted.await(5, TimeUnit.SECONDS)
            );
            File spoolDirectory = new File(spoolDirectoryOf(fixture, onlyLaunchScript(fixture)));

            fixture.device.exitDeferredLaunch(0);
            worker.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse("the abandoned launch attempt must finish", worker.isAlive());

            FakeRootTransport.Entry candidate = fixture.device.addBundledCandidate("foreign-token");

            RootBackend restarted = fixture.newBackend();
            ExecutionOwnershipManager.RecoveryAssessment assessment = restarted.recoverExecutions();

            assertEquals(
                    "a refreshed bundled candidate outranks the stale empty snapshot",
                    ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                    assessment.classification()
            );
            assertEquals(
                    ExecutionOwnershipManager.CandidateEvidence.UNOWNED_CANDIDATE,
                    assessment.candidateEvidence()
            );
            assertFalse("a refreshed candidate never permits replacement", assessment.mayLaunch());
            assertEquals(
                    "only the matching pending state is cleared token-safely",
                    ExecutionRecordStore.PendingLaunch.Status.NONE,
                    RootSpoolEvidence.readPendingLaunch(spoolDirectory).status()
            );
            assertTrue("a blocked recovery keeps the run spool", spoolDirectory.exists());
            assertTrue(
                    "the kept run spool still carries its operation output",
                    new File(spoolDirectory, RootRunSpool.OUTPUT_FILE).exists()
            );
            assertTrue(candidate.alive);
            assertEquals("no replacement launch is transported", 1, fixture.device.launchScripts.size());
            assertTrue("candidate discovery never authorizes a signal", fixture.device.signals.isEmpty());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void provenGonePendingLaunchCanBeClearedAfterRestart() throws Exception {
        // The abandoned launch must observe its own process disappearing, so its creation
        // confirmation is given a bound that leaves room for the inspection that follows the exit.
        Fixture fixture = new Fixture(60_000, 5_000);
        try {
            fixture.device.deferLaunchExecution = true;
            fixture.device.launchScriptAccepted = new CountDownLatch(1);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = startAbandonedLaunchOnWorker(fixture, outcome);
            assertTrue(
                    "the shell must accept the launch script before it disappears",
                    fixture.device.launchScriptAccepted.await(5, TimeUnit.SECONDS)
            );
            File spoolDirectory = new File(spoolDirectoryOf(fixture, onlyLaunchScript(fixture)));
            File evidence = new File(spoolDirectory, RootRunSpool.EVIDENCE_FILE);
            assertEquals(
                    ExecutionRecordStore.PendingLaunch.Status.PENDING,
                    RootSpoolEvidence.readPendingLaunch(spoolDirectory).status()
            );

            // The exact pending process disappears before it wrote any pre-exec evidence.
            fixture.device.exitDeferredLaunch(0);
            worker.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse("the abandoned launch attempt must finish", worker.isAlive());

            RootBackend restarted = fixture.newBackend();
            ExecutionOwnershipManager.RecoveryAssessment cleared = restarted.recoverExecutions();
            assertTrue("a proven gone pending launch permits a replacement", cleared.mayLaunch());
            assertEquals(
                    ExecutionOwnershipManager.Classification.NO_BUNDLED_CANDIDATE,
                    cleared.classification()
            );
            assertFalse("only the matching pending evidence is cleared", evidence.exists());
            assertTrue(
                    "the run an in-process execution still owns is not reconciled away",
                    spoolDirectory.exists()
            );

            // The application process that owned the abandoned attempt is gone, so its ephemeral
            // run lease died with it and a restarted application reconciles the leftover run.
            fixture.modelOwnerProcessDeath(spoolDirectory);
            assertTrue(
                    "a restarted application may launch after the expired run is reconciled",
                    restarted.recoverExecutions().mayLaunch()
            );
            assertFalse("its expired run directory is reconciled away", spoolDirectory.exists());

            fixture.device.deferLaunchExecution = false;
            // The replacement is a fresh application process: the backend that owned the abandoned
            // attempt still holds its in-memory run, exactly as the dying process did.
            PrivilegeBackend.Execution replacement =
                    restarted.start(SyncthingCommand.DEVICE_ID, environment());
            assertEquals(2, fixture.device.launchScripts.size());
            assertNotNull(replacement.identity());
            assertTrue(
                    "the replacement launch owns the live bundled process",
                    liveProcessWithPath(fixture.device, fixture.binary.getAbsolutePath()).alive
            );
        } finally {
            fixture.close();
        }
    }

    /** Unknown inspection of a pending launch must fail closed and keep every durable file. */
    @Test
    public void pendingLaunchWithUnknownInspectionFailsClosed() throws Exception {
        Fixture fixture = new Fixture(60_000, 250);
        try {
            fixture.device.deferLaunchExecution = true;
            fixture.device.launchScriptAccepted = new CountDownLatch(1);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = startAbandonedLaunchOnWorker(fixture, outcome);
            try {
                assertTrue(
                        "the shell must accept the launch script before inspection fails",
                        fixture.device.launchScriptAccepted.await(5, TimeUnit.SECONDS)
                );
                File spoolDirectory = new File(
                        spoolDirectoryOf(fixture, onlyLaunchScript(fixture))
                );
                File evidence = new File(spoolDirectory, RootRunSpool.EVIDENCE_FILE);

                // The pending process cannot be inspected, for example because its executable link
                // is unreadable, so recovery must never conclude that the launch is gone.
                fixture.device.deferredLaunchEntry().executablePath = null;

                RootBackend restarted = fixture.newBackend();
                ExecutionRecoveryException blocked = assertThrows(
                        ExecutionRecoveryException.class,
                        () -> restarted.prepareLaunch(SyncthingCommand.SERVE, environment())
                );
                assertEquals(
                        ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                        blocked.assessment().classification()
                );
                assertTrue(
                        "unknown inspection never authorizes a signal",
                        fixture.device.signals.isEmpty()
                );
                assertTrue("unknown inspection keeps the pending evidence", evidence.exists());
                assertTrue("unknown inspection keeps the pending spool", spoolDirectory.exists());
                assertEquals(
                        "unknown inspection transports no replacement launch",
                        1,
                        fixture.device.launchScripts.size()
                );
            } finally {
                fixture.device.exitDeferredLaunch(0);
                worker.join(TimeUnit.SECONDS.toMillis(10));
            }
        } finally {
            fixture.close();
        }
    }

    /** Durable pending state without root capability must report authorization loss. */
    @Test
    public void pendingLaunchWithLostRootAuthorizationFailsClosed() throws Exception {
        Fixture fixture = new Fixture(60_000, 250);
        try {
            fixture.device.deferLaunchExecution = true;
            fixture.device.launchScriptAccepted = new CountDownLatch(1);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = startAbandonedLaunchOnWorker(fixture, outcome);
            try {
                assertTrue(
                        "the shell must accept the launch script before root is lost",
                        fixture.device.launchScriptAccepted.await(5, TimeUnit.SECONDS)
                );
                File spoolDirectory = new File(
                        spoolDirectoryOf(fixture, onlyLaunchScript(fixture))
                );
                File evidence = new File(spoolDirectory, RootRunSpool.EVIDENCE_FILE);

                fixture.device.rootAvailable = false;
                RootBackend restarted = fixture.newBackend();
                RootTransportException failure = assertThrows(
                        RootTransportException.class,
                        restarted::recoverExecutions
                );
                assertEquals(RootFailure.ROOT_AUTHORIZATION_LOST, failure.failure());
                assertTrue("lost root never deletes pending evidence", evidence.exists());
                assertTrue("lost root never deletes the pending spool", spoolDirectory.exists());
                assertTrue("lost root never signals a possible launch", fixture.device.signals.isEmpty());
                assertEquals(
                        "lost root never falls back to another transport",
                        1,
                        fixture.device.launchScripts.size()
                );
            } finally {
                fixture.device.exitDeferredLaunch(0);
                worker.join(TimeUnit.SECONDS.toMillis(10));
            }
        } finally {
            fixture.close();
        }
    }

    /** Reconciliation must never remove a run whose delivery could still become Syncthing. */
    @Test
    public void reconciliationNeverDeletesPendingLaunchSpool() throws Exception {
        Fixture fixture = new Fixture(60_000, 250);
        try {
            fixture.device.deferLaunchExecution = true;
            fixture.device.launchScriptAccepted = new CountDownLatch(1);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = startAbandonedLaunchOnWorker(fixture, outcome);
            try {
                assertTrue(
                        "the shell must accept the launch script before reconciliation",
                        fixture.device.launchScriptAccepted.await(5, TimeUnit.SECONDS)
                );
                File spoolDirectory = new File(
                        spoolDirectoryOf(fixture, onlyLaunchScript(fixture))
                );
                RootBackend restarted = fixture.newBackend();
                assertFalse(
                        "a pending launch is never launchable",
                        restarted.recoverExecutions().mayLaunch()
                );

                RootRunSpoolReconciler reconciler = new RootRunSpoolReconciler(
                        new File(fixture.directory, "runs"),
                        fixture.logFile,
                        fixture.directory
                );
                assertEquals(
                        "a pending run is not counted as reconciled",
                        0,
                        reconciler.reconcile(null)
                );
                assertTrue(
                        "reconciliation keeps the run that may still become Syncthing",
                        spoolDirectory.exists()
                );
                assertTrue(
                        "reconciliation keeps the pending evidence",
                        new File(spoolDirectory, RootRunSpool.EVIDENCE_FILE).exists()
                );
                assertTrue(
                        "reconciliation keeps the pending output spool",
                        new File(spoolDirectory, RootRunSpool.OUTPUT_FILE).exists()
                );
            } finally {
                fixture.device.exitDeferredLaunch(0);
                worker.join(TimeUnit.SECONDS.toMillis(10));
            }
        } finally {
            fixture.close();
        }
    }

    /**
     * Reproduces the previous defect explicitly: an uncertain launch that never wrote pre-exec
     * evidence must not look like an absent candidate merely because the application process that
     * prepared it is gone.
     */
    @Test
    public void uncertainLaunchWithoutEvidenceIsNeverReportedAsNoCandidate() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.device.deliveryFailure =
                    FakeRootTransport.Device.DeliveryFailure.BEFORE_EVIDENCE;
            RootTransportException failure = assertThrows(
                    RootTransportException.class,
                    () -> fixture.backend.start(SyncthingCommand.SERVE, environment())
            );
            assertEquals(RootFailure.ROOT_TRANSPORT_FAILED, failure.failure());

            File spoolDirectory = new File(spoolDirectoryOf(fixture, onlyLaunchScript(fixture)));
            File evidence = new File(spoolDirectory, RootRunSpool.EVIDENCE_FILE);
            assertTrue(
                    "the launch must persist its pre-delivery state before delivering bytes",
                    evidence.length() > 0
            );
            assertEquals(
                    ExecutionRecordStore.PendingLaunch.Status.PENDING,
                    RootSpoolEvidence.readPendingLaunch(spoolDirectory).status()
            );

            RootBackend restarted = fixture.newBackend();
            ExecutionOwnershipManager.RecoveryAssessment assessment =
                    restarted.recoverExecutions();
            assertTrue(
                    "an uncertain launch is never reported as an absent candidate",
                    assessment.classification()
                            != ExecutionOwnershipManager.Classification.NO_BUNDLED_CANDIDATE
            );
            assertEquals(
                    ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                    assessment.classification()
            );
            assertFalse("an uncertain launch is never launchable", assessment.mayLaunch());

            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(restarted);
            ExecutionRecoveryException blocked = assertThrows(
                    ExecutionRecoveryException.class,
                    () -> runtime.startOneShotWithLifecycleCheck(
                            SyncthingCommand.SERVE,
                            environment(),
                            null,
                            () -> { }
                    )
            );
            assertEquals(
                    ExecutionOwnershipManager.Classification.LAUNCH_IN_FLIGHT,
                    blocked.assessment().classification()
            );
            assertEquals(
                    "no replacement launch is transported",
                    1,
                    fixture.device.launchScripts.size()
            );
            assertTrue("no signal reaches the uncertain launch", fixture.device.signals.isEmpty());
        } finally {
            fixture.close();
        }
    }

    /**
     * Starts one bundled launch on a worker thread, so a test can act like the application process
     * that prepared it disappearing while the transport keeps its own state.
     */
    private static Thread startAbandonedLaunchOnWorker(
            Fixture fixture,
            AtomicReference<Throwable> outcome
    ) {
        Thread worker = new Thread(() -> {
            try {
                fixture.backend.start(SyncthingCommand.DEVICE_ID, environment());
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        }, "abandoned-root-launch");
        worker.start();
        return worker;
    }

    private static void assertNotImplemented(Runnable operation) {
        RootTransportException failure = assertThrows(RootTransportException.class, operation::run);
        assertEquals(RootFailure.PRIVILEGED_STATE_NOT_IMPLEMENTED, failure.failure());
    }

    private static void assertNoLibsuType(Class<?> type, String owner) {
        String name = type.getName();
        assertFalse(
                owner + " must not expose the libsu type " + name,
                name.startsWith("com.topjohnwu")
        );
    }

    /** Returns the command-like operations one root shell index ran, in order. */
    private static java.util.List<String> operationsOf(FakeRootTransport.Device device, int shellIndex) {
        java.util.List<String> operations = new java.util.ArrayList<>();
        String prefix = shellIndex + ":";
        for (String entry : device.shellOperations) {
            if (entry.startsWith(prefix)) {
                operations.add(entry.substring(prefix.length()));
            }
        }
        return operations;
    }

    /** Returns the shell index that transported the launch script. */
    private static int launchShellIndex(FakeRootTransport.Device device) {
        for (String entry : device.shellOperations) {
            if (entry.endsWith(":execTerminalScript")) {
                return Integer.parseInt(entry.substring(0, entry.indexOf(':')));
            }
        }
        throw new AssertionError("No root shell transported a launch script");
    }

    private static String onlyLaunchScript(Fixture fixture) {
        assertEquals(1, fixture.device.launchScripts.size());
        return fixture.device.launchScripts.get(0);
    }

    /** Returns the live process that currently carries one executable path. */
    private static FakeRootTransport.Entry liveProcessWithPath(
            FakeRootTransport.Device device,
            String executablePath
    ) {
        for (FakeRootTransport.Entry entry : device.processes.values()) {
            if (entry.alive && executablePath.equals(entry.executablePath)) {
                return entry;
            }
        }
        throw new AssertionError("Expected a live process running " + executablePath);
    }

    private static String spoolDirectoryOf(Fixture fixture, String script) {
        return new File(
                new File(fixture.directory, "runs"),
                FakeRootTransport.runTokenOf(script)
        ).getAbsolutePath();
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static SyncthingEnvironment environment() {
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

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Reads a log that may not exist yet, as before anything appended to it. */
    private static String readLogText(File log) throws IOException {
        return log.exists() ? readText(log) : "";
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Waits until the expected number of shells was closed. */
    private static void awaitShellClosure(FakeRootTransport.Device device, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (device.shellCloses < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    /** Waits until a worker thread parked in a blocking wait, failing the test on timeout. */
    private static void awaitThreadWaiting(Thread worker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread.State state = worker.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("The worker thread never reached a blocking wait");
    }

    /** Returns every live thread with the given name at this instant. */
    private static Set<Thread> liveThreadsNamed(String name) {
        Set<Thread> threads = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && name.equals(thread.getName())) {
                threads.add(thread);
            }
        }
        return threads;
    }

    /** Counts the non-overlapping occurrences of one marker inside a text. */
    private static int occurrencesOf(String text, String marker) {
        int count = 0;
        int index = text.indexOf(marker);
        while (index >= 0) {
            count++;
            index = text.indexOf(marker, index + marker.length());
        }
        return count;
    }

    /** Returns the run directory that currently carries durable pre-delivery state, if any. */
    private static File pendingRunDirectory(Fixture fixture) {
        for (File directory : runDirectories(fixture)) {
            if (RootSpoolEvidence.readPendingLaunch(directory).status()
                    == ExecutionRecordStore.PendingLaunch.Status.PENDING) {
                return directory;
            }
        }
        return null;
    }

    /** Waits on a test latch, restoring the interrupt flag instead of failing the launch. */
    private static void awaitLatchIgnoringInterrupts(CountDownLatch latch) {
        boolean released = false;
        while (!released) {
            try {
                released = latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }


    private static File[] runDirectories(Fixture fixture) {
        File[] directories = new File(fixture.directory, "runs").listFiles();
        return directories == null ? new File[0] : directories;
    }

    private static String readAll(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    /** One backend, fake device, and temporary state directory owned by a single test. */
    private static final class Fixture {
        final FakeRootTransport.Device device = new FakeRootTransport.Device();
        final FakeRootTransport.Factory factory = new FakeRootTransport.Factory(device);
        final File directory;
        final File binary;
        final File logFile;
        final RootBackend backend;

        Fixture() throws IOException {
            this(60_000);
        }

        Fixture(long activationTimeoutMillis) throws IOException {
            this(activationTimeoutMillis, RootBackend.CREATION_CONFIRMATION_TIMEOUT_MILLIS);
        }

        Fixture(long activationTimeoutMillis, long creationConfirmationTimeoutMillis)
                throws IOException {
            directory = File.createTempFile("root-backend", "");
            if (!directory.delete() || !directory.mkdir()) {
                throw new IOException("Could not prepare a temporary directory");
            }
            binary = new File(directory, "libsyncthingnative.so");
            writeText(binary, "bundled binary");
            logFile = new File(directory, "syncthing.log");
            backend = new RootBackend(
                    null,
                    directory,
                    binary,
                    logFile,
                    directory,
                    factory,
                    activationTimeoutMillis,
                    creationConfirmationTimeoutMillis
            );
        }

        /** Creates a second backend over the same state, as a restarted application would. */
        RootBackend newBackend() {
            return new RootBackend(
                    null,
                    directory,
                    binary,
                    logFile,
                    directory,
                    factory,
                    60_000,
                    RootBackend.CREATION_CONFIRMATION_TIMEOUT_MILLIS
            );
        }

        File recordFile() {
            return new File(directory, RECORD_FILE);
        }

        RootExecutionRecordStore recordStore() {
            return new RootExecutionRecordStore(recordFile());
        }

        /**
         * Models the death of the application process that owned a run.
         *
         * <p>When an application process dies, the operating system releases every file lock it
         * held, so a restarted application opens the run as a fresh owner. A unit test cannot kill
         * the JVM that holds the lease, so this helper removes the run's lease file: the next owner
         * creates and locks a new file, exactly as a restarted application process does, while the
         * dead owner never touches the run again.</p>
         */
        void modelOwnerProcessDeath(File runDirectory) {
            File lease = new File(runDirectory, RootRunSpool.LEASE_FILE);
            if (lease.exists() && !lease.delete()) {
                throw new AssertionError("Could not model the death of the run's owner process");
            }
        }

        void close() {
            RootRunSpool.deleteDirectory(directory);
        }
    }
}
