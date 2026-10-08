package com.nutomic.syncthingandroid.runtime;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.nutomic.syncthingandroid.service.Constants;
import com.nutomic.syncthingandroid.util.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
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

    private final Context context;
    private final File binary;
    private final AppUidProcessLauncher processLauncher;
    private final ExecutionOwnershipManager ownershipManager;
    private final ConfigStorage configStorage;
    private final ManagedStateTransfer managedStateTransfer;
    private final HttpsCertificateStorage httpsCertificateStorage;

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
    }

    /**
     * Package-private execution seam used by JVM tests without an Android process.
     */
    AppUidBackend(
            File binary,
            AppUidProcessLauncher processLauncher,
            ExecutionOwnershipManager ownershipManager,
            ConfigStorage configStorage,
            ManagedStateLocations managedStateLocations
    ) {
        this.context = null;
        this.binary = Objects.requireNonNull(binary);
        this.processLauncher = Objects.requireNonNull(processLauncher);
        this.ownershipManager = Objects.requireNonNull(ownershipManager);
        this.configStorage = Objects.requireNonNull(configStorage);
        Objects.requireNonNull(managedStateLocations);
        this.managedStateTransfer = new AppUidManagedStateTransfer(managedStateLocations);
        this.httpsCertificateStorage = new AppUidHttpsCertificateStorage(managedStateLocations);
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
    public FolderWriteability validateCandidateFolder(String path) {
        return Util.nativeBinaryCanWriteToPath(context, path)
                ? FolderWriteability.WRITABLE
                : FolderWriteability.READ_ONLY;
    }

    @Override
    public ConflictDiscoveryResult discoverConflicts(ConfiguredFolderReference folder) {
        String[] paths = Util.getSyncConflictFiles(folder.path());
        return ConflictDiscoveryResult.of(Arrays.asList(paths));
    }

    @Override
    public FolderIgnoreResult loadFolderIgnoreList(ConfiguredFolderReference folder) {
        File file = new File(folder.path(), Constants.FILENAME_STIGNORE);
        FileInputStream input = null;
        try {
            if (!file.exists()) {
                Log.w(TAG, "loadFolderIgnoreList: File missing " + file);
                return FolderIgnoreResult.of(null);
            }
            input = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            input.read(data);
            return FolderIgnoreResult.of(new String(data, StandardCharsets.UTF_8).split("\n"));
        } catch (IOException e) {
            Log.e(TAG, "loadFolderIgnoreList: Failed to read '" + file + "' #1", e);
            return FolderIgnoreResult.of(null);
        } finally {
            try {
                if (input != null) {
                    input.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "loadFolderIgnoreList: Failed to read '" + file + "' #2", e);
            }
        }
    }

    @Override
    public void saveFolderIgnoreList(ConfiguredFolderReference folder, String[] ignore) {
        File file = new File(folder.path(), Constants.FILENAME_STIGNORE);
        FileOutputStream output = null;
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
            output = new FileOutputStream(file);
            output.write(TextUtils.join("\n", ignore).getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException e) {
            Log.w(TAG, "saveFolderIgnoreList: Failed to write '" + file + "' #1", e);
        } finally {
            try {
                if (output != null) {
                    output.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "saveFolderIgnoreList: Failed to write '" + file + "' #2", e);
            }
        }
    }

    @Override
    public void runFolderScripts(
            ConfiguredFolderReference folder,
            FolderEvent event
    ) {
        String scriptDirectory = folder.path() + "/" + Constants.FILENAME_STFOLDER;
        Util.runScriptSet(scriptDirectory, new String[]{event.argument()});
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
