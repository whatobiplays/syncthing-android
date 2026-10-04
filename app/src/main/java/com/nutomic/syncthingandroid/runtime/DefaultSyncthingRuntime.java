package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.Objects;

/**
 * Mode-neutral runtime for bundled Syncthing execution and semantic operations.
 *
 * <p>The runtime owns the invariant that only one bundled Syncthing invocation may be active.
 * Backend-specific process and folder mechanics remain behind {@link PrivilegeBackend}.</p>
 */
public final class DefaultSyncthingRuntime
        implements OwnedExecutionShutdown.ExecutionControl {
    @FunctionalInterface
    public interface OwnedExecutionRecoveryHandler {
        /** Returns true only after the exact owned execution is proven to have exited. */
        boolean stopOwnedExecution(ExecutionIdentity identity) throws InterruptedException;
    }

    @FunctionalInterface
    public interface LifecycleLaunchCheck {
        /**
         * Observes whether the service has revoked this launch without committing process
         * creation. Lifecycle implementations use this before a final recovery failure is
         * reported, so an already-cancelled launch keeps its expected cancellation outcome.
         *
         * <p>Checks without a service-owned cancellation source need no action.</p>
         */
        default void checkCancellation() {}

        /** Runs prospective launch checks and commits process creation at the final boundary. */
        void check();
    }

    /** Acquires the process-start reservation used immediately before backend process creation. */
    @FunctionalInterface
    interface LaunchPermitAcquirer {
        OwnedExecutionShutdown.LaunchPermit acquire(boolean waitForPendingRequests)
                throws InterruptedException;
    }

    private final PrivilegeBackend backend;
    private final AdmissionGate admission = new AdmissionGate();
    private final LaunchPermitAcquirer launchPermitAcquirer;

    public DefaultSyncthingRuntime(PrivilegeBackend backend) {
        this(backend, OwnedExecutionShutdown::acquireLaunchPermit);
    }

    DefaultSyncthingRuntime(
            PrivilegeBackend backend,
            LaunchPermitAcquirer launchPermitAcquirer
    ) {
        this.backend = Objects.requireNonNull(backend);
        this.launchPermitAcquirer = Objects.requireNonNull(launchPermitAcquirer);
    }

    /**
     * Starts a bundled one-shot invocation if runtime admission and recovery permit it.
     *
     * @throws ExecutionAdmissionException when another invocation owns admission
     * @throws ExecutionRecoveryException when an owned or ambiguous process blocks a new launch
     */
    public SyncthingExecution start(SyncthingCommand command, SyncthingEnvironment environment)
            throws IOException, ExecutableNotFoundException {
        return start(command, environment, null);
    }

    /** Starts a one-shot invocation with an optional exact-ownership recovery policy. */
    public SyncthingExecution start(
            SyncthingCommand command,
            SyncthingEnvironment environment,
            OwnedExecutionRecoveryHandler recoveryHandler
    ) throws IOException, ExecutableNotFoundException {
        admission.acquireOneShot();
        try {
            return launch(command, environment, recoveryHandler, null, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("One-shot execution recovery was interrupted", e);
        }
    }

    /** Starts an ordinary one-shot with a final callback immediately before process creation. */
    public SyncthingExecution startOneShot(
            SyncthingCommand command,
            SyncthingEnvironment environment,
            OwnedExecutionRecoveryHandler recoveryHandler,
            Runnable beforeProcessCreation
    ) throws IOException, ExecutableNotFoundException {
        Runnable launchCheck = Objects.requireNonNull(beforeProcessCreation);
        return startOneShotWithLifecycleCheck(
                command, environment, recoveryHandler, launchCheck::run
        );
    }

    /**
     * Starts a cancellable service-owned one-shot with separate cancellation observation and final
     * process-creation checks.
     *
     * <p>The cancellation check runs after final recovery classification and before a recovery
     * failure is reported. The final check runs only when recovery permits launch and remains the
     * process-creation commit boundary.</p>
     */
    public SyncthingExecution startOneShotWithLifecycleCheck(
            SyncthingCommand command,
            SyncthingEnvironment environment,
            OwnedExecutionRecoveryHandler recoveryHandler,
            LifecycleLaunchCheck launchCheck
    ) throws IOException, ExecutableNotFoundException {
        LifecycleLaunchCheck checkedLaunch = Objects.requireNonNull(launchCheck);
        admission.acquireOneShot();
        try {
            return launch(command, environment, recoveryHandler, checkedLaunch, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("One-shot execution recovery was interrupted", e);
        }
    }

    /**
     * Starts the service-owned invocation after the current admission owner exits.
     *
     * @throws InterruptedException when interrupted while waiting for admission or recovery
     */
    public SyncthingExecution startServiceLifecycle(
            SyncthingCommand command,
            SyncthingEnvironment environment
    ) throws IOException, ExecutableNotFoundException, InterruptedException {
        return startServiceLifecycle(command, environment, null);
    }

    /**
     * Starts a lifecycle invocation after resolving any previously owned execution.
     *
     * <p>The recovery handler runs only for an exactly verified owned execution and must report
     * success only after it proves that execution has exited. Ambiguous candidates never reach the
     * handler.</p>
     */
    public SyncthingExecution startServiceLifecycle(
            SyncthingCommand command,
            SyncthingEnvironment environment,
            OwnedExecutionRecoveryHandler recoveryHandler
    ) throws IOException, ExecutableNotFoundException, InterruptedException {
        return startServiceLifecycle(command, environment, recoveryHandler, null);
    }

    /** Starts a lifecycle invocation with a final check before replacement launch. */
    public SyncthingExecution startServiceLifecycle(
            SyncthingCommand command,
            SyncthingEnvironment environment,
            OwnedExecutionRecoveryHandler recoveryHandler,
            LifecycleLaunchCheck launchCheck
    ) throws IOException, ExecutableNotFoundException, InterruptedException {
        admission.awaitServiceLifecycleAdmission();
        return launch(
                command,
                environment,
                recoveryHandler,
                launchCheck,
                true
        );
    }

    /**
     * Runs one admission exercise while a service lifecycle start is registered as waiting.
     *
     * <p>Package-private test seam. While the exercise runs, the runtime keeps the waiting service
     * lifecycle start from being admitted, which lets regressions observe the boundary between the
     * release of the active invocation and the admission of the waiting lifecycle start without
     * relying on thread timing.</p>
     *
     * @param exercise admission exercise executed while the lifecycle start is waiting
     */
    void whileServiceLifecycleStartWaits(AdmissionExercise exercise)
            throws IOException, ExecutableNotFoundException, InterruptedException {
        synchronized (admission) {
            admission.awaitServiceLifecycleStartRegistered();
            exercise.run();
        }
    }

    private SyncthingExecution launch(
            SyncthingCommand command,
            SyncthingEnvironment environment,
            OwnedExecutionRecoveryHandler recoveryHandler,
            LifecycleLaunchCheck launchCheck,
            boolean serviceLifecycle
    ) throws IOException, ExecutableNotFoundException, InterruptedException {
        try {
            if (serviceLifecycle) {
                OwnedExecutionShutdown.awaitNoUnquiescedRestShutdownRequests();
            } else {
                OwnedExecutionShutdown.requireNoUnquiescedRestShutdownRequests();
            }
            backend.validateLaunchPrerequisites();
            OwnedExecutionShutdown.awaitProcessStartQuiescence();
            ExecutionOwnershipManager.RecoveryAssessment recovery =
                    backend.recoverExecutions();
            if (recovery.classification()
                    == ExecutionOwnershipManager.Classification.OWNED_EXECUTION) {
                if (recoveryHandler == null
                        || !recoveryHandler.stopOwnedExecution(recovery.ownedExecution())) {
                    throw new ExecutionRecoveryException(recovery);
                }
                recovery = backend.recoverExecutions();
            }
            if (!recovery.mayLaunch()) throw new ExecutionRecoveryException(recovery);

            try (OwnedExecutionShutdown.LaunchPermit ignored =
                         launchPermitAcquirer.acquire(serviceLifecycle)) {
                // The initial recovery above may be stale if another runtime starts a process
                // before this launch reservation is acquired. Reclassify under the reservation,
                // where only a launchable result may proceed; do not stop or reconcile here.
                ExecutionOwnershipManager.RecoveryAssessment finalRecovery =
                        backend.recoverExecutions();
                if (launchCheck != null) launchCheck.checkCancellation();
                if (!finalRecovery.mayLaunch()) {
                    throw new ExecutionRecoveryException(finalRecovery);
                }
                if (launchCheck != null) launchCheck.check();
                PrivilegeBackend.Execution execution = backend.start(command, environment);
                return new SyncthingExecution(execution, admission::release);
            }
        } catch (IOException | ExecutableNotFoundException | InterruptedException
                 | RuntimeException e) {
            admission.release();
            throw e;
        }
    }

    public ExecutionOwnershipManager.RecoveryAssessment recoverExecutions() {
        try {
            OwnedExecutionShutdown.awaitProcessStartQuiescence();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Execution recovery was interrupted while waiting for process creation", e
            );
        }
        return backend.recoverExecutions();
    }

    @Override
    public ExecutionOwnershipManager.SignalAttempt signalIfOwned(
            ExecutionIdentity identity,
            ExecutionOwnershipManager.Signal signal
    ) {
        return backend.signalIfOwned(identity, signal);
    }

    @Override
    public ExecutionOwnershipManager.Observation observe(ExecutionIdentity identity) {
        return backend.observe(identity);
    }

    /** Removes durable ownership evidence after the exact process exit has been proven. */
    public boolean clearAfterExit(ExecutionIdentity identity) throws IOException {
        return backend.clearAfterExit(identity);
    }

    public ConfigStorage configStorage() {
        return backend.configStorage();
    }

    public FolderWriteability validateCandidateFolder(String path) {
        return backend.validateCandidateFolder(path);
    }

    public ConflictDiscoveryResult discoverConflicts(ConfiguredFolderReference folder) {
        return backend.discoverConflicts(folder);
    }

    public FolderIgnoreResult loadFolderIgnoreList(ConfiguredFolderReference folder) {
        return backend.loadFolderIgnoreList(folder);
    }

    public void saveFolderIgnoreList(ConfiguredFolderReference folder, String[] ignore) {
        backend.saveFolderIgnoreList(folder, ignore);
    }

    public void runFolderScripts(ConfiguredFolderReference folder, FolderEvent event) {
        backend.runFolderScripts(folder, event);
    }

    @FunctionalInterface
    interface AdmissionExercise {
        void run() throws IOException, ExecutableNotFoundException, InterruptedException;
    }

    /**
     * Coordinates the single bundled-invocation admission slot.
     *
     * <p>An invocation owns admission from the moment it is admitted until its execution exit is
     * observed. A service lifecycle start registers as waiting before it blocks, and a registered
     * lifecycle start keeps priority: ordinary one-shot starts are rejected while one is waiting,
     * and releasing admission wakes the waiting lifecycle start instead of freeing the slot for
     * one-shots.</p>
     */
    private static final class AdmissionGate {
        private boolean invocationActive;
        private int waitingServiceLifecycleStarts;

        private void acquireOneShot() {
            synchronized (this) {
                if (invocationActive || waitingServiceLifecycleStarts > 0) {
                    throw new ExecutionAdmissionException();
                }
                invocationActive = true;
            }
        }

        private void awaitServiceLifecycleAdmission() throws InterruptedException {
            synchronized (this) {
                waitingServiceLifecycleStarts++;
                notifyAll();
                try {
                    while (invocationActive) {
                        wait();
                    }
                    invocationActive = true;
                } finally {
                    waitingServiceLifecycleStarts--;
                }
            }
        }

        /**
         * Blocks until a service lifecycle start is registered as waiting.
         *
         * <p>The caller holds this gate's monitor, so the registered lifecycle start stays waiting
         * until the caller leaves the coordination.</p>
         */
        private void awaitServiceLifecycleStartRegistered() throws InterruptedException {
            while (waitingServiceLifecycleStarts == 0) {
                wait();
            }
        }

        private void release() {
            synchronized (this) {
                invocationActive = false;
                notifyAll();
            }
        }
    }
}
