package com.nutomic.syncthingandroid.runtime;

import android.content.Context;
import android.util.Log;

import com.nutomic.syncthingandroid.service.Constants;
import com.nutomic.syncthingandroid.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Normal-mode backend using the application UID and the bundled executable.
 *
 * <p>This class contains transport and app-UID filesystem mechanics. Runtime policy remains in
 * {@link DefaultSyncthingRuntime} and the service-facing exit policy remains in the runnable.</p>
 */
public final class AppUidBackend implements PrivilegeBackend {
    private static final String TAG = "AppUidBackend";

    /**
     * Deadline for the one privileged maintenance operation this backend may acquire root for.
     *
     * <p>It matches the superuser backend's activation deadline, so an unanswered root prompt can
     * never outlive the bound the rest of the runtime already uses.</p>
     */
    private static final long TUNING_ACTIVATION_TIMEOUT_MILLIS =
            RootBackend.ACTIVATION_TIMEOUT_MILLIS;
    private final File binary;
    /** Application context used for diagnostics; {@code null} on the JVM construction seam. */
    private final Context context;
    private final AppUidProcessLauncher processLauncher;
    private final ExecutionOwnershipManager ownershipManager;
    private final ConfigStorage configStorage;
    private final ManagedStateTransfer managedStateTransfer;
    private final HttpsCertificateStorage httpsCertificateStorage;
    /** Folder policy for the application UID; the platform mechanics sit behind its seam. */
    private final AppUidFolderOperations folderOperations;
    /** Absolute path a configured {@code ~} expands to. */
    private final String tildeBase;
    /**
     * Root helper used only by explicit privileged maintenance, or {@code null} when none exists.
     *
     * <p>Normal Mode needs no root for folder work. This session boundary exists solely so the
     * optional system tuning can run while Normal Mode stays selected, and no other operation of
     * this backend ever touches it.</p>
     */
    private final RootHelperSessions privilegedTuningSessions;

    public AppUidBackend(Context context) {
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext;
        this.binary = Constants.getSyncthingBinary(applicationContext);
        this.processLauncher = AppUidBackend::startWithProcessBuilder;
        File record = new File(
                new File(applicationContext.getNoBackupFilesDir(), "normal-syncthing"),
                "execution-record-v1.bin"
        );
        this.ownershipManager = new ExecutionOwnershipManager(
                binary.getAbsolutePath(),
                new FileExecutionRecordStore(record),
                new ProcExecutionInspector(),
                new AndroidProcessSignalTransport()
        );
        this.configStorage = new AppUidConfigStorage(applicationContext);
        ManagedStateLocations locations = ManagedStateLocations.forApplication(applicationContext);
        this.managedStateTransfer = new AppUidManagedStateTransfer(locations);
        this.httpsCertificateStorage = new AppUidHttpsCertificateStorage(locations);
        this.folderOperations = new AppUidFolderOperations(
                new AndroidFolderNativeAccess(),
                System::nanoTime
        );
        this.tildeBase = FileUtils.getSyncthingTildeAbsolutePath();
        this.privilegedTuningSessions = new RootHelperSessions(
                new LibsuRootShellFactory(locations),
                TUNING_ACTIVATION_TIMEOUT_MILLIS
        );
    }

    /**
     * Package-private execution seam used by JVM tests without an Android process.
     */
    AppUidBackend(
            File binary,
            AppUidProcessLauncher processLauncher,
            ExecutionOwnershipManager ownershipManager,
            ConfigStorage configStorage,
            ManagedStateLocations managedStateLocations,
            FolderNativeAccess folderNativeAccess,
            String tildeBase,
            RootShellFactory privilegedTuningShellFactory
    ) {
        this.context = null;
        this.binary = Objects.requireNonNull(binary);
        this.processLauncher = Objects.requireNonNull(processLauncher);
        this.ownershipManager = Objects.requireNonNull(ownershipManager);
        this.configStorage = Objects.requireNonNull(configStorage);
        Objects.requireNonNull(managedStateLocations);
        this.managedStateTransfer = new AppUidManagedStateTransfer(managedStateLocations);
        this.httpsCertificateStorage = new AppUidHttpsCertificateStorage(managedStateLocations);
        this.folderOperations = new AppUidFolderOperations(
                Objects.requireNonNull(folderNativeAccess),
                System::nanoTime
        );
        this.tildeBase = Objects.requireNonNull(tildeBase);
        this.privilegedTuningSessions = privilegedTuningShellFactory == null
                ? null
                : new RootHelperSessions(
                        privilegedTuningShellFactory,
                        TUNING_ACTIVATION_TIMEOUT_MILLIS
                );
    }

