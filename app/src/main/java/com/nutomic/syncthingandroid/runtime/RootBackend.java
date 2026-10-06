package com.nutomic.syncthingandroid.runtime;

import android.content.Context;
import android.util.Log;

import com.nutomic.syncthingandroid.service.Constants;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Superuser (root) execution backend for the bundled Syncthing binary.
 *
 * <p>The backend owns the whole root transport boundary: it acquires explicit root shells inside a
 * bounded activation window, verifies that they run as UID 0, transports exactly one audited launch
 * script per bundled invocation, records the durable identity of the launched process, and stops a
 * process only after the ownership manager has re-verified that identity. It never falls back to
 * application-UID execution, and it never signals a process it cannot prove it started.</p>
 *
 * <p>Construction is side-effect free: no root shell is acquired until an explicit root-capable
 * operation runs. Privileged state, folder, and script operations that belong to a later
 * implementation slice fail closed with {@link RootFailure#PRIVILEGED_STATE_NOT_IMPLEMENTED}
 * instead of silently substituting application-UID behavior.</p>
 *
 * <h2>Launch preparation boundary</h2>
 *
 * <p>Root activation is slow, can prompt, and can fail, and the audited launch script has to be
 * encoded and validated before anything can be transported, so none of that may run after the
 * lifecycle layer has committed to creating a process. {@link #prepareLaunch} therefore performs
 * every fallible step - the executable prerequisite, recovery classification, planning of the
 * per-run spool paths, launch script encoding and validation, the bounded acquisition of the
 * dedicated launch shell with its immutable transport identity, and the operation-scoped helper
 * session - and returns a {@link PrivilegeBackend.LaunchPreparation} that owns the resulting
 * immutable launch material.</p>
 *
 * <p>Under the caller's process-start reservation the caller then reclassifies recovery through
 * that already-prepared helper session, arms the launch with
 * {@link PrivilegeBackend.LaunchPreparation#armLaunch()}, settles its lifecycle launch check, and
 * starts the launch. Arming is the last fallible step and runs before the lifecycle layer commits,
 * so a failure to record durable state can never strand a launch the lifecycle layer already
 * committed to. Arming also creates the complete app-owned run spool, so the run directory exists
 * on disk only once its durable pre-delivery state is being written: a run that is still waiting
 * for root is never visible to a concurrent recovery as an attributed, empty leftover. The start
 * call performs the single terminal {@code exec} that creates the bundled
 * process, and it releases the caller's process-start reservation as soon as the process and its
 * durable pre-exec evidence exist, before a fresh root activation verifies the launched identity.
 * When the lifecycle check refuses the launch, or when arming fails, the caller discards the
 * preparation: the prepared shell is closed, this run's pending state and spool are removed, and no
 * process was created.</p>
 * <h2>Privileged operation sessions</h2>
 *
 * <p>Every compound privileged operation - recovery, signal delivery, observation, record cleanup,
 * and the post-launch identity verification - runs inside one bounded helper session that is
 * acquired before the operation and closed when it ends. No helper shell is kept alive between
 * operations, and the dedicated launch shell is never reused for helper work.</p>
 *
 * <h2>Exit status</h2>
 *
 * <p>The audited launch script replaces the root shell with the bundled binary, so the process the
 * transport observes <em>is</em> the Syncthing process. The exit status the transport observes is
 * therefore Syncthing's own status, and the backend reports it unchanged so the existing service
 * policy - including the requested-restart status - keeps working.</p>
 */
public final class RootBackend implements PrivilegeBackend {
    /** Bounded root activation deadline required by the canonical superuser design. */
    static final long ACTIVATION_TIMEOUT_MILLIS = 60_000;
    /**
     * Bounded wait for a transported launch script to prove that it created its process.
     *
     * <p>The wait covers the interval between the transport accepting the launch bytes and the
     * shell having written its durable evidence, so it is part of the creation boundary and stays
     * far below the activation deadline: a shell that executes a delivered script does so in
     * milliseconds, while a missing confirmation means the launch cannot be proven at all.</p>
     */
    static final long CREATION_CONFIRMATION_TIMEOUT_MILLIS = 5_000;

    /** Poll interval of the creation-confirmation reads. */
    private static final long CREATION_CONFIRMATION_POLL_MILLIS = 25;

    private static final String TAG = "RootBackend";
    private static final String STATE_DIRECTORY = "superuser-runtime";
    private static final String RECORD_FILE = "root-execution-v1.txt";
    private static final String SPOOL_DIRECTORY = "runs";

    private final Context context;
    private final File binary;
    private final File logFile;
    private final File logTemporaryDirectory;
    private final RootActivation activation;
    private final long activationTimeoutMillis;
    private final long creationConfirmationTimeoutMillis;
    private final ExecutorService activationWorker;
    private final RootEvidenceStore records;
    private final RootRunSpoolReconciler reconciler;
    /** Spool of the run this backend most recently launched, if it is still active. */
    private volatile RootRunSpool activeSpool;
    /** Spool of a run this backend prepared but has not created a process for yet, if any. */
    private volatile RootRunSpool preparedSpool;

    /**
     * Creates the production backend for the application's no-backup private storage.
     *
     * <p>The root capability is not touched here; it is acquired only when an explicit root-capable
     * operation runs.</p>
     */
    public RootBackend(Context context) {
        this(
                context.getApplicationContext(),
                new File(context.getApplicationContext().getNoBackupFilesDir(), STATE_DIRECTORY),
                Constants.getSyncthingBinary(context.getApplicationContext()),
                Constants.getSyncthingLogFile(context.getApplicationContext()),
                context.getApplicationContext().getFilesDir(),
                new LibsuRootShellFactory(),
                ACTIVATION_TIMEOUT_MILLIS,
                CREATION_CONFIRMATION_TIMEOUT_MILLIS
        );
    }

    /**
     * Package-private construction seam used by JVM tests with a fake root shell factory.
     *
     * @param context nullable; only used for diagnostics
     * @param stateDirectory directory that holds the execution records and the run spools
     * @param binary bundled Syncthing executable
     * @param logFile shared Syncthing log that receives reconciled leftover output, or {@code null}
     * @param logTemporaryDirectory directory used when the shared log is trimmed
     * @param shellFactory root shell transport
     * @param activationTimeoutMillis caller-visible activation deadline
     * @param creationConfirmationTimeoutMillis bounded wait for a delivered launch to prove that it
     *     created its process
     */
    RootBackend(
            Context context,
            File stateDirectory,
            File binary,
            File logFile,
            File logTemporaryDirectory,
            RootShellFactory shellFactory,
            long activationTimeoutMillis,
            long creationConfirmationTimeoutMillis
    ) {
        this.context = context;
        this.binary = Objects.requireNonNull(binary);
        this.logFile = logFile;
        this.logTemporaryDirectory = logTemporaryDirectory;
        this.activationTimeoutMillis = activationTimeoutMillis;
        this.creationConfirmationTimeoutMillis = creationConfirmationTimeoutMillis;
        this.activation = new RootActivation(
                Objects.requireNonNull(shellFactory),
                activationTimeoutMillis
        );
        // Every activation owns its worker. A root prompt that is never answered leaves its
        // activation blocked until the transport's own bound expires, and that must not delay
        // unrelated root operations behind it; the abandoned request is cancelled instead, and
        // RootActivation closes the shell if it arrives afterwards.
        this.activationWorker = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "root-activation");
                thread.setDaemon(true);
                return thread;
            }
        });
        File stateRoot = Objects.requireNonNull(stateDirectory);
        File spoolRoot = new File(stateRoot, SPOOL_DIRECTORY);
        this.records = new RootEvidenceStore(new File(stateRoot, RECORD_FILE), spoolRoot);
        this.reconciler = new RootRunSpoolReconciler(spoolRoot, logFile, logTemporaryDirectory);
    }

    @Override
    public void validateLaunchPrerequisites() throws ExecutableNotFoundException {
        if (!binary.exists()) {
            throw new ExecutableNotFoundException(binary.getPath());
        }
    }

    /**
     * Prepares one bundled root invocation without creating a process.
     *
     * <p>This is where every fallible step of a root launch happens: the executable prerequisite,
     * the durable run spool, the encoding and validation of the complete audited launch script, the
     * bounded acquisition of the dedicated launch shell, and the bounded acquisition of a separate
     * operation-scoped session. The returned preparation owns the encoded script, both shells, and
     * the spool until the caller either starts it or discards it.</p>
     *
     * <p>The dedicated launch shell is replaced by the bundled process, so it performs exactly one
     * audited raw terminal script and no helper operation. Its immutable kernel identity is captured
     * by the activation boundary while that boundary establishes the shell, before the caller
     * designates it, so the transport never reads process metadata after designation. The
     * operation-scoped session is what serves the final recovery classification and the confirmation
     * of creation, so neither of them has to acquire root again once the caller holds the
     * process-start reservation.</p>
     *
     * @throws IllegalArgumentException when the structured environment cannot be encoded into the
     *     audited launch script, so no process can be created for it
     */
    @Override
    public LaunchPreparation prepareLaunch(
            SyncthingCommand command,
            SyncthingEnvironment environment
    ) throws IOException, ExecutableNotFoundException {
        validateLaunchPrerequisites();
        ExecutionOwnershipManager.RecoveryAssessment recovery = recoverExecutions();
        if (!recovery.mayLaunch()) {
            throw new ExecutionRecoveryException(recovery);
        }
        if (activeSpool != null || preparedSpool != null) {
            throw new IllegalStateException(
                    "A bundled root invocation is already active"
            );
        }

        String runToken = UUID.randomUUID().toString();
        Map<String, String> processEnvironment = new HashMap<>(environment.values());
        processEnvironment.put(LibsuRootShell.RUN_TOKEN_ENVIRONMENT, runToken);
        RootRunSpool spool = RootRunSpool.plan(spoolRoot(), runToken, command.name());
        // The spool is only planned here: it owns this run's paths and protects the preparation
        // from a replacement launch, but nothing exists on disk yet. The complete app-owned spool
        // is materialized by armLaunch() under the caller's process-start reservation, so a
        // concurrent recovery can never observe an attributed run that carries no durable
        // pre-delivery state and reconcile it away while root is still being acquired.
        preparedSpool = spool;
        String launchScript;
        try {
            // The complete launch script is encoded and validated here, before the lifecycle layer
            // commits to process creation. An environment that cannot be encoded therefore fails
            // the launch without prompting for root, without transporting anything, and without
            // leaving a run spool behind.
            launchScript = RootShellEncoder.launchScript(
                    command.argv(binary.getPath()),
                    processEnvironment,
                    spool.evidenceFile().getAbsolutePath(),
                    spool.evidenceStagingFile().getAbsolutePath(),
                    spool.outputFile().getAbsolutePath()
            );
        } catch (RuntimeException e) {
            preparedSpool = null;
            spool.delete();
            throw e;
        }
        RootShellSession launchSession = null;
        ExecutionIdentity transportIdentity;
        try {
            launchSession = acquireBounded(activation.beginLaunchTransport());
            // The activation captured the transport identity while it established and UID-0-verified
            // the shell and before the shell became the dedicated launch transport, so the durable
            // pre-delivery state written later is exactly the identity a later recovery process must
            // inspect, and the launch transport itself runs no helper operation.
            transportIdentity = launchSession.transportIdentity(binary.getPath(), runToken);
        } catch (RuntimeException e) {
            closeQuietly(launchSession);
            preparedSpool = null;
            spool.delete();
            throw e;
        }
        RootShell launchShell = launchSession.shell();
        RootShell helperShell;
        try {
            // The launch shell is replaced by the bundled process, so it can never run a helper
            // operation again. Recovery classification and creation confirmation therefore need
            // their own root session - and that slow, fallible acquisition has to happen while
            // the caller still allows it, never under the process-start reservation.
            helperShell = acquireBounded(activation.begin()).shell();
        } catch (RuntimeException e) {
            closeQuietly(launchShell);
            preparedSpool = null;
            spool.delete();
            throw e;
        }
        return new PreparedRootLaunch(
                spool,
                launchShell,
                helperShell,
                launchScript,
                runToken,
                transportIdentity,
                writesServeOutput(command)
        );
    }

    /**
     * Prepares and immediately starts one bundled root invocation.
     *
     * <p>Mode-neutral callers go through {@link #prepareLaunch} so they can settle their lifecycle
     * launch check between preparation and process creation. This entry point exists for callers
     * that have no lifecycle check to settle, and it keeps the same guarantee: nothing that can
     * fail is left between the preparation and the terminal {@code exec}.</p>
     */
    @Override
    public Execution start(SyncthingCommand command, SyncthingEnvironment environment)
            throws IOException, ExecutableNotFoundException {
        LaunchPreparation preparation = prepareLaunch(command, environment);
        boolean started = false;
        try {
            // This entry point settles no lifecycle launch check of its own, so the preparation is
            // armed here, directly in front of the terminal transport. The durable pre-delivery
            // state is written before the shell can receive its first launch byte, and a launch
            // that is refused before that point releases the preparation.
            preparation.armLaunch();
            // This entry point takes no process-start reservation, so the preparation reports the
            // end of its creation boundary against a no-op reservation.
            Execution execution = preparation.start(ProcessStartReservation.NONE);
            started = true;
            return execution;
        } finally {
            if (!started) {
                preparation.discard();
            }
        }
    }

    /**
     * Classifies whether a root execution may already exist.
     *
     * <p>Durable evidence is read first, so a failure to acquire root while evidence may still
     * describe a live execution is reported as {@link RootFailure#ROOT_AUTHORIZATION_LOST} instead
     * of a plain activation failure. When no evidence exists and root cannot be acquired, the
     * absence of a bundled candidate cannot be proven either, so the typed activation failure is
     * surfaced rather than a launchable "no candidate" assessment.</p>
     */
    @Override
    public ExecutionOwnershipManager.RecoveryAssessment recoverExecutions() {
        ExecutionRecordStore.ReadResult stored = records.read();
        ExecutionOwnershipManager.RecoveryAssessment assessment;
        try {
            assessment = recoverInSession();
        } catch (RootTransportException e) {
            if (stored.status() != ExecutionRecordStore.ReadResult.Status.MISSING) {
                throw new RootTransportException(
                        RootFailure.ROOT_AUTHORIZATION_LOST,
                        "Root capability is unavailable while durable evidence may describe a live"
                                + " execution",
                        e
                );
            }
            // Root capability is unavailable and no evidence exists, so the absence of a bundled
            // candidate cannot be proven. A typed activation failure is surfaced instead of a
            // launchable assessment.
            throw e;
        }
        if (assessment.mayLaunch()) {
            // A spool of a run this backend is preparing, or of one it already launched, is not a
            // leftover run: reconciling it would destroy the output of an in-flight launch.
            reconciler.reconcile(activeSpool != null ? activeSpool : preparedSpool);
        }
        return assessment;
    }

    @Override
    public ExecutionOwnershipManager.SignalAttempt signalIfOwned(
            ExecutionIdentity identity,
            ExecutionOwnershipManager.Signal signal
    ) {
        if (identity == null) {
            return ExecutionOwnershipManager.SignalAttempt.NOT_OWNED;
        }
        try {
            return withHelperSession(manager -> manager.signalIfOwned(identity, signal));
        } catch (IOException | RuntimeException e) {
            // A signal follows only an exact re-verification inside one bounded session. When the
            // session cannot run, nothing is signaled.
            return ExecutionOwnershipManager.SignalAttempt.NOT_OWNED;
        }
    }

    @Override
    public ExecutionOwnershipManager.Observation observe(ExecutionIdentity identity) {
        if (identity == null) {
            return ExecutionOwnershipManager.Observation.NOT_OWNED;
        }
        try {
            return withHelperSession(manager -> manager.observe(identity));
        } catch (IOException | RuntimeException e) {
            return ExecutionOwnershipManager.Observation.UNKNOWN;
        }
    }

    @Override
    public boolean clearAfterExit(ExecutionIdentity identity) throws IOException {
        if (identity == null) {
            return false;
        }
        return withHelperSession(manager -> manager.clearAfterExit(identity));
    }

    @Override
    public ConfigStorage configStorage() {
        throw notImplemented("Privileged configuration storage");
    }

    @Override
    public FolderWriteability validateCandidateFolder(String path) {
        throw notImplemented("Validating a folder for root execution");
    }

    @Override
    public ConflictDiscoveryResult discoverConflicts(ConfiguredFolderReference folder) {
        throw notImplemented("Discovering conflicts as root");
    }

    @Override
    public FolderIgnoreResult loadFolderIgnoreList(ConfiguredFolderReference folder) {
        throw notImplemented("Reading a folder ignore list as root");
    }

    @Override
    public void saveFolderIgnoreList(ConfiguredFolderReference folder, String[] ignore) {
        throw notImplemented("Writing a folder ignore list as root");
    }

    @Override
    public void runFolderScripts(ConfiguredFolderReference folder, FolderEvent event) {
        throw notImplemented("Running folder scripts as root");
    }

    private File spoolRoot() {
        return reconciler.spoolRoot();
    }

    /**
     * Runs one privileged operation inside a single bounded root session.
     *
     * <p>The session shell is acquired through the bounded activation path, lent to the operation
     * through {@link SessionOwnedRootShell} views, and closed exactly once when the operation ends,
     * whether it succeeds or fails. Operations never keep the session shell alive afterwards.</p>
     */
    private <T> T withHelperSession(RootSessionOperation<T> operation) throws IOException {
        RootActivation.Request request = activation.begin();
        RootShellSession session = acquireBounded(request);
        try {
            return operation.run(ownershipManagerFor(session.shell()));
        } finally {
            closeQuietly(session);
        }
    }

    /**
     * Builds the ownership manager of one session.
     *
     * <p>Inspection and signal transports close every shell they acquire, which is correct for
     * operation-scoped helper shells. The session view ignores that close so the real session shell
     * stays open for the whole operation and is closed by the session boundary.</p>
     */
    private ExecutionOwnershipManager ownershipManagerFor(RootShell sessionShell) {
        RootShellProvider provider = () -> new SessionOwnedRootShell(sessionShell);
        return new ExecutionOwnershipManager(
                binary.getPath(),
                records,
                new RootExecutionInspector(provider),
                new RootProcessSignalTransport(provider)
        );
    }

    /** Runs the recovery classification of one bounded session. */
    private ExecutionOwnershipManager.RecoveryAssessment recoverInSession() {
        try {
            return withHelperSession(ExecutionOwnershipManager::recover);
        } catch (IOException e) {
            throw new RootTransportException(
                    RootFailure.ROOT_TRANSPORT_FAILED,
                    "The root recovery session failed",
                    e
            );
        }
    }

    /**
     * Runs one bounded activation on its dedicated worker thread.
     *
     * <p>libsu never runs on the caller's thread. The caller-visible deadline is the configured
     * activation bound itself; when it expires, the request is cancelled and the atomic handoff of
     * {@link RootActivation.Request} decides who owns a shell that still arrives, so a timeout that
     * races the handoff can neither lose the shell nor leave it unowned.</p>
     */
    private RootShellSession acquireBounded(RootActivation.Request request) {
        Future<?> future = activationWorker.submit(() -> {
            activation.acquire(request);
            return null;
        });
        try {
            future.get(activationTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            closeQuietly(request.cancelAndTake());
            throw new RootTransportException(
                    RootFailure.ROOT_ACTIVATION_TIMEOUT,
                    "Root activation exceeded the bounded activation deadline",
                    e
            );
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RootTransportException) {
                throw (RootTransportException) cause;
            }
            throw new RootTransportException(
                    RootFailure.ROOT_TRANSPORT_FAILED,
                    "Root activation failed",
                    cause
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            closeQuietly(request.cancelAndTake());
            throw new RootTransportException(
                    RootFailure.ROOT_TRANSPORT_FAILED,
                    "Root activation was interrupted",
                    e
            );
        }
        // Ownership is decided by the request, not by the future value: a timeout that races the
        // handoff may discard that value, and the shell must not be lost with it.
        RootShellSession session = request.take();
        if (session == null) {
            throw new RootTransportException(
                    RootFailure.ROOT_ACTIVATION_OBSOLETE,
                    "Root activation completed without a root shell owned by the requesting caller"
            );
        }
        return session;
    }

    /**
     * Terminates a launch whose script delivery failed after it had begun, and which the ownership
     * manager proved to be this launch's own process.
     *
     * <p>The candidate is signaled only after the ownership manager re-verifies it inside the
     * preparation's own helper session. A candidate that cannot be identified keeps its shell, its
     * evidence, and its run spool, so no unverified process is touched and no evidence is
     * destroyed.</p>
     */
    private void terminateUnrecordedLaunch(
            RootShell launchShell,
            RootShell helperShell,
            String runToken,
            RootRunSpool spool
    ) {
        ExecutionIdentity launched = null;
        try {
            launched = ownershipManagerFor(helperShell).findLaunchedProcess(runToken);
        } catch (IOException | RuntimeException e) {
            logWarning("Could not identify the unrecorded root launch", e);
        }
        boolean signaled = launched != null
                && ownershipManagerFor(helperShell).signalIfOwned(
                        launched, ExecutionOwnershipManager.Signal.SIGKILL)
                == ExecutionOwnershipManager.SignalAttempt.SIGNALED;
        if (signaled) {
            awaitExitQuietly(launchShell);
        }
        if (launchShell.hasExited()) {
            // The possible execution is proven gone, so its transport and its evidence may be
            // released and its run spool stops blocking a replacement launch.
            releaseProvenGoneLaunch(launchShell, spool);
        }
    }

    /**
     * Releases the transport, the durable evidence, and the run spool of a proven-gone launch.
     *
     * <p>Only a launch whose process is already proven gone may be released this way: closing a
     * transport whose process could still be the bundled binary would destroy an unverified
     * execution, and deleting its spool would destroy the only ownership evidence.</p>
     */
    private void releaseProvenGoneLaunch(RootShell launchShell, RootRunSpool spool) {
        closeQuietly(launchShell);
        spool.delete();
        if (preparedSpool == spool) {
            preparedSpool = null;
        }
        if (activeSpool == spool) {
            activeSpool = null;
        }
    }


    /** Waits one creation-confirmation poll interval; reports whether the wait completed. */
    private static boolean awaitConfirmationPoll() {
        try {
            Thread.sleep(CREATION_CONFIRMATION_POLL_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isLaunchProtocolExitCode(int exitCode) {
        return exitCode == RootShellEncoder.UID_GUARD_EXIT_CODE
                || exitCode == RootShellEncoder.EVIDENCE_WRITE_EXIT_CODE
                || exitCode == RootShellEncoder.EVIDENCE_READ_EXIT_CODE;
    }

    private static RootTransportException failureForProtocolExit(int exitCode) {
        if (exitCode == RootShellEncoder.UID_GUARD_EXIT_CODE) {
            return new RootTransportException(
                    RootFailure.UID_VERIFICATION_FAILED,
                    "The launch script refused to start because the shell does not run as UID 0"
            );
        }
        return new RootTransportException(
                RootFailure.ROOT_TRANSPORT_FAILED,
                "The launch script could not establish durable execution evidence (exit "
                        + exitCode + ")"
        );
    }

    private static RootTransportException notImplemented(String operation) {
        return new RootTransportException(
                RootFailure.PRIVILEGED_STATE_NOT_IMPLEMENTED,
                operation + " is not part of this implementation slice"
        );
    }

    /** Reports whether one bundled command writes long-running serve output. */
    private static boolean writesServeOutput(SyncthingCommand command) {
        return command != SyncthingCommand.DEVICE_ID
                && command != SyncthingCommand.GENERATE
                && command != SyncthingCommand.RESET_DATABASE;
    }

    private static int awaitExitQuietly(RootShell shell) {
        try {
            return shell.awaitExit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static void closeQuietly(RootShell shell) {
        if (shell == null) {
            return;
        }
        try {
            shell.close();
        } catch (RuntimeException ignored) {
            // The shell process is already gone.
        }
    }

    private static void closeQuietly(RootShellSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException ignored) {
            // The shell process is already gone.
        }
    }

    private void logWarning(String message, Throwable error) {
        if (context != null) {
            Log.w(TAG, message, error);
        }
    }

    /** One privileged operation that runs inside a single bounded root session. */
    @FunctionalInterface
    interface RootSessionOperation<T> {
        T run(ExecutionOwnershipManager ownershipManager) throws IOException;
    }

    /** A root launch that is fully prepared but has not created a process yet. */
    private final class PreparedRootLaunch implements LaunchPreparation {
        private final RootRunSpool spool;
    private final RootShell launchShell;
    private final ExecutionIdentity transportIdentity;
        /**
         * Operation-scoped root session of this preparation.
         *
         * <p>It serves the final recovery classification, the creation confirmation, and the
         * post-launch identity verification, so none of them acquires root on its own. It is never
         * the launch shell: that shell is replaced by the bundled process and can never run a
         * helper operation again.</p>
         */
        private final RootShell helperShell;
        private final String launchScript;
        private final String runToken;
        private final boolean serveOutput;
        private final AtomicBoolean helperSessionClosed = new AtomicBoolean();
        /** Whether the durable pre-delivery state of this launch has been written. */
        private boolean armed;
        private boolean consumed;

        private PreparedRootLaunch(
                RootRunSpool spool,
                RootShell launchShell,
                RootShell helperShell,
                String launchScript,
                String runToken,
                ExecutionIdentity transportIdentity,
                boolean serveOutput
        ) {
            this.spool = spool;
            this.launchShell = launchShell;
            this.helperShell = helperShell;
            this.launchScript = launchScript;
            this.runToken = runToken;
            this.transportIdentity = transportIdentity;
            this.serveOutput = serveOutput;
        }

        @Override
        public ExecutionOwnershipManager.RecoveryAssessment classifyLaunch() {
            // Called while the caller holds the process-start reservation, so it uses the helper
            // session the preparation already acquired: no root acquisition, no user prompt, and no
            // activation deadline can block process creation here.
            return ownershipManagerFor(helperShell).recover();
        }

        @Override
        public Execution start(ProcessStartReservation reservation) throws IOException {
            if (consumed) {
                throw new IllegalStateException("A root launch preparation can only start once");
            }
            if (!armed) {
                throw new IllegalStateException(
                        "A root launch preparation must be armed before it can start"
                );
            }
            consumed = true;
            try {
                return create(reservation);
            } finally {
                closeHelperSession();
            }
        }

        /**
         * Arms this launch: creates the complete app-owned run spool and writes its durable
         * pre-delivery state.
         *
         * <p>Called while the caller holds the process-start reservation and before the caller's
         * lifecycle launch check, so the state exists before the shell can receive its first launch
         * byte and a failure can never strand a launch the lifecycle layer already committed to.
         * The run directory and every file the launch writes into - the staging file, the output
         * file, the consumption offset, and the command name - are created here, so the directory
         * is never observable on disk without the durable state that makes concurrent recovery
         * fail closed, and the UID-0 launch process always writes into app-owned files.
         * When arming fails, nothing has been transported yet: the launch transport is closed, the
         * partially created run spool is removed, and no durable state is left behind that could
         * block a later launch.</p>
         */
        @Override
        public void armLaunch() {
            if (consumed) {
                throw new IllegalStateException("A root launch preparation can only be armed once");
            }
            if (armed) {
                throw new IllegalStateException("This root launch preparation is already armed");
            }
            try {
                spool.materialize(transportIdentity);
            } catch (IOException | RuntimeException e) {
                discard();
                throw new RootTransportException(
                        RootFailure.ROOT_TRANSPORT_FAILED,
                        "Could not record the pre-delivery root launch state",
                        e
                );
            }
            armed = true;
        }

        /**
         * Creates the bundled process of this launch.
         *
         * <p>Every deterministic or fallible step already ran during preparation, so this method
         * only transports the encoded script, proves that creation happened, and hands the run
         * spool to the returned execution. Root capability used for those proofs was acquired
         * before the caller took the process-start reservation.</p>
         */
        private Execution create(ProcessStartReservation reservation) throws IOException {
            try {
                launchShell.execTerminalScript(launchScript);
            } catch (IOException | RuntimeException e) {
                throw failUncertainDelivery(e);
            }
        // The transport proves only that the shell accepted the script bytes, so creation is
        // confirmed under the still-held reservation: durable evidence and live process must
        // both describe this launch before any concurrent classifier may observe the
        // process-start boundary as finished.
        ExecutionOwnershipManager.LaunchConfirmation confirmation = awaitCreation();
        if (!confirmation.recognized()) {
            throw failUnconfirmedCreation();
        }
        // The run spool transfers to the returned execution before the creation boundary ends,
        // so a concurrent recovery can never reconcile an in-flight run away.
        activeSpool = spool;
        preparedSpool = null;
        reservation.release();
        ExecutionIdentity identity;
        if (confirmation.state() == ExecutionOwnershipManager.LaunchConfirmation.State.OWNED) {
            identity = confirmation.identity();
        } else if (confirmation.state()
                == ExecutionOwnershipManager.LaunchConfirmation.State.PROCESS_GONE) {
            identity = null;
        } else {
            identity = awaitTerminalExec();
        }
        if (identity == null) {
            int exitCode = awaitExitQuietly(launchShell);
            if (isLaunchProtocolExitCode(exitCode)) {
                activeSpool = null;
                closeQuietly(launchShell);
                spool.delete();
                throw failureForProtocolExit(exitCode);
            }
            // The process exited before its identity could be captured. Its real exit status is
            // reported, and SyncthingExecution rejects a one-shot result without identity.
            return ownedExecution(null);
        }
        return ownedExecution(identity);
    }

        /**
         * Waits, bounded, for the delivered launch to prove that it created its process.
         *
         * <p>The wait covers the interval in which the shell executes the script it accepted: the
         * launch writes its durable evidence and replaces itself with the bundled binary. Both
         * observable results - exact ownership and a proven pre-exec handoff - are states the
         * recovery classifier refuses to treat as launchable.</p>
         */
        private ExecutionOwnershipManager.LaunchConfirmation awaitCreation() {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(creationConfirmationTimeoutMillis);
            ExecutionOwnershipManager.LaunchConfirmation confirmation = confirmQuietly();
            while (!confirmation.recognized() && System.nanoTime() < deadline) {
                if (!awaitConfirmationPoll()) {
                    return confirmation;
                }
                confirmation = confirmQuietly();
            }
            return confirmation;
        }

        /**
         * Waits, bounded, for a proven handoff to reach the bundled executable.
         *
         * <p>The creation boundary was confirmed when the durable evidence and the recorded kernel
         * process matched, so this wait only resolves the identity of a launch that is already
         * recognized as in flight. It runs after the process-start reservation was released and
         * through the preparation's own helper session, so it neither blocks process creation nor
         * acquires root again.</p>
         *
         * @return verified identity, or {@code null} when the launched process already exited
         * @throws RootTransportException when the process never becomes the verified bundled
         *     executable; the possible execution is then left untouched
         */
        private ExecutionIdentity awaitTerminalExec() {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(creationConfirmationTimeoutMillis);
            while (true) {
                ExecutionOwnershipManager.LaunchConfirmation confirmation = confirmQuietly();
                if (confirmation.state()
                        == ExecutionOwnershipManager.LaunchConfirmation.State.OWNED) {
                    return confirmation.identity();
                }
                if (confirmation.state()
                        == ExecutionOwnershipManager.LaunchConfirmation.State.PROCESS_GONE) {
                    return null;
                }
                if (System.nanoTime() >= deadline || !awaitConfirmationPoll()) {
                    throw new RootTransportException(
                            RootFailure.EXECUTION_VERIFICATION_FAILED,
                            "The handed-off root launch did not reach the bundled executable"
                    );
                }
            }
        }

        /** Reads the creation confirmation through this preparation's own helper session. */
        private ExecutionOwnershipManager.LaunchConfirmation confirmQuietly() {
            try {
                return ownershipManagerFor(helperShell).confirmLaunch(runToken);
            } catch (RuntimeException e) {
                logWarning("Could not read the root launch confirmation", e);
                return ExecutionOwnershipManager.unresolved();
            }
        }

        /**
         * Reports a launch whose creation the transported script did not prove.
         *
         * <p>Neither durable evidence nor a matching live process describes what the accepted
         * script bytes created, so the possible execution is left untouched - never signaled, never
         * closed - and its evidence, its run spool, and its transport are preserved for a later
         * recovery. Its run spool keeps protecting that evidence and blocks a replacement launch
         * until the next application start re-derives the state from durable evidence.</p>
         */
        private RootTransportException failUnconfirmedCreation() {
            return new RootTransportException(
                    RootFailure.EXECUTION_VERIFICATION_FAILED,
                    "The transported root launch did not prove that it created its process"
            );
        }

        /**
         * Classifies a launch whose script delivery failed after it had begun.
         *
         * <p>A failed write or flush does not prove that nothing was delivered: the shell may have
         * executed nothing, part of the script, all of it, or may already be the bundled process.
         * Only a launch the ownership manager re-verifies as this launch's own process is cleaned
         * up. Every other state keeps its shell, its evidence, and its run spool untouched.</p>
         */
        private RootTransportException failUncertainDelivery(Throwable cause) {
            ExecutionOwnershipManager.LaunchConfirmation confirmation = confirmQuietly();
            if (confirmation.state() == ExecutionOwnershipManager.LaunchConfirmation.State.OWNED) {
                terminateUnrecordedLaunch(launchShell, helperShell, runToken, spool);
            } else if (confirmation.state()
                    == ExecutionOwnershipManager.LaunchConfirmation.State.PROCESS_GONE) {
                // The script wrote its durable evidence and the kernel no longer runs the recorded
                // process, so nothing can be destroyed by releasing its transport and its spool.
                reconcileGoneLaunch();
            }
            return new RootTransportException(
                    RootFailure.ROOT_TRANSPORT_FAILED,
                    "Could not transport the audited root launch script",
                    cause
            );
        }

        /** Releases the transport, the evidence, and the run spool of a proven-gone launch. */
        private void reconcileGoneLaunch() {
            releaseProvenGoneLaunch(launchShell, spool);
        }
        /** Wraps one created process into the execution contract of this backend. */
        private RootProcessExecution ownedExecution(ExecutionIdentity identity) {
            try {
                return new RootProcessExecution(
                        launchShell, identity, identity == null, spool, serveOutput
                );
            } catch (RuntimeException e) {
                if (activeSpool == spool) {
                    activeSpool = null;
                }
                throw e;
            }
        }

        /** Closes the operation-scoped helper session of this preparation exactly once. */
        private void closeHelperSession() {
            if (helperSessionClosed.compareAndSet(false, true)) {
                closeQuietly(helperShell);
            }
        }

        @Override
        public void discard() {
            if (consumed) {
                // The launch either reached terminal exec, or already released what it could prove
                // gone while reporting failure. An unverified possible execution is kept, so a
                // started preparation never cleans up here.
                return;
            }
            consumed = true;
            closeQuietly(launchShell);
            closeHelperSession();
            preparedSpool = null;
            spool.delete();
        }
    }

    /** One admitted root Syncthing execution. */
    private final class RootProcessExecution implements Execution {
        private final RootShell shell;
        private final ExecutionIdentity identity;
        private final boolean exitedBeforeIdentityCapture;
        private final RootRunSpool spool;
        private final RootServeLogPump serveLogPump;
        private final InputStream stdout;
        private final AtomicBoolean launchShellClosed = new AtomicBoolean();
        /** Whether this execution's process exit has already been proven. */
        private final AtomicBoolean exitProven = new AtomicBoolean();
        /** Exit status that was captured once, when the process exit was proven. */
        private volatile int provenExitCode;
        /** Whether the proven-exit settlement of this execution has already been started. */
        private final AtomicBoolean finalizationSettled = new AtomicBoolean();
        /** Counts down once the proven-exit settlement of this execution completed. */
        private final CountDownLatch finalizationFinished = new CountDownLatch(1);
        /**
         * Whether this run's spool has to survive for a later reconciliation.
         *
         * <p>Set when the serve log pump reported an I/O failure, or when the pump was cancelled
         * because the caller was interrupted. The flag is sticky: no later await may delete a spool
         * whose remaining output may still not have reached the shared log.</p>
         */
        private final AtomicBoolean spoolRequiresRecovery = new AtomicBoolean();

        private RootProcessExecution(
                RootShell shell,
                ExecutionIdentity identity,
                boolean exitedBeforeIdentityCapture,
                RootRunSpool spool,
                boolean serveOutput
        ) {
            this.shell = shell;
            this.identity = identity;
            this.exitedBeforeIdentityCapture = exitedBeforeIdentityCapture;
            this.spool = spool;
            if (serveOutput) {
                // A long-running run owns its own output: one pump moves spool bytes into the
                // shared log while the process runs and drains the rest after it exits. No caller
                // receives the same bytes a second time.
                this.serveLogPump = new RootServeLogPump(
                        RootServeLogWriter.forRunDirectory(
                                spool.directory(), logFile, logTemporaryDirectory
                        ),
                        shell::hasExited,
                        RootServeLogPump.POLL_MILLIS,
                        error -> logWarning("Could not reconcile root serve output", error)
                );
                this.serveLogPump.start();
                this.stdout = new ByteArrayInputStream(new byte[0]);
            } else {
                this.serveLogPump = null;
                this.stdout = openOutputTail(spool, shell);
            }
        }

        private InputStream openOutputTail(RootRunSpool runSpool, RootShell runShell) {
            try {
                return runSpool.openOutputTail(runShell);
            } catch (IOException e) {
                runSpool.delete();
                closeQuietly(runShell);
                throw new IllegalStateException("Could not open the root run output spool", e);
            }
        }

        @Override
        public boolean outputBelongsToSharedLog() {
            // Rooted output is operation-scoped: a one-shot run's output belongs to the caller that
            // requested the operation, and a serve run routes its output to the shared log through
            // its own pump, so no caller appends these bytes to the shared log.
            return false;
        }

        @Override
        public InputStream stdout() {
            return stdout;
        }

        @Override
        public InputStream stderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int await() throws InterruptedException {
            if (!exitProven.get()) {
                // The terminal exec replaced the root shell, so this is Syncthing's own exit
                // status.
                provenExitCode = shell.awaitExit();
                // From here on the process is proven gone, so every remaining step has to complete
                // even when the caller interrupts the join below: an interrupted wait must not
                // strand the durable record, the spool ownership, or the transport of an execution
                // that exited.
                exitProven.set(true);
            }
            if (finalizationSettled.compareAndSet(false, true)) {
                return settleProvenExit();
            }
            // A repeated await - for example the cleanup retry in SyncthingRunnable after an
            // interrupted wait - waits until the settlement an earlier await already started has
            // finished and then returns the same proven exit status. It never repeats the durable
            // cleanup and never revisits the earlier decision to keep this run's spool for a later
            // reconciliation.
            finalizationFinished.await();
            return provenExitCode;
        }

        /**
         * Completes the proven-exit settlement of this execution.
         *
         * <p>Runs whether or not the caller was interrupted while the serve log pump drained, so a
         * proven-gone execution never keeps its durable record, its spool ownership, or its
         * dedicated launch shell. A spool whose output did not reach the shared log is retained for
         * a later reconciliation instead of being discarded together with its output.</p>
         *
         * @return the proven exit status
         * @throws InterruptedException when the caller was interrupted while the serve log pump
         *     drained; the settlement itself has completed by then
         */
        private int settleProvenExit() throws InterruptedException {
            boolean interruptedWhileJoining = false;
            try {
                if (serveLogPump != null) {
                    try {
                        IOException failure = serveLogPump.awaitCompletion();
                        if (failure != null) {
                            // Output that never reached the shared log keeps its spool, so a later
                            // recovery appends the remainder instead of losing it.
                            spoolRequiresRecovery.set(true);
                            logWarning("Could not reconcile root serve output", failure);
                        }
                    } catch (InterruptedException interrupted) {
                        // The pump has not drained this run yet, so its spool is retained for a
                        // later recovery and the interruption is reported after finalization. The
                        // pump is stopped and joined before this execution releases the spool to
                        // recovery, so a later reconciliation can never append to the shared log
                        // while this pump is still writing the same run.
                        interruptedWhileJoining = true;
                        spoolRequiresRecovery.set(true);
                        try {
                            serveLogPump.cancelAndAwaitStopped();
                        } catch (InterruptedException stopped) {
                            // The pump has terminated; the calling thread's interruption is
                            // restored and reported after finalization below.
                        }
                    }
                }
            } finally {
                try {
                    finishProvenExit();
                } finally {
                    finalizationFinished.countDown();
                }
            }
            if (interruptedWhileJoining) {
                throw new InterruptedException(
                        "Interrupted while joining the root serve log pump of an exited execution"
                );
            }
            return provenExitCode;
        }

        /**
         * Releases everything this execution owned once its process is proven exited.
         *
         * <p>Settles exactly once per execution, so a repeated await cannot repeat the durable
         * cleanup. A spool whose output did not reach the shared log - because the pump failed, or
         * because the pump was cancelled for an interrupted caller - is retained for a later
         * reconciliation instead of being discarded together with its output. That retention is
         * sticky: once this execution has kept its spool for recovery, no later await may delete
         * it.</p>
         */
        private void finishProvenExit() {
            if (identity != null) {
                try {
                    clearAfterExit(identity);
                } catch (IOException | RuntimeException e) {
                    logWarning("Could not clear the exited root execution record", e);
                }
            }
            if (!spoolRequiresRecovery.get()) {
                spool.delete();
            }
            if (activeSpool == spool) {
                activeSpool = null;
            }
            if (preparedSpool == spool) {
                preparedSpool = null;
            }
            // The process is proven exited and every liveness-dependent drain that needed the
            // transport has finished, so the dedicated launch shell is closed here. Closing it
            // after exit can no longer kill a live Syncthing process, and it releases the transport
            // resources this invocation owns. An unverified live process never reaches this point,
            // so it keeps its shell untouched.
            closeLaunchShellAfterExit();
        }

        @Override
        public boolean exitProven() {
            return exitProven.get();
        }

        /** Closes the dedicated launch shell exactly once, after its process is proven exited. */
        private void closeLaunchShellAfterExit() {
            if (launchShellClosed.compareAndSet(false, true)) {
                closeQuietly(shell);
            }
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
            if (identity == null) {
                return ExecutionOwnershipManager.Observation.NOT_OWNED;
            }
            return RootBackend.this.observe(identity);
        }

        @Override
        public ExecutionOwnershipManager.SignalAttempt signalIfOwned(
                ExecutionOwnershipManager.Signal signal
        ) {
            if (identity == null) {
                return ExecutionOwnershipManager.SignalAttempt.NOT_OWNED;
            }
            return RootBackend.this.signalIfOwned(identity, signal);
        }
    }
}
