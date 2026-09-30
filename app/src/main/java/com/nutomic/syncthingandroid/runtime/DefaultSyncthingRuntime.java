package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.Objects;

/**
 * Mode-neutral runtime for bundled Syncthing execution and semantic operations.
 *
 * <p>The runtime owns the invariant that only one bundled Syncthing invocation may be active.
 * Backend-specific process and folder mechanics remain behind {@link PrivilegeBackend}.</p>
 */
public final class DefaultSyncthingRuntime {
    private final PrivilegeBackend backend;
    private final AdmissionGate admission = new AdmissionGate();

    public DefaultSyncthingRuntime(PrivilegeBackend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    /**
     * Starts a bundled Syncthing invocation if the runtime is idle.
     *
     * <p>This is the ordinary start used by one-shot commands. It never waits for another
     * invocation, and it does not take admission away from a service lifecycle start that is
     * already waiting for it.</p>
     *
     * @throws ExecutionAdmissionException when another bundled invocation owns admission or a
     *                                     service lifecycle start is waiting for it
     */
    public SyncthingExecution start(SyncthingCommand command, SyncthingEnvironment environment)
            throws IOException, ExecutableNotFoundException {
        admission.acquireOneShot();
        return launch(command, environment);
    }

    /**
     * Starts the service-owned Syncthing lifecycle invocation after the active bundled invocation,
     * if any, has exited and released admission.
     *
     * <p>Registering as waiting and acquiring admission are one runtime operation, and a registered
     * service lifecycle start keeps priority, so no other invocation can be admitted between the
     * release of the previous invocation and this launch. The caller must invoke this on a
     * background thread because the wait lasts until the active invocation observes its exit.</p>
     *
     * @throws InterruptedException when the calling thread is interrupted while waiting
     */
    public SyncthingExecution startServiceLifecycle(
            SyncthingCommand command,
            SyncthingEnvironment environment
    ) throws IOException, ExecutableNotFoundException, InterruptedException {
        admission.awaitServiceLifecycleAdmission();
        return launch(command, environment);
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

    private SyncthingExecution launch(SyncthingCommand command, SyncthingEnvironment environment)
            throws IOException, ExecutableNotFoundException {
        try {
            PrivilegeBackend.Execution execution = backend.start(command, environment);
            return new SyncthingExecution(execution, admission::release);
        } catch (IOException | ExecutableNotFoundException | RuntimeException e) {
            admission.release();
            throw e;
        }
    }

    /**
     * Performs the selected backend's Syncthing-specific process compatibility cleanup.
     */
    public void terminateBundledSyncthing() {
        backend.terminateBundledSyncthing();
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

    public void runFolderScripts(
            ConfiguredFolderReference folder,
            FolderEvent event
    ) {
        backend.runFolderScripts(folder, event);
    }

    /**
     * Admission exercise executed while a service lifecycle start waits for admission.
     */
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
