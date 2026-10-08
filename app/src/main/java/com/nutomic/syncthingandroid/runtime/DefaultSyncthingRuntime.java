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
         * Atomically settles a non-launchable recovery result against service cancellation.
         *
         * <p>The runtime invokes this for every recovery verdict that forbids a launch: the
         * classification backend preparation performs, and the final classification taken under
         * the process-start reservation. Implementations without a service-owned cancellation
         * source need no action. An implementation that owns one must let an already-revoked
         * lifecycle stay the reported outcome, which is what
         * {@link LifecycleLaunchPermit#commitRecoveryBlocked()} enforces when it throws
         * {@link LifecycleLaunchPermit.CancelledException}. This callback must only settle the
         * launch decision; it must not reconcile or signal an execution.</p>
         */
        default void commitRecoveryBlocked() {}

        /** Runs prospective launch checks and commits process creation at the final boundary. */
        void check();
    }

    /**
     * Acquires the process-start reservation held across final recovery classification and the
     * backend process creation that follows it.
     */
    @FunctionalInterface
    interface LaunchPermitAcquirer {
        OwnedExecutionShutdown.LaunchPermit acquire(boolean waitForPendingRequests)
                throws InterruptedException;
    }

    private final PrivilegeBackend backend;
    private final AdmissionGate admission = new AdmissionGate();
    private final LaunchPermitAcquirer launchPermitAcquirer;
    /** Guards the admitted execution handle the runtime keeps for a later recovery settlement. */
    private final Object settlementLock = new Object();
    /** Handle of the admitted execution, kept while it still owns runtime admission. */
    private SyncthingExecution admittedExecution;
    /** Whether the admitted execution's caller handed its settlement to runtime recovery. */
    private boolean settlementHandedOff;
    /**
     * Test seam invoked after a launch-permitting recovery assessment returned and before the
     * handed-off execution that assessment was measured against settles, so a test can prove that
     * an execution which starts and hands off its own settlement during that interval keeps its
     * admission. Production code leaves it {@code null}.
     */
    Runnable recoveryAssessmentReturnedHookForTesting;

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
     * <p>The cancellation check settles the final recovery classification before a recovery failure
     * is reported. The final check runs only when recovery permits launch, directly in front of
     * process creation and after every fallible preparation step already ran.</p>
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
                    throw recoveryBlocked(recovery, launchCheck);
                }
                recovery = backend.recoverExecutions();
            }
            if (!recovery.mayLaunch()) throw recoveryBlocked(recovery, launchCheck);

            // Preparation - rooted activation above all - is slow, can prompt, and can fail, so it
            // runs before the process-start reservation is taken and never holds that reservation.
            PrivilegeBackend.LaunchPreparation preparation;
            try {
                preparation = backend.prepareLaunch(command, environment);
            } catch (ExecutionRecoveryException preparationBlocked) {
                throw recoveryBlocked(preparationBlocked.assessment(), launchCheck);
            }
            boolean started = false;
            try {
                try (OwnedExecutionShutdown.LaunchPermit permit =
                             launchPermitAcquirer.acquire(serviceLifecycle)) {
                    // The preparation above may be stale if another runtime starts a process before
                    // this launch reservation is acquired. Reclassify under the reservation using
                    // capability the preparation already acquired, so a superuser backend never
                    // acquires root or prompts the user while process-start quiescence is blocked.
                    // Only a launchable result may proceed; nothing is stopped or reconciled here.
                    ExecutionOwnershipManager.RecoveryAssessment finalRecovery =
                            preparation.classifyLaunch();
                    if (!finalRecovery.mayLaunch()) {
                        throw recoveryBlocked(finalRecovery, launchCheck);
                    }
                    // Every slow or fallible step already ran during preparation, so the committed
                    // launch check sits directly in front of process creation. A check that refuses
                    // the launch discards the preparation, which closes the prepared transport and
                    // removes this run's armed pending state without having created a process.
                    // The durable launch state is armed here, under the reservation and after
                    // the final classification: it is the last fallible step of the launch, so it
                    // must fail before the lifecycle layer commits to creating a process. Arming
                    // therefore sits directly in front of the launch check, never behind it.
                    preparation.armLaunch();
                    if (launchCheck != null) launchCheck.check();
                    // The preparation releases the reservation itself once the process and its
                    // durable ownership evidence exist. Slow post-launch verification runs after
                    // that release, so it never keeps process-start quiescence blocked.
                    PrivilegeBackend.Execution execution = preparation.start(permit::close);
                    started = true;
                    return retainAdmittedExecution(execution);
                }
            } finally {
                if (!started) preparation.discard();
            }
        } catch (IOException | ExecutableNotFoundException | InterruptedException
                 | RuntimeException e) {
            admission.release();
            throw e;
        }
    }

    /**
     * Hands one admitted execution to its caller while the runtime keeps a reference to it.
     *
     * <p>An execution whose caller stops waiting while its process may still be alive cannot be
     * settled by that caller. The runtime keeps the handle until an exact-ownership operation
     * proves the recorded process gone, so the execution's local resources and the runtime
     * admission it owns are released at the one point where releasing them is safe.</p>
     */
    private SyncthingExecution retainAdmittedExecution(PrivilegeBackend.Execution execution) {
        SyncthingExecution handle = new SyncthingExecution(execution, this::releaseAdmission);
        synchronized (settlementLock) {
            admittedExecution = handle;
            settlementHandedOff = false;
        }
        return handle;
    }

    /**
     * Releases the single admission slot and forgets the handle that owned it.
     *
     * <p>Only the admitted execution releases admission, and a new invocation cannot be admitted
     * before this release, so clearing the retained handle here can never discard a newer
     * execution.</p>
     */
    private void releaseAdmission() {
        synchronized (settlementLock) {
            admittedExecution = null;
            settlementHandedOff = false;
        }
        admission.release();
    }

    /**
     * Hands an execution's settlement to runtime recovery after its caller stopped waiting.
     *
     * <p>A caller that can no longer wait for the launched process - because exit verification
     * failed while the process may still be alive - must not settle the execution and must not
     * release its admission. The runtime keeps both until an exact-ownership operation proves the
     * recorded process exited; {@link #clearAfterExit(ExecutionIdentity)} and a launch-permitting
     * {@link #recoverExecutions()} are the operations that carry that proof.</p>
     */
    public void handOffExecutionSettlement(SyncthingExecution execution) {
        synchronized (settlementLock) {
            if (admittedExecution == execution) {
                settlementHandedOff = true;
            }
        }
    }

    /**
     * Settles the handed-off execution once an exact-ownership operation proved its process gone.
     *
     * @param provenIdentity identity whose exact exit was just proven, or {@code null} when the
     *     caller proved that no owned execution remains at all
     */
    private void settleHandedOffExecution(ExecutionIdentity provenIdentity) {
        SyncthingExecution handle;
        synchronized (settlementLock) {
            handle = admittedExecution;
            if (handle == null || !settlementHandedOff) {
                return;
            }
            if (provenIdentity != null) {
                ExecutionIdentity admitted = handle.identity();
                if (admitted == null || !admitted.matches(provenIdentity)) {
                    return;
                }
            }
        }
        handle.settleAfterProvenExit();
    }

    /** Reports the execution that currently owns runtime admission, or {@code null} when the
     * slot is free. */
    private SyncthingExecution admittedExecutionHandle() {
        synchronized (settlementLock) {
            return admittedExecution;
        }
    }

    /**
     * Settles the handed-off execution one launch-permitting assessment was measured against.
     *
     * <p>The assessment proves that the execution it classified exited, so only the handle that
     * held admission while it ran may settle here. Matching the handle by instance - instead of
     * settling whichever execution currently holds admission - keeps the proof bound to the
     * execution it covers: a later execution that reached failed exit verification and handed off
     * its own settlement during the assessment keeps its admission until an exact-ownership
     * operation proves that execution's process gone, so no overlapping launch can be admitted
     * against a possibly live process.</p>
     *
     * @param assessedExecution handle that held admission when the assessment started; ignored
     *     when it is not (or no longer) the handed-off admitted execution
     */
    private void settleAssessedHandedOffExecution(SyncthingExecution assessedExecution) {
        if (assessedExecution == null) {
            return;
        }
        synchronized (settlementLock) {
            if (admittedExecution != assessedExecution || !settlementHandedOff) {
                return;
            }
        }
        assessedExecution.settleAfterProvenExit();
    }

    /**
     * Settles every non-launchable recovery verdict against lifecycle cancellation before the
     * verdict can escape the runtime. A STOP that already revoked the launch therefore remains the
     * terminal outcome at the initial, preparation, and final recovery boundaries alike.
     */
    private static ExecutionRecoveryException recoveryBlocked(
            ExecutionOwnershipManager.RecoveryAssessment recovery,
            LifecycleLaunchCheck launchCheck
    ) {
        if (launchCheck != null) {
            launchCheck.commitRecoveryBlocked();
        }
        return new ExecutionRecoveryException(recovery);
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
        SyncthingExecution assessedExecution = admittedExecutionHandle();
        ExecutionOwnershipManager.RecoveryAssessment assessment = backend.recoverExecutions();
        if (assessment.mayLaunch()) {
            if (recoveryAssessmentReturnedHookForTesting != null) {
                recoveryAssessmentReturnedHookForTesting.run();
            }
            // Recovery proved that no owned execution remains, so an execution whose caller
            // handed settlement to recovery settles here, without any further acquisition. The
            // settlement is bound to the handle the assessment was measured against: a handle
            // admitted afterwards belongs to an execution this assessment never classified, so it
            // keeps its admission until an exact-ownership proof covers it.
            settleAssessedHandedOffExecution(assessedExecution);
        }
        return assessment;
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

    /**
     * Removes durable ownership evidence after the exact process exit has been proven.
     *
     * <p>A handed-off execution with the same identity settles in the same step: the caller just
     * proved that this execution's process exited, so its local resources and the runtime
     * admission it still owns are released here.</p>
     */
    public boolean clearAfterExit(ExecutionIdentity identity) throws IOException {
        try {
            return backend.clearAfterExit(identity);
        } finally {
            settleHandedOffExecution(identity);
        }
    }

    public ConfigStorage configStorage() {
        return backend.configStorage();
    }

    /**
     * Returns the selected backend's semantic Managed State transfer.
     *
     * <p>The runtime only delegates: it never substitutes application-UID state access when the
     * selected backend cannot serve an operation, so a failed privileged transfer stays a reported
     * failure.</p>
     */
    public ManagedStateTransfer managedStateTransfer() {
        return backend.managedStateTransfer();
    }

    /**
     * Returns the selected backend's semantic HTTPS certificate storage.
     *
     * <p>The certificate workflow uses this capability for prior-state capture, mutation, and
     * rollback, so it needs no direct file access of its own.</p>
     */
    public HttpsCertificateStorage httpsCertificateStorage() {
        return backend.httpsCertificateStorage();
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