    @Override
    public void validateLaunchPrerequisites() throws ExecutableNotFoundException {
        if (!binary.exists()) throw new ExecutableNotFoundException(binary.getPath());
    }

    @Override
    public LaunchPreparation prepareLaunch(
            SyncthingCommand command,
            SyncthingEnvironment environment
    ) throws IOException, ExecutableNotFoundException {
        validateLaunchPrerequisites();

        ExecutionOwnershipManager.RecoveryAssessment recovery = ownershipManager.recover();
        if (!recovery.mayLaunch()) {
            throw new ExecutionRecoveryException(recovery);
        }

        final String runToken = java.util.UUID.randomUUID().toString();
        final Map<String, String> processEnvironment = new HashMap<>(environment.values());
        processEnvironment.put(
                ProcExecutionInspector.RUN_TOKEN_ENVIRONMENT,
                runToken
        );
        final String[] argv = command.argv(binary.getPath());
        return new LaunchPreparation() {
            @Override
            public ExecutionOwnershipManager.RecoveryAssessment classifyLaunch() {
                // This backend needs no prepared capability: its recovery classification reads the
                // local process table and never acquires root.
                return ownershipManager.recover();
            }

            @Override
            public Execution start(ProcessStartReservation reservation) throws IOException {
                Process process = processLauncher.start(argv, processEnvironment);
                ExecutionIdentity identity = null;
                try {
                    identity = ownershipManager.recordLaunchedProcess(runToken);
                } catch (IOException | RuntimeException e) {
                    if (context != null) {
                        Log.e(TAG, "Could not durably record the launched Syncthing process", e);
                    }
                }
                // The process exists and its durable ownership evidence has been written, or its
                // failure was observed, so the creation boundary ends here.
                reservation.release();
                boolean exitedBeforeIdentityCapture = identity == null && hasExited(process);
                return new ProcessExecution(
                        process, identity, exitedBeforeIdentityCapture, ownershipManager
                );
            }

            @Override
            public void discard() {
                // The application-UID preparation owns nothing outside start(...).
            }

            @Override
            public void armLaunch() {
                // The application-UID launch records no durable state before start(...), so there
                // is nothing this backend has to arm before the lifecycle layer commits.
            }
        };
    }

    @Override
    public Execution start(SyncthingCommand command, SyncthingEnvironment environment)
            throws IOException, ExecutableNotFoundException {
        LaunchPreparation preparation = prepareLaunch(command, environment);
        // This entry point settles no lifecycle launch check of its own, so the preparation is
        // armed here, directly in front of process creation. Arming records nothing for this
        // backend, and preparation steps that can fail still ran before this point.
        preparation.armLaunch();
        return preparation.start(ProcessStartReservation.NONE);
    }

    @Override
    public ExecutionOwnershipManager.RecoveryAssessment recoverExecutions() {
        return ownershipManager.recover();
    }

    @Override
    public ExecutionOwnershipManager.SignalAttempt signalIfOwned(
            ExecutionIdentity identity,
            ExecutionOwnershipManager.Signal signal
    ) {
        return ownershipManager.signalIfOwned(identity, signal);
    }

    @Override
    public ExecutionOwnershipManager.Observation observe(ExecutionIdentity identity) {
        return ownershipManager.observe(identity);
    }

    @Override
    public boolean clearAfterExit(ExecutionIdentity identity) throws IOException {
        return ownershipManager.clearAfterExit(identity);
    }

    @Override
    public ConfigStorage configStorage() {
        return configStorage;
    }

    @Override
    public ManagedStateTransfer managedStateTransfer() {
        return managedStateTransfer;
    }

    @Override
    public HttpsCertificateStorage httpsCertificateStorage() {
        return httpsCertificateStorage;
    }

    @Override
    public FolderWriteability validateCandidateFolder(String path) throws FolderOperationException {
        return folderOperations.probeWriteability(path);
    }

    @Override
    public ConflictDiscoveryResult discoverConflicts(ConfiguredFolderReference folder)
            throws FolderOperationException {
        return folderOperations.discoverConflicts(resolveConfiguredFolderPath(folder));
    }

    @Override
    public FolderIgnoreResult loadFolderIgnoreList(ConfiguredFolderReference folder)
            throws FolderOperationException {
        return folderOperations.readIgnoreList(resolveConfiguredFolderPath(folder));
    }

    @Override
    public void saveFolderIgnoreList(ConfiguredFolderReference folder, String[] ignore)
            throws FolderOperationException {
        folderOperations.writeIgnoreList(resolveConfiguredFolderPath(folder), ignore);
    }

    @Override
    public List<FolderScriptOutcome> runFolderScripts(
            ConfiguredFolderReference folder,
            FolderEvent event
    ) throws FolderOperationException {
        return folderOperations.runScriptSet(resolveConfiguredFolderPath(folder), event.argument());
    }

