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
 * transport observes <em>is</em> the Syncthing process. The backend reports that status unchanged
 * so the existing service policy - including the requested-restart status - keeps working, but only
 * while the transport proved during acquisition that its awaited status belongs to the launched
 * process. When the transport client detached, the exit of the exact recorded process is still
 * verified against durable evidence, and the run then reports
 * {@link ExecutionExitStatusUnavailableException} instead of an unauthenticated status.</p>
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

    /**
     * Poll interval of the bounded post-signal exit wait, which observes only launch transport
     * liveness and never runs a command on the shell it watches.
     */
    private static final long CLEANUP_EXIT_POLL_MILLIS = 25;

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
    private final long cleanupExitWaitMillis;
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
                CREATION_CONFIRMATION_TIMEOUT_MILLIS,
                OwnedExecutionShutdown.SIGKILL_WAIT_MS
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
     * @param cleanupExitWaitMillis bounded wait for a failed launch process to exit after its
     *     exact-ownership signal, in milliseconds
     */
    RootBackend(
            Context context,
            File stateDirectory,
            File binary,
            File logFile,
            File logTemporaryDirectory,
            RootShellFactory shellFactory,
            long activationTimeoutMillis,
            long creationConfirmationTimeoutMillis,
            long cleanupExitWaitMillis
    ) {
        this.context = context;
        this.binary = Objects.requireNonNull(binary);
        this.logFile = logFile;
        this.logTemporaryDirectory = logTemporaryDirectory;
        this.activationTimeoutMillis = activationTimeoutMillis;
        this.creationConfirmationTimeoutMillis = creationConfirmationTimeoutMillis;
        this.cleanupExitWaitMillis = cleanupExitWaitMillis;
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
                command.writesServeLog()
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
     * preparation's own helper session. The wait for its exit is bounded by an
     * exact-identity observation loop, so a local {@code su} client that disappears independently
     * can never authorize cleanup of a daemon-side root process that is still alive. A candidate
     * that cannot be identified, or whose exit is not proven inside
     * that window, keeps its shell, its evidence, and its run spool, so no unverified process is
     * touched and no evidence is destroyed.</p>
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
        ExecutionOwnershipManager manager = ownershipManagerFor(helperShell);
        boolean signaled = launched != null
                && manager.signalIfOwned(launched, ExecutionOwnershipManager.Signal.SIGKILL)
                == ExecutionOwnershipManager.SignalAttempt.SIGNALED;
        if (signaled && awaitOwnedExitWithinCleanupWindow(manager, launched)) {
            // Exact process identity, not the local transport client, proved the launched process
            // gone. Only now may its transport and durable state be released.
            releaseProvenGoneLaunch(launchShell, spool);
        }
    }

    /**
     * Releases the transport, the durable evidence, and the run spool of a proven-gone launch.
     *
     * <p>Only a launch whose process is already proven gone may be released this way: closing a
     * transport whose process could still be the bundled binary would destroy an unverified
     * execution, and deleting its spool would destroy the only ownership evidence. Deleting the run
     * spool also releases this backend's in-process ownership of the run, which a proven-gone
     * launch no longer needs.</p>
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

    private static int awaitExitQuietly(RootShell shell) {
        try {
            return shell.awaitExit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * Waits for one exactly owned process to be proven gone inside the bounded post-signal window.
     *
     * <p>The local {@code su} process is only transport state on daemon-backed root managers. It
     * may disappear while the UID-0 execution continues, so cleanup authority comes only from a
     * fresh exact-identity observation through the already-owned helper session.</p>
     *
     * <p>No observation is started once the window is exhausted. A single observation can still
     * overrun it, because the transport bounds each helper operation with its own operation
     * timeout and a timed-out operation ends that session: an unresponsive helper therefore delays
     * the unproven-exit answer by at most one operation timeout, and the caller keeps its
     * fail-closed outcome because a stopped helper yields no proof of exit.</p>
     */
    private boolean awaitOwnedExitWithinCleanupWindow(
            ExecutionOwnershipManager manager,
            ExecutionIdentity identity
    ) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cleanupExitWaitMillis);
        while (true) {
            if (deadline - System.nanoTime() <= 0) {
                // No budget left for another observation, so the exit stays unproven.
                return false;
            }
            ExecutionOwnershipManager.Observation observation = manager.observe(identity);
            if (observation == ExecutionOwnershipManager.Observation.EXITED) {
                return true;
            }
            if (observation != ExecutionOwnershipManager.Observation.OWNED) {
                return false;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            try {
                Thread.sleep(Math.min(
                        CLEANUP_EXIT_POLL_MILLIS,
                        Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
                ));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (deadline - System.nanoTime() <= 0) {
                    return false;
                }
                return manager.observe(identity)
                        == ExecutionOwnershipManager.Observation.EXITED;
            }
        }
    }

    /**
     * Models the death of the application process that owns this backend's run state.
     *
     * <p>A JVM test cannot terminate the process that holds an operating-system file lock, so this
     * seam releases the ephemeral run ownership exactly as process death would: the lock is released
     * while the durable evidence and the run directory stay in place, and a restarted application
     * can then reconcile the leftover run. Test-only; production code never calls it.</p>
     */
    void releaseRunOwnershipForTesting() {
        RootRunSpool active = activeSpool;
        if (active != null) {
            active.releaseLease();
        }
        RootRunSpool prepared = preparedSpool;
        if (prepared != null) {
            prepared.releaseLease();
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
            } catch (IOException | RuntimeException e) {
                // A failed start returns no execution handle, so this preparation must also give
                // up its instance-local bookkeeping. Durable execution evidence remains the
                // authority for later recovery; keeping these fields would permanently reject
                // every later preparation even after the failed launch was proven gone.
                if (preparedSpool == spool) {
                    preparedSpool = null;
                }
                if (activeSpool == spool) {
                    activeSpool = null;
                }
                spool.releaseLease();
                throw e;
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
                        launchShell, identity, identity == null, spool, serveOutput, helperShell
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
            if (armed) {
                // Nothing was transported yet, so removing the durable pre-delivery run this
                // preparation owns is safe and its lease covers the deletion. A preparation that
                // never acquired the run must not delete a directory another owner holds.
                spool.delete();
            } else {
                // Arming failed: materialize() already removed anything it created while it held
                // its own lease, and this preparation never owned the run.
                spool.releaseLease();
            }
        }
    }

    /**
     * Verifies, after the dedicated launch transport ended, that the launched execution exited.
     *
     * <p>A root transport can outlive its usefulness: a daemon-backed root manager may let the
     * local {@code su} client exit while the UID-0 process keeps running, and the status such a
     * client reports then describes the client instead of Syncthing. This tracker therefore keeps
     * two facts apart. Exact process exit is proven only by the ownership manager observing the
     * recorded kernel identity as gone; the exit status is authenticated only when the transport
     * itself proved, during acquisition, that its awaited status belongs to the launched
     * process.</p>
     *
     * <p>The canonical root design allows the next privileged operation one bounded reacquisition
     * attempt and forbids polling for root authorization, so the tracker runs at most one
     * operation-scoped helper session. When that single attempt cannot prove the exact process
     * gone, because root is denied, unavailable, timed out, unreadable, or because the recorded
     * process is still present, the run reports a typed failure instead of an exit code. The
     * failure keeps the runtime admission, the durable record, and the run spool, signals nothing,
     * and leaves the state ready for an explicit later recovery operation to reclassify the
     * execution.</p>
     */
    private final class RootProcessExitTracker implements Runnable {
        /**
         * One immutable verification outcome.
         *
         * <p>The tracker publishes an outcome as one value, so a concurrent waiter can never
         * observe a verification attempt that is still running as a completed exit.</p>
         */
        private final class Outcome {
            final boolean exitProven;
            final int exitCode;
            final RuntimeException failure;

            Outcome(boolean exitProven, int exitCode, RuntimeException failure) {
                this.exitProven = exitProven;
                this.exitCode = exitCode;
                this.failure = failure;
            }
        }

        private final RootShell transport;
        private final ExecutionIdentity identity;
        private final CountDownLatch terminal = new CountDownLatch(1);
        /** Latest outcome, replaced only by a whole new verification attempt. */
        private volatile Outcome outcome;
        /** Status the transport reported when its own client process ended. */
        private volatile int transportExitCode;
        /** Whether that status belongs to the launched process, fixed while the client lived. */
        private volatile boolean transportStatusBelongsToExecution;
        /** Whether the verification the tracker thread ran has already been reported. */
        private boolean initialVerificationReported;
        private Thread thread;

        RootProcessExitTracker(RootShell transport, ExecutionIdentity identity) {
            this.transport = Objects.requireNonNull(transport);
            this.identity = identity;
        }

        void start() {
            thread = new Thread(this, "root-process-exit");
            thread.setDaemon(true);
            thread.start();
        }

        /**
         * Reports whether the exact launched process is proven gone, whether or not its exit status
         * could be authenticated.
         */
        boolean exactExitProven() {
            Outcome recorded = outcome;
            return recorded != null && recorded.exitProven;
        }

        /**
         * Reports whether an operation-scoped output reader has to stop waiting for more output.
         *
         * <p>A one-shot reader consumes this run's output tail before it waits for the exit, so the
         * tail has to end once this tracker has produced its answer - a proven exit or the typed
         * failure of one bounded verification attempt - because the operation reports that answer
         * instead of blocking for a further privileged attempt it is not allowed to make. The serve
         * log pump therefore does not use this signal: a long-running run keeps draining until its
         * process is proven exited.</p>
         */
        boolean outputEnded() {
            return outcome != null;
        }

        /**
         * Reports this run's outcome to one explicit caller.
         *
         * <p>The first caller reports the outcome the tracker thread recorded. Every later caller
         * runs one new logical privileged operation with its own single bounded reacquisition
         * attempt, because the canonical root design forbids polling for authorization but allows
         * an explicit later operation to reclassify the execution.</p>
         *
         * <p>Waiting and re-verifying hold this tracker's monitor while they read or replace the
         * recorded outcome, so no waiter can ever see a still-running verification attempt as a
         * completed exit.</p>
         *
         * @throws ExecutionExitStatusUnavailableException when the exit was proven without an
         *     authenticated status
         * @throws RootTransportException when the single bounded attempt could not prove the exit
         */
        int awaitExit() throws InterruptedException {
            terminal.await();
            synchronized (this) {
                if (initialVerificationReported) {
                    reverifyUnverifiedExit();
                } else {
                    initialVerificationReported = true;
                }
                Outcome reported = outcome;
                if (reported.failure != null) {
                    throw reported.failure;
                }
                return reported.exitCode;
            }
        }

        /**
         * Runs one bounded re-verification attempt for an exit an earlier attempt could not prove.
         *
         * <p>Must be called while holding this tracker's monitor. Only an exit that was never
         * proven can become proven later; a proven exit - including one whose status stayed
         * unattributable - keeps its recorded outcome, so an unauthenticated status can never be
         * reinterpreted as a real one. The replacement outcome is published as one value, so no
         * reader can observe the attempt between its start and its result.</p>
         */
        private void reverifyUnverifiedExit() {
            Outcome recorded = outcome;
            if (recorded.exitProven || recorded.failure == null) {
                return;
            }
            outcome = verifyExit(transportExitCode, transportStatusBelongsToExecution);
        }

        @Override
        public void run() {
            try {
                transportExitCode = transport.awaitExit();
                transportStatusBelongsToExecution = transport.exitStatusBelongsToLaunchedProcess();
                outcome = verifyExit(transportExitCode, transportStatusBelongsToExecution);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcome = new Outcome(false, 0, new RootTransportException(
                        RootFailure.ROOT_TRANSPORT_FAILED,
                        "Root execution exit verification was interrupted",
                        e
                ));
            } catch (RuntimeException e) {
                outcome = new Outcome(false, 0, e);
            } finally {
                terminal.countDown();
            }
        }

        /**
         * Runs one bounded exit verification and reports what it could prove.
         *
         * <p>One call is one logical privileged operation: it either observes the recorded process
         * gone through a single operation-scoped helper session, or reports the typed failure that
         * prevented the observation. Nothing here retries and nothing here signals.</p>
         */
        private Outcome verifyExit(int transportExitCode, boolean statusBelongsToExecution) {
            if (identity == null) {
                // This handle is created only after creation confirmation already proved the
                // launched process gone, so the exit is proven here and no further identity check
                // is available or needed. Only the status still depends on whether the transport
                // proved that the status it reports belongs to the launched process.
                return statusBelongsToExecution
                        ? new Outcome(true, transportExitCode, null)
                        : new Outcome(true, 0, unauthenticatedExit(transportExitCode));
            }
            ExecutionOwnershipManager.Observation observation;
            try {
                observation = withHelperSession(
                        ownershipManager -> ownershipManager.observe(identity)
                );
            } catch (RootTransportException rootUnavailable) {
                return new Outcome(false, 0, exitVerificationUnavailable(rootUnavailable));
            } catch (IOException unreadable) {
                return new Outcome(false, 0, exitVerificationUnavailable(unreadable));
            }
            if (observation != ExecutionOwnershipManager.Observation.EXITED) {
                return new Outcome(false, 0, new RootTransportException(
                        RootFailure.EXECUTION_VERIFICATION_FAILED,
                        "The recorded root execution was still observable as " + observation
                                + " after its transport ended, so its exit cannot be verified"
                                + " without polling for root authorization"
                ));
            }
            return statusBelongsToExecution
                    ? new Outcome(true, transportExitCode, null)
                    : new Outcome(true, 0, unauthenticatedExit(transportExitCode));
        }

        /**
         * Reports the typed result of a run whose process exit was proven while the transport could
         * not prove that the status it reported belongs to the launched process.
         */
        private ExecutionExitStatusUnavailableException unauthenticatedExit(int transportExitCode) {
            return new ExecutionExitStatusUnavailableException(
                    "The exact root execution exited, but the status " + transportExitCode
                            + " reported by its local transport client cannot be attributed to it"
            );
        }

        /**
         * Reports the single bounded exit verification attempt that could not be carried out, while
         * durable evidence may still describe a live execution.
         */
        private RootTransportException exitVerificationUnavailable(Throwable cause) {
            return new RootTransportException(
                    RootFailure.ROOT_AUTHORIZATION_LOST,
                    "Root capability is unavailable while the recorded execution may still be alive,"
                            + " so its exit could not be verified",
                    cause
            );
        }
    }

    /** One admitted root Syncthing execution. */
    private final class RootProcessExecution implements Execution {
        private final RootShell shell;
        /**
         * Operation-scoped helper session of the preparation that created this execution.
         *
         * <p>It stays open while the preparation's start(...) runs, and the output-tail failure
         * path uses it to re-verify and clean up through exact ownership without acquiring root
         * again.</p>
         */
        private final RootShell helperShell;
        private final ExecutionIdentity identity;
        private final boolean exitedBeforeIdentityCapture;
        private final RootRunSpool spool;
        private final RootServeLogPump serveLogPump;
        private final RootProcessExitTracker exitTracker;
        private final InputStream stdout;
        private final AtomicBoolean launchShellClosed = new AtomicBoolean();
        /** Whether this execution's process exit has already been proven. */
        private final AtomicBoolean exitProven = new AtomicBoolean();
        /** Exit status captured once, when the process exit was proven. */
        private volatile int provenExitCode;
        /**
         * Set when the exact process exit is proven while its status cannot be attributed to it.
         *
         * <p>The handle then settles like any proven exit and still never reports an exit code, so
         * no caller can feed an unattributed status into the ordinary Syncthing policy.</p>
         */
        private volatile ExecutionExitStatusUnavailableException exitStatusUnavailable;
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
                boolean serveOutput,
                RootShell helperShell
        ) {
            this.shell = shell;
            this.helperShell = helperShell;
            this.identity = identity;
            this.exitedBeforeIdentityCapture = exitedBeforeIdentityCapture;
            this.spool = spool;
            this.exitTracker = new RootProcessExitTracker(shell, identity);
            if (serveOutput) {
                this.exitTracker.start();
                // A long-running run owns its own output: one pump moves spool bytes into the
                // shared log while the process runs and drains the rest after it exits. No caller
                // receives the same bytes a second time.
                this.serveLogPump = new RootServeLogPump(
                        RootServeLogWriter.forRunDirectory(
                                spool.directory(), logFile, logTemporaryDirectory
                        ),
                        this::outputCannotGrowAnymore,
                        RootServeLogPump.POLL_MILLIS,
                        error -> logWarning("Could not reconcile root serve output", error)
                );
                this.serveLogPump.start();
                this.stdout = new ByteArrayInputStream(new byte[0]);
            } else {
                this.serveLogPump = null;
                // Opening the one-shot tail can fail and has its own exact-owner cleanup. Do not
                // start the independent exit tracker until that last fallible construction step
                // succeeds, or both paths could race to inspect and settle the same failed launch.
                this.stdout = openOutputTail(spool);
                this.exitTracker.start();
            }
        }

        /**
         * Reports whether this run can no longer append output to its spool.
         *
         * <p>The serve log pump follows this run for as long as it may still produce output. A
         * failed exit verification does not end that: the launched process may still be alive and
         * may still write, so the pump keeps draining until the exact process is proven exited or
         * an exact-ownership operation settles the run. Stopping the drain at a failed
         * verification would let the settlement delete a spool whose remaining bytes never
         * reached the shared log.</p>
         */
        private boolean outputCannotGrowAnymore() {
            return exitProven.get() || exitTracker.exactExitProven();
        }

        /**
         * Opens the operation-scoped output tail of this one-shot run.
         *
         * <p>Opening the tail is the last fallible step of the launch, so a failure here can
         * already have created the bundled process. The failure therefore never closes the launch
         * transport or deletes the run spool directly: it is settled through exact ownership, and
         * every unproven outcome leaves the possible execution untouched.</p>
         */
        private InputStream openOutputTail(RootRunSpool runSpool) {
            try {
                return runSpool.openOutputTail(exitTracker::outputEnded);
            } catch (IOException e) {
                settleUnavailableOutputTail(runSpool, shell);
                throw new RootTransportException(
                        RootFailure.ROOT_TRANSPORT_FAILED,
                        "Could not open the root run output spool",
                        e
                );
            }
        }

        /**
         * Settles a one-shot run whose output tail could not be opened.
         *
         * <p>A launch that never captured an execution identity already proved that its process
         * exited, so ordinary proven-gone cleanup is safe. Otherwise the launch may be the live
         * bundled process, so the run is only ever cleaned up through exact ownership: re-verify
         * the recorded process inside this preparation's own helper session, signal it through the
         * exact-ownership signal path, wait for the proven exit, and only then release the durable
         * record, the run spool, and the launch transport. If any of those steps is not proven, the
         * process, its evidence, and its spool stay untouched and only the in-process spool
         * ownership is given up, so a later recovery can still reconcile the run.</p>
         */
        private void settleUnavailableOutputTail(RootRunSpool runSpool, RootShell runShell) {
            if (identity == null) {
                releaseProvenGoneLaunch(runShell, runSpool);
                return;
            }
            if (!settleOwnedProcessAfterUnavailableOutputTail(runSpool, runShell)) {
                runSpool.releaseLease();
            }
        }

        /**
         * Releases one confirmed execution whose output tail could not be opened.
         *
         * <p>The exactly recorded process is re-verified before it is signaled, and the transport,
         * the durable record, and the spool are released only after its exact identity was proven
         * exited inside the bounded post-signal window. A process
         * that outlives that window keeps its transport, its evidence, and its spool, and this
         * operation reports the original output failure instead of waiting forever.</p>
         *
         * @return whether the process was re-verified, signaled, proven exited, and cleaned up
         */
        private boolean settleOwnedProcessAfterUnavailableOutputTail(
                RootRunSpool runSpool,
                RootShell runShell
        ) {
            ExecutionOwnershipManager manager = ownershipManagerFor(helperShell);
            try {
                if (manager.observe(identity) != ExecutionOwnershipManager.Observation.OWNED) {
                    return false;
                }
                if (manager.signalIfOwned(identity, ExecutionOwnershipManager.Signal.SIGKILL)
                        != ExecutionOwnershipManager.SignalAttempt.SIGNALED) {
                    return false;
                }
            } catch (RuntimeException e) {
                logWarning("Could not verify the launched root execution", e);
                return false;
            }
            if (!awaitOwnedExitWithinCleanupWindow(manager, identity)) {
                // Exact identity still has not proven exit after the bounded signal wait, so
                // neither a dead local transport nor an accepted signal may authorize cleanup.
                return false;
            }
            try {
                ownershipManagerFor(helperShell).clearAfterExit(identity);
            } catch (IOException | RuntimeException e) {
                logWarning("Could not clear the exited root execution record", e);
            }
            releaseProvenGoneLaunch(runShell, runSpool);
            return true;
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
                // The local su client is only transport state. The exit tracker waits for
                // that client and then independently proves the exact durable execution is gone
                // before this handle may finalize its record or spool.
                try {
                    provenExitCode = exitTracker.awaitExit();
                } catch (ExecutionExitStatusUnavailableException unavailable) {
                    if (!exitTracker.exactExitProven()) {
                        // The recorded process may still be alive, so nothing is finalized,
                        // nothing is released, and nothing is signaled. A later explicit recovery
                        // owns the decision.
                        throw unavailable;
                    }
                    // The process exit itself is proven, so this run settles exactly like any
                    // proven exit and still never reports an unattributed status as an exit code.
                    exitStatusUnavailable = unavailable;
                }
                // From here on the process is proven gone, so every remaining step has to complete
                // even when the caller interrupts the join below: an interrupted wait must not
                // strand the durable record, the spool ownership, or the transport of an execution
                // that exited.
                exitProven.set(true);
            }
            int settledExitCode;
            if (finalizationSettled.compareAndSet(false, true)) {
                settledExitCode = settleProvenExit(true);
            } else {
                // A repeated await - for example the cleanup retry in SyncthingRunnable after an
                // interrupted wait - waits until the settlement an earlier await already started
                // has finished and then reports the same result. It never repeats the durable
                // cleanup and never revisits the earlier decision to keep this run's spool for a
                // later reconciliation.
                finalizationFinished.await();
                settledExitCode = provenExitCode;
            }
            if (exitStatusUnavailable != null) {
                throw exitStatusUnavailable;
            }
            return settledExitCode;
        }

        /**
         * Completes the proven-exit settlement of this execution.
         *
         * <p>Runs whether or not the caller was interrupted while the serve log pump drained, so a
         * proven-gone execution never keeps its durable record, its spool ownership, or its
         * dedicated launch shell. A spool whose output did not reach the shared log is retained for
         * a later reconciliation instead of being discarded together with its output.</p>
         *
         * @param clearDurableRecord whether this settlement also removes the execution's durable
         *     record; an external exact-ownership operation that already settled the record passes
         *     {@code false} so the settlement never acquires root a second time
         * @return the proven exit status
         * @throws InterruptedException when the caller was interrupted while the serve log pump
         *     drained; the settlement itself has completed by then
         */
        private int settleProvenExit(boolean clearDurableRecord) throws InterruptedException {
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
                    finishProvenExit(clearDurableRecord);
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
         *
         * @param clearDurableRecord whether this settlement also removes the execution's durable
         *     record; see {@link #settleProvenExit(boolean)}
         */
        private void finishProvenExit(boolean clearDurableRecord) {
            if (clearDurableRecord && identity != null) {
                try {
                    clearAfterExit(identity);
                } catch (IOException | RuntimeException e) {
                    logWarning("Could not clear the exited root execution record", e);
                }
            }
            if (!spoolRequiresRecovery.get()) {
                spool.delete();
            } else {
                // The spool is retained for a later reconciliation, so this execution gives up
                // in-process ownership after every local reader and writer - the serve log pump
                // above - has stopped. The durable launch evidence keeps blocking unsafe
                // reconciliation of the run itself.
                spool.releaseLease();
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

        /**
         * Settles this execution after an external exact-ownership operation proved it exited.
         *
         * <p>An execution whose caller stopped waiting after a failed exit verification may still
         * have been running. Only an exact-ownership recovery operation may settle such an
         * execution, and it proved the recorded process gone before calling this method. The
         * settlement therefore never verifies, signals, or acquires root again: it releases exactly
         * the local resources this invocation still owns, through the same single proven-exit
         * settlement a completed wait runs.</p>
         *
         * <p>No authenticated exit status exists on this path, so the handle records the typed
         * unauthenticated-exit result: a repeated wait must report that the exit was proven without
         * a status instead of inventing one.</p>
         */
        @Override
        public void settleAfterProvenExit() {
            exitProven.set(true);
            if (!finalizationSettled.compareAndSet(false, true)) {
                // The execution already settled its own exit and reported the authenticated
                // outcome; an external settlement must not replace it.
                return;
            }
            exitStatusUnavailable = new ExecutionExitStatusUnavailableException(
                    "The exact root execution exit was proven by an external ownership operation,"
                            + " so no authenticated Syncthing exit status is available"
            );
            try {
                settleProvenExit(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                logWarning("Could not settle an externally verified root execution", e);
            }
        }

        @Override
        public boolean exitProven() {
            return exitProven.get() || exitTracker.exactExitProven();
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
