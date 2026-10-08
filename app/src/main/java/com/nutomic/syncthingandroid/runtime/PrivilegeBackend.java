package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.io.InputStream;

/** Backend contract for launching and controlling bundled Syncthing executions. */
public interface PrivilegeBackend {
    void validateLaunchPrerequisites() throws IOException, ExecutableNotFoundException;

    Execution start(SyncthingCommand command, SyncthingEnvironment environment)
            throws IOException, ExecutableNotFoundException;

    /**
     * Runs every fallible launch preparation that must finish before the lifecycle layer commits to
     * creating a process, and returns the prepared invocation.
     *
     * <p>Root activation is slow, can prompt, and can fail, and a root launch has to be encoded and
     * validated before any process can be created, so a backend whose launch needs either must not
     * perform that work after the caller's lifecycle launch check has committed. Callers therefore
     * prepare first, settle their launch check, and then create the process with
     * {@link LaunchPreparation#start(ProcessStartReservation)}. A caller that does not create the
     * process must call {@link LaunchPreparation#discard()}, which releases every prepared resource
     * without creating a process.</p>
     *
     * <p>The default implementation performs no preparation: backends that already create their
     * process in {@link #start} keep that behaviour, and their whole launch still runs after the
     * caller's launch check.</p>
     */
    default LaunchPreparation prepareLaunch(
            SyncthingCommand command,
            SyncthingEnvironment environment
    ) throws IOException, ExecutableNotFoundException {
        return new LaunchPreparation() {
            @Override
            public ExecutionOwnershipManager.RecoveryAssessment classifyLaunch() {
                // This backend needs no prepared capability: its recovery classification
                // reads the local process table and never acquires root.
                return PrivilegeBackend.this.recoverExecutions();
            }

            @Override
            public Execution start(ProcessStartReservation reservation) throws IOException,
                    ExecutableNotFoundException {
                Execution execution = PrivilegeBackend.this.start(command, environment);
                reservation.release();
                return execution;
            }

            @Override
            public void armLaunch() {
                // This backend records no durable state before start(...), so there is nothing to
                // arm: its launch is completely described by start(...) itself.
            }

            @Override
            public void discard() {
                // This backend creates nothing before start(...).
            }
        };
    }

    /**
     * One bundled invocation that finished its fallible preparation but has not created a process.
     *
     * <p>A preparation owns whatever it prepared - for the superuser backend an acquired root
     * shell and a run spool - so callers must always either start it or discard it.</p>
     */
    interface LaunchPreparation {
        /**
         * Re-runs the final recovery classification under the caller's process-start
         * reservation.
         *
         * <p>The caller takes the reservation, asks the preparation for the final recovery
         * decision, settles its lifecycle launch check, and only then starts the prepared
         * invocation. This classification therefore must not acquire root capability, prompt
         * the user, or wait on a root session: a preparation performs every slow or fallible
         * step - including the root capability this classification needs - before the caller
         * takes the reservation. A backend whose classification cannot run without new root
         * capability must fail closed here instead of acquiring it.</p>
         *
         * @return final recovery classification; only a launchable result may proceed
         */
        ExecutionOwnershipManager.RecoveryAssessment classifyLaunch();

        /**
         * Arms the durable launch state this preparation needs before the lifecycle layer commits
         * to creating a process.
         *
         * <p>Call at most once, after {@link #classifyLaunch()} accepted the launch and while the
         * caller still holds the process-start reservation, and always before the caller's
         * lifecycle launch check. Arming performs the last fallible step of a launch - for a
         * superuser backend, durably recording which transport may become the bundled process - so
         * it has to fail before the lifecycle layer has committed to process creation, never
         * after.</p>
         *
         * <p>Slow work such as root activation belongs to preparation instead. A backend that
         * records nothing before {@link #start(ProcessStartReservation)} arms nothing.</p>
         *
         * @throws java.io.IOException when the durable state cannot be written; the preparation
         *     then releases everything it owns, and the caller must discard it
         */
        void armLaunch() throws IOException;

        /**
         * Creates the bundled process.
         *
         * <p>Call at most once, immediately after the lifecycle launch check has committed. Every
         * deterministic or fallible step already ran during preparation, so this call starts the
         * unavoidable transport or process-creation operation directly. A preparation that fails
         * during creation releases what it prepared itself.</p>
         *
         * @param reservation reservation the caller holds across process creation; the preparation
         *     releases it once the process and the durable evidence that proves its ownership exist
         */
        Execution start(ProcessStartReservation reservation)
                throws IOException, ExecutableNotFoundException;

        /** Releases the preparation without creating a process; safe to call after start. */
        void discard();
    }