    @Override
    public TuningOutcome applyIoPriority(ExecutionIdentity identity) {
        // I/O priority is tuned for a privileged process. Normal Mode never owns one and never
        // falls back to a privileged command, so the request is simply not applicable here.
        return TuningOutcome.notApplicable(
                "Normal Mode runs Syncthing as the application UID, so no privileged I/O priority "
                        + "is applied"
        );
    }

    @Override
    public TuningOutcome applyInotifyWatchLimit() {
        if (privilegedTuningSessions == null) {
            return TuningOutcome.notApplicable(
                    "No privileged root helper is available in this process"
            );
        }
        // Explicit privileged maintenance only. This call is the user's own request, so it may
        // acquire root for that single bounded operation; no other Normal Mode path reaches this
        // method, which is what keeps the tuning from becoming an implicit root prompt. The
        // selected Execution Mode is neither consulted nor changed.
        try {
            return privilegedTuningSessions.run(
                    shell -> shell.applyInotifyWatchLimit(InotifyWatchLimit.TARGET)
            );
        } catch (IOException | RuntimeException failure) {
            // Tuning is optional. A device without a usable root helper keeps the system default,
            // and the caller decides what to report or whether to store the preference.
            return TuningOutcome.failed(
                    "The system inotify watch limit could not be applied: " + failure
            );
        }
    }

    /**
     * Resolves the authoritative path of one configured folder as the application UID.
     *
     * <p>The path always comes from the authoritative configuration document. A caller-provided
     * or memory-only path never decides which files a folder operation touches.</p>
     *
     * @throws FolderOperationException when the configuration cannot be read, cannot be parsed,
     *                                  or carries no folder with the requested identifier
     */
    private String resolveConfiguredFolderPath(ConfiguredFolderReference folder)
            throws FolderOperationException {
        byte[] configuration;
        if (!configStorage.canRead()) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                    "The authoritative configuration is not readable"
            );
        }
        try {
            configuration = configStorage.load();
        } catch (IOException failure) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                    "The authoritative configuration could not be read",
                    failure
            );
        }
        return ConfiguredFolderResolver.resolveFolderPath(configuration, folder.id(), tildeBase);
    }

    private static final class ProcessExecution implements Execution {
        private final Process process;
        private final ExecutionIdentity identity;
        private final boolean exitedBeforeIdentityCapture;
        private final ExecutionOwnershipManager ownershipManager;

        private ProcessExecution(
                Process process,
                ExecutionIdentity identity,
                boolean exitedBeforeIdentityCapture,
                ExecutionOwnershipManager ownershipManager
        ) {
            this.process = process;
            this.identity = identity;
            this.exitedBeforeIdentityCapture = exitedBeforeIdentityCapture;
            this.ownershipManager = ownershipManager;
        }

        @Override
        public InputStream stdout() {
            return process.getInputStream();
        }

        @Override
        public InputStream stderr() {
            return process.getErrorStream();
        }

        @Override
        public int await() throws InterruptedException {
            int exitCode = process.waitFor();
            if (identity != null) {
                try {
                    ownershipManager.clearAfterExit(identity);
                } catch (IOException e) {
                    Log.e(TAG, "Could not clear the exited Syncthing identity record", e);
                }
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            signalIfOwned(ExecutionOwnershipManager.Signal.SIGKILL);
        }

        @Override
        public ExecutionIdentity identity() {
            return identity;
        }

        @Override
        public boolean exitedBeforeIdentityCapture() {
            return exitedBeforeIdentityCapture;
        }

        @Override
        public ExecutionOwnershipManager.Observation observe() {
            if (identity == null) return ExecutionOwnershipManager.Observation.NOT_OWNED;
            return ownershipManager.observe(identity);
        }

        @Override
        public ExecutionOwnershipManager.SignalAttempt signalIfOwned(
                ExecutionOwnershipManager.Signal signal
        ) {
            if (identity == null) return ExecutionOwnershipManager.SignalAttempt.NOT_OWNED;
            return ownershipManager.signalIfOwned(identity, signal);
        }
    }

    /** Uses Process.exitValue, available on the full minSdk range, as concrete exit evidence. */
    private static boolean hasExited(Process process) {
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException stillRunning) {
            return false;
        } catch (RuntimeException uncertain) {
            return false;
        }
    }

    private static Process startWithProcessBuilder(
            String[] argv,
            Map<String, String> environment
    ) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(argv);
        processBuilder.environment().putAll(environment);
        return processBuilder.start();
    }
}

/** Package-private seam for transporting an app-UID Syncthing process. */
@FunctionalInterface
interface AppUidProcessLauncher {
    Process start(String[] argv, Map<String, String> environment) throws IOException;
}
