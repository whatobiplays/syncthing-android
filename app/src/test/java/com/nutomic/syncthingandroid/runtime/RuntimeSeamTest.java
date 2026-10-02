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
    public void lifecycleLaunchCheckRunsAfterRecoveryAndBeforeReplacementStart()
            throws Exception {
        RecordingBackend backend = new RecordingBackend();
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);

        SyncthingExecution execution = runtime.startServiceLifecycle(
                SyncthingCommand.SERVE,
                normalModeEnvironment(),
                null,
                () -> backend.events.add("port-check")
        );
        execution.await();

        assertEquals(Arrays.asList("validate", "recover", "port-check", "start"),
                backend.events);
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

    private static final class RecordingBackend implements PrivilegeBackend {
        private final ConfigStorage storage = new InMemoryConfigStorage();
        private final List<String> events = new ArrayList<>();
        private Execution execution = new ImmediateExecution();
        private ExecutableNotFoundException launchPrerequisiteFailure;
        private int recoveryChecks;
        private SyncthingCommand command;
        private SyncthingEnvironment environment;
        private ConfiguredFolderReference folder;
        private FolderEvent event;
        private String[] ignore;

        @Override
        public void validateLaunchPrerequisites() throws ExecutableNotFoundException {
            if (launchPrerequisiteFailure != null) throw launchPrerequisiteFailure;
            events.add("validate");
        }

        @Override
        public Execution start(SyncthingCommand command, SyncthingEnvironment environment)
                throws IOException, ExecutableNotFoundException {
            events.add("start");
            this.command = command;
            this.environment = environment;
            return execution;
        }

        @Override
        public ExecutionOwnershipManager.RecoveryAssessment recoverExecutions() {
            events.add("recover");
            recoveryChecks++;
            return ExecutionOwnershipManager.RecoveryAssessment.noCandidate();
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

    private static final class ImmediateExecution implements PrivilegeBackend.Execution {
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