    /**
     * Releases the caller's process-start reservation once the process-creation boundary has
     * ended.
     *
     * <p>Callers hold one reservation across process creation so that shutdown and recovery work
     * can wait for creation to finish. The preparation receives the reservation while it creates
     * the process and releases it exactly once, and only after creation is proven rather than
     * merely requested: the durable pre-execution evidence must be complete and valid for this
     * launch, the recorded pid, start time, boot id and run token must still describe the launched
     * process, and the process must either have reached the bundled executable or be in a handoff
     * state that every concurrent recovery classifier recognises as an in-progress owned launch.
     * Releasing earlier would let a competing runtime observe neither ownership evidence nor a
     * bundled Syncthing candidate and classify its own launch as permitted while the first root
     * shell is still about to exec.</p>
     *
     * <p>A preparation that cannot prove creation must not release the reservation and must fail
     * closed instead. Slower work such as canonical-record updates or post-launch verification
     * happens after release, so it never keeps process-start quiescence blocked behind a root
     * prompt or helper session.</p>
     */
    @FunctionalInterface
    interface ProcessStartReservation {
        /** No reservation was taken, as when a caller creates a process without one. */
        ProcessStartReservation NONE = () -> { };

        void release();
    }

    ExecutionOwnershipManager.RecoveryAssessment recoverExecutions();

    default ExecutionOwnershipManager.SignalAttempt signalIfOwned(
            ExecutionIdentity identity,
            ExecutionOwnershipManager.Signal signal
    ) {
        return ExecutionOwnershipManager.SignalAttempt.NOT_OWNED;
    }

    default ExecutionOwnershipManager.Observation observe(ExecutionIdentity identity) {
        return ExecutionOwnershipManager.Observation.UNKNOWN;
    }

    default boolean clearAfterExit(ExecutionIdentity identity) throws IOException {
        return false;
    }

    ConfigStorage configStorage();

    /**
     * Returns the semantic Managed State transfer of the selected backend.
     *
     * <p>Callers express state operations through the returned capability and never learn whether
     * the selected backend runs with the application UID or through the superuser transport. A
     * failed privileged operation is reported as a state failure and is never retried through the
     * other backend.</p>
     */
    ManagedStateTransfer managedStateTransfer();

    /**
     * Returns the semantic HTTPS certificate storage of the selected backend.
     *
     * <p>The certificate workflow captures prior state, mutates the pair, and restores the capture
     * on failure through this capability, so it works unchanged while Syncthing runs as UID 0.</p>
     */
    HttpsCertificateStorage httpsCertificateStorage();

    FolderWriteability validateCandidateFolder(String path);

    ConflictDiscoveryResult discoverConflicts(ConfiguredFolderReference folder);

    FolderIgnoreResult loadFolderIgnoreList(ConfiguredFolderReference folder);

    void saveFolderIgnoreList(ConfiguredFolderReference folder, String[] ignore);

    void runFolderScripts(ConfiguredFolderReference folder, FolderEvent event);

    interface Execution {
        InputStream stdout();

        InputStream stderr();

        int await() throws InterruptedException;

        void destroy();

        /**
         * Releases this execution's local resources after an external operation proved its process
         * exited.
         *
         * <p>A caller that stops waiting after a failed exit verification leaves its execution to
         * recovery: only an exact-ownership operation may settle a process that may still be
         * alive, and it calls this method once it proved the recorded process gone. An
         * implementation must never signal the process and must never perform a privileged
         * acquisition here - the exact proof already happened outside this handle - and must
         * tolerate being called after the execution already settled its own exit.</p>
         */
        default void settleAfterProvenExit() {
        }

        default ExecutionIdentity identity() {
            return null;
        }

        /**
         * Whether this execution's process exit has already been proven.
         *
         * <p>An interrupted {@link #await()} may still have proven the exit of the launched
         * process. A caller that has to decide whether the execution still owns a resource, such
         * as runtime admission, asks this method instead of assuming that an interrupted wait
         * means the process may still be running.</p>
         *
         * <p>A backend that cannot prove exit independently of a completed wait keeps the
         * default: an interrupted wait then proves nothing.</p>
         */
        default boolean exitProven() {
            return false;
        }

        /**
         * Whether a caller may append this execution's raw output to the durable shared log.
         *
         * <p>{@code true} preserves the application-UID behaviour, where the output of a bundled
         * invocation is appended to the shared Syncthing log while the process runs. A backend
         * whose executions own their output reports {@code false}: the operation-scoped output of a
         * rooted one-shot stays with the caller that asked for it, and a rooted serve run routes
         * its output to the shared log through its own pump instead of through a caller.</p>
         */
        default boolean outputBelongsToSharedLog() {
            return true;
        }

        /** True only when the concrete launched child had exited before identity capture failed. */
        default boolean exitedBeforeIdentityCapture() {
            return false;
        }

        default ExecutionOwnershipManager.Observation observe() {
            return ExecutionOwnershipManager.Observation.UNKNOWN;
        }

        default ExecutionOwnershipManager.SignalAttempt signalIfOwned(
                ExecutionOwnershipManager.Signal signal
        ) {
            return ExecutionOwnershipManager.SignalAttempt.NOT_OWNED;
        }
    }
}
