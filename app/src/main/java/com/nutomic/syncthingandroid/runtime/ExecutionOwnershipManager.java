package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Records and verifies the durable identity of Normal Mode Syncthing executions.
 *
 * <p>Candidate discovery reports ambiguity only. The manager sends a signal only after matching the
 * durable record and live PID, process start time, boot ID, executable, and run token.</p>
 */
public final class ExecutionOwnershipManager {
    public enum Classification {
        OWNED_EXECUTION,
        RECORDED_PROCESS_GONE,
        BOOT_ID_MISMATCH,
        NONMATCHING_RECORD,
        NO_BUNDLED_CANDIDATE,
        AMBIGUOUS_EXECUTION,
        /**
         * One owned launch is between the durable pre-exec evidence write and the terminal exec
         * that replaces the root shell with the bundled binary. The live process is the recorded
         * one, so recovery must preserve its evidence, must never treat it as launchable, and must
         * never signal it: exact ownership is only proven once the process itself is the bundled
         * executable.
         */
        LAUNCH_IN_FLIGHT
    }

    public enum RecordEvidence {
        VALID,
        MISSING,
        CORRUPT,
        UNSUPPORTED_VERSION,
        READ_FAILED,
        PROCESS_GONE,
        BOOT_ID_MISMATCH,
        NONMATCHING
    }

    public enum CandidateEvidence {
        NONE,
        EXACTLY_RECORDED_PROCESS,
        UNOWNED_CANDIDATE,
        UNKNOWN
    }

    public enum InspectionEvidence {
        NOT_CHECKED,
        LIVE,
        PROCESS_ABSENT,
        UNKNOWN
    }

    public enum Signal {
        SIGINT(2),
        SIGKILL(9);

        private final int value;

        Signal(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        public static Signal fromValue(int value) {
            for (Signal signal : values()) {
                if (signal.value == value) return signal;
            }
            throw new IllegalArgumentException("Unsupported process signal: " + value);
        }
    }

    /** Result of the kernel signal transport after exact ownership was verified. */
    public enum SignalResult {
        SIGNALED,
        SIGNAL_FAILED
    }

    /** Result of an ownership-checked signal attempt, including attempts rejected before transport. */
    public enum SignalAttempt {
        NOT_OWNED,
        SIGNALED,
        SIGNAL_FAILED
    }

    public static final class RecoveryAssessment {
        private final Classification classification;
        private final RecordEvidence recordEvidence;
        private final CandidateEvidence candidateEvidence;
        private final InspectionEvidence inspectionEvidence;
        private final ExecutionIdentity ownedExecution;

        private RecoveryAssessment(
                Classification classification,
                RecordEvidence recordEvidence,
                CandidateEvidence candidateEvidence,
                ExecutionIdentity ownedExecution
        ) {
            this(classification, recordEvidence, candidateEvidence,
                    InspectionEvidence.NOT_CHECKED, ownedExecution);
        }

        private RecoveryAssessment(
                Classification classification,
                RecordEvidence recordEvidence,
                CandidateEvidence candidateEvidence,
                InspectionEvidence inspectionEvidence,
                ExecutionIdentity ownedExecution
        ) {
            this.classification = classification;
            this.recordEvidence = recordEvidence;
            this.candidateEvidence = candidateEvidence;
            this.inspectionEvidence = inspectionEvidence;
            this.ownedExecution = ownedExecution;
        }

        public Classification classification() {
            return classification;
        }

        public RecordEvidence recordEvidence() {
            return recordEvidence;
        }

        public CandidateEvidence candidateEvidence() {
            return candidateEvidence;
        }

        public InspectionEvidence inspectionEvidence() {
            return inspectionEvidence;
        }

        public ExecutionIdentity ownedExecution() {
            return ownedExecution;
        }

        public boolean mayLaunch() {
            if (candidateEvidence != CandidateEvidence.NONE) return false;
            return classification == Classification.NO_BUNDLED_CANDIDATE
                    || classification == Classification.RECORDED_PROCESS_GONE
                    || classification == Classification.BOOT_ID_MISMATCH
                    || classification == Classification.NONMATCHING_RECORD;
        }

        public static RecoveryAssessment noCandidate() {
            return new RecoveryAssessment(
                    Classification.NO_BUNDLED_CANDIDATE,
                    RecordEvidence.MISSING,
                    CandidateEvidence.NONE,
                    null
            );
        }
    }

    public enum Observation { OWNED, EXITED, NOT_OWNED, UNKNOWN }

    private final String expectedExecutable;
    private final ExecutionRecordStore records;
    private final ExecutionInspector inspector;
    private final ProcessSignalTransport signals;

    ExecutionOwnershipManager(
            String expectedExecutable,
            ExecutionRecordStore records,
            ExecutionInspector inspector,
            ProcessSignalTransport signals
    ) {
        this.expectedExecutable = Objects.requireNonNull(expectedExecutable);
        this.records = Objects.requireNonNull(records);
        this.inspector = Objects.requireNonNull(inspector);
        this.signals = Objects.requireNonNull(signals);
    }

    RecoveryAssessment recover() {
        ExecutionRecordStore.ReadResult stored = records.read();
        RecordEvidence evidence = recordEvidence(stored.status());
        List<ExecutionIdentity> candidates;
        String currentBootId;
        try {
            candidates = inspector.findBundledCandidates(expectedExecutable);
            currentBootId = inspector.currentBootId();
        } catch (IOException | RuntimeException e) {
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION,
                    evidence,
                    CandidateEvidence.UNKNOWN,
                    null
            );
        }

        ExecutionRecordStore.PendingLaunch pending = records.readPendingLaunch();
        if (pending.status() == ExecutionRecordStore.PendingLaunch.Status.UNRESOLVED) {
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION,
                    RecordEvidence.CORRUPT,
                    candidates.isEmpty()
                            ? CandidateEvidence.NONE
                            : CandidateEvidence.UNOWNED_CANDIDATE,
                    null
            );
        }
        if (pending.status() == ExecutionRecordStore.PendingLaunch.Status.PENDING) {
            RecoveryAssessment inFlight =
                    classifyPendingLaunch(pending.transportIdentity(), currentBootId, candidates);
            if (inFlight != null) {
                return inFlight;
            }
            // The recorded transport is proven gone, so its state was cleared token-safely and the
            // stale candidate snapshot was refreshed before any launchable assessment. Read the
            // durable store again: the ordinary classification below must describe what actually
            // remains instead of the pre-delivery state this call just removed.
            stored = records.read();
            evidence = recordEvidence(stored.status());
        }

        if (stored.status() == ExecutionRecordStore.ReadResult.Status.READ_FAILED) {
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION, RecordEvidence.READ_FAILED,
                    candidates.isEmpty()
                            ? CandidateEvidence.NONE
                            : CandidateEvidence.UNOWNED_CANDIDATE,
                    null
            );
        }

        if (stored.status() != ExecutionRecordStore.ReadResult.Status.VALID) {
            return candidates.isEmpty()
                    ? assessment(
                            Classification.NO_BUNDLED_CANDIDATE, evidence,
                            CandidateEvidence.NONE, null
                    )
                    : assessment(
                            Classification.AMBIGUOUS_EXECUTION, evidence,
                            CandidateEvidence.UNOWNED_CANDIDATE, null
                    );
        }

        ExecutionIdentity recorded = stored.identity();
        if (!recorded.bootId().equals(currentBootId)) {
            return classifyAfterClearedRecord(
                    recorded,
                    Classification.BOOT_ID_MISMATCH,
                    RecordEvidence.BOOT_ID_MISMATCH,
                    InspectionEvidence.NOT_CHECKED
            );
        }

        ExecutionInspector.InspectionResult inspection;
        try {
            inspection = inspector.inspect(recorded.pid());
        } catch (IOException | RuntimeException e) {
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION, evidence,
                    CandidateEvidence.UNKNOWN, null, InspectionEvidence.UNKNOWN
            );
        }

        if (inspection == null
                || inspection.status() == ExecutionInspector.InspectionResult.Status.UNKNOWN) {
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION, evidence,
                    CandidateEvidence.UNKNOWN, null, InspectionEvidence.UNKNOWN
            );
        }

        if (inspection.status()
                == ExecutionInspector.InspectionResult.Status.PROCESS_ABSENT) {
            return classifyAfterClearedRecord(
                    recorded,
                    Classification.RECORDED_PROCESS_GONE,
                    RecordEvidence.PROCESS_GONE,
                    InspectionEvidence.PROCESS_ABSENT
            );
        }

        ExecutionIdentity live = inspection.identity();

        if (recorded.matches(live)) {
            boolean competingCandidate = false;
            for (ExecutionIdentity candidate : candidates) {
                if (!recorded.sameProcess(candidate)) {
                    competingCandidate = true;
                    break;
                }
            }
            return !competingCandidate
                    ? assessment(
                            Classification.OWNED_EXECUTION, RecordEvidence.VALID,
                            candidates.isEmpty()
                                    ? CandidateEvidence.NONE
                                    : CandidateEvidence.EXACTLY_RECORDED_PROCESS,
                            recorded, InspectionEvidence.LIVE
                    )
                    : assessment(
                            Classification.AMBIGUOUS_EXECUTION, RecordEvidence.VALID,
                            CandidateEvidence.UNOWNED_CANDIDATE,
                            null, InspectionEvidence.LIVE
                    );
        }

        if (recorded.sameProcess(live)) {
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION,
                    RecordEvidence.NONMATCHING,
                    CandidateEvidence.UNOWNED_CANDIDATE,
                    null, InspectionEvidence.LIVE
            );
        }

        if (recorded.sameKernelProcess(live)) {
            // Same kernel process, different executable: the launch script has written its
            // durable pre-exec evidence and has not executed the terminal exec yet, because the
            // evidence names the expected bundled binary while the process still runs something
            // else. This is an owned launch in flight, so its evidence stays and no classifier
            // may treat it as launchable or signal it.
            return ExecutionIdentity.sameExecutableTarget(
                    expectedExecutable, recorded.executablePath())
                    ? assessment(
                            Classification.LAUNCH_IN_FLIGHT,
                            RecordEvidence.VALID,
                            CandidateEvidence.UNOWNED_CANDIDATE,
                            null, InspectionEvidence.LIVE
                    )
                    : assessment(
                            Classification.AMBIGUOUS_EXECUTION,
                            RecordEvidence.NONMATCHING,
                            CandidateEvidence.UNOWNED_CANDIDATE,
                            null, InspectionEvidence.LIVE
                    );
        }

        return classifyAfterClearedRecord(
                recorded,
                Classification.NONMATCHING_RECORD,
                RecordEvidence.NONMATCHING,
                InspectionEvidence.LIVE
        );
    }

    /**
     * Classifies the durable pre-delivery state of one launch transport.
     *
     * <p>A live process that still carries the recorded kernel identity keeps the launch
     * non-launchable: the transport may still replace itself with the bundled binary. When that
     * process already reached the expected bundled executable the launch is an exactly owned
     * execution, and when the identity no longer names a live process the recorded state is stale
     * and is cleared with the ordinary run-token-safe cleanup. A recovery may only become
     * launchable once that cleanup proved the obsolete state removed, because surviving evidence
     * would keep overriding a replacement run's own pre-exec evidence; every other cleanup
     * outcome fails closed. Clearing the state refreshes bundled candidate discovery before
     * recovery may report a launchable absence.</p>
     *
     * @return an assessment that must block the launch, or {@code null} when the pending state
     *     was cleared, no bundled candidate exists, and the ordinary classification must continue
     */
    private RecoveryAssessment classifyPendingLaunch(
            ExecutionIdentity transport,
            String currentBootId,
            List<ExecutionIdentity> candidates
    ) {
        if (!transport.bootId().equals(currentBootId)) {
            // A launch transport cannot survive a reboot, so this state belongs to a finished boot.
            return classifyAfterClearedPendingLaunch(
                    transport,
                    RecordEvidence.BOOT_ID_MISMATCH,
                    InspectionEvidence.NOT_CHECKED
            );
        }
        ExecutionInspector.InspectionResult inspection;
        try {
            inspection = inspector.inspect(transport.pid());
        } catch (IOException | RuntimeException e) {
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION, RecordEvidence.VALID,
                    CandidateEvidence.UNKNOWN, null, InspectionEvidence.UNKNOWN
            );
        }
        if (inspection == null
                || inspection.status() == ExecutionInspector.InspectionResult.Status.UNKNOWN) {
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION, RecordEvidence.VALID,
                    CandidateEvidence.UNKNOWN, null, InspectionEvidence.UNKNOWN
            );
        }
        if (inspection.status() == ExecutionInspector.InspectionResult.Status.PROCESS_ABSENT) {
            return classifyAfterClearedPendingLaunch(
                    transport,
                    RecordEvidence.PROCESS_GONE,
                    InspectionEvidence.PROCESS_ABSENT
            );
        }
        ExecutionIdentity live = inspection.identity();
        if (transport.matches(live)) {
            for (ExecutionIdentity candidate : candidates) {
                if (!transport.sameProcess(candidate)) {
                    return assessment(
                            Classification.AMBIGUOUS_EXECUTION, RecordEvidence.VALID,
                            CandidateEvidence.UNOWNED_CANDIDATE, null, InspectionEvidence.LIVE
                    );
                }
            }
            return assessment(
                    Classification.OWNED_EXECUTION, RecordEvidence.VALID,
                    candidates.isEmpty()
                            ? CandidateEvidence.NONE
                            : CandidateEvidence.EXACTLY_RECORDED_PROCESS,
                    transport, InspectionEvidence.LIVE
            );
        }
        if (transport.sameProcess(live)) {
            // The same kernel process now runs the expected bundled executable, but its run token
            // is missing, unreadable, or different. Exact ownership needs the token as well, so a
            // pre-delivery transport in this state is never reported as an owned execution and
            // never authorizes a signal.
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION, RecordEvidence.NONMATCHING,
                    CandidateEvidence.UNOWNED_CANDIDATE, null, InspectionEvidence.LIVE
            );
        }
        if (transport.sameKernelProcess(live)) {
            return ExecutionIdentity.sameExecutableTarget(
                    expectedExecutable, transport.executablePath())
                    ? assessment(
                            Classification.LAUNCH_IN_FLIGHT, RecordEvidence.VALID,
                            CandidateEvidence.UNOWNED_CANDIDATE, null, InspectionEvidence.LIVE
                    )
                    : assessment(
                            Classification.AMBIGUOUS_EXECUTION, RecordEvidence.NONMATCHING,
                            CandidateEvidence.UNOWNED_CANDIDATE, null, InspectionEvidence.LIVE
                    );
        }
        // The recorded process identifier now belongs to an unrelated process.
        return classifyAfterClearedPendingLaunch(
                transport,
                RecordEvidence.NONMATCHING,
                InspectionEvidence.LIVE
        );
    }

    /**
     * Classifies one pending launch whose transport is proven gone, after clearing its durable
     * state.
     *
     * <p>When the matching pre-delivery evidence cannot be proven removed, recovery fails closed
     * for this attempt: the surviving state could keep every later launch confirmation unresolved,
     * so permitting a replacement could leave a live root process without its execution handle.
     * Nothing is signaled, no replacement may start, and the evidence stays for the next
     * attempt.</p>
     */
    private RecoveryAssessment classifyAfterClearedPendingLaunch(
            ExecutionIdentity transport,
            RecordEvidence recordEvidence,
            InspectionEvidence inspectionEvidence
    ) {
        if (!deletePendingLaunch(transport)) {
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION,
                    recordEvidence,
                    CandidateEvidence.UNKNOWN,
                    null,
                    inspectionEvidence
            );
        }
        return blockOnRefreshedCandidatesAfterCleanup(recordEvidence, inspectionEvidence);
    }

    /**
     * Clears only the durable state that names the run token of one obsolete transport.
     *
     * @return whether the matching durable evidence was proven removed
     */
    private boolean deletePendingLaunch(ExecutionIdentity transport) {
        try {
            return records.deleteIfRunTokenMatches(transport.runToken());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Classifies one stale record whose process is proven gone, after clearing its durable
     * evidence.
     *
     * <p>A classification may only become launchable once the obsolete evidence has been proven
     * removed: while it survives it keeps taking precedence over any new run's pre-exec evidence,
     * so a replacement launch could never confirm its own identity. A cleanup that throws, or
     * that cannot prove the removal, therefore fails closed for this attempt and keeps the
     * evidence for the next one.</p>
     */
    private RecoveryAssessment classifyAfterClearedRecord(
            ExecutionIdentity recorded,
            Classification noCandidateClassification,
            RecordEvidence recordEvidence,
            InspectionEvidence inspectionEvidence
    ) {
        if (!deleteStaleRecord(recorded)) {
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION,
                    recordEvidence,
                    CandidateEvidence.UNKNOWN,
                    null,
                    inspectionEvidence
            );
        }
        return classifyAfterRecordedExit(
                noCandidateClassification, recordEvidence, inspectionEvidence);
    }

    private RecoveryAssessment classifyAfterRecordedExit(
            Classification noCandidateClassification,
            RecordEvidence recordEvidence,
            InspectionEvidence inspectionEvidence
    ) {
        RecoveryAssessment blocked =
                blockOnRefreshedCandidatesAfterCleanup(recordEvidence, inspectionEvidence);
        return blocked != null
                ? blocked
                : assessment(
                        noCandidateClassification, recordEvidence,
                        CandidateEvidence.NONE, null, inspectionEvidence
                );
    }

    /**
     * Takes a fresh bundled candidate snapshot after durable process evidence was cleared.
     *
     * <p>Clearing a durable record or a pre-delivery state removes the evidence the previous
     * classification was based on, so a candidate snapshot taken before the cleanup can no longer
     * decide whether a bundled process exists. Recovery rescans before it may report any launchable
     * absence, and a failed scan stays fail-closed with unknown candidate evidence. No signal is
     * ever authorized from this refresh.</p>
     *
     * @return a fail-closed assessment when a bundled candidate exists or the refresh fails, or
     *     {@code null} when the refresh proved that no bundled candidate exists
     */
    private RecoveryAssessment blockOnRefreshedCandidatesAfterCleanup(
            RecordEvidence recordEvidence,
            InspectionEvidence inspectionEvidence
    ) {
        List<ExecutionIdentity> refreshed;
        try {
            refreshed = inspector.findBundledCandidates(expectedExecutable);
        } catch (IOException | RuntimeException e) {
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION, recordEvidence,
                    CandidateEvidence.UNKNOWN, null, inspectionEvidence
            );
        }
        return refreshed.isEmpty()
                ? null
                : assessment(
                        Classification.AMBIGUOUS_EXECUTION, recordEvidence,
                        CandidateEvidence.UNOWNED_CANDIDATE, null, inspectionEvidence
                );
    }

    ExecutionIdentity recordLaunchedProcess(String runToken) throws IOException {
        ExecutionIdentity launched = inspector.findLaunchedProcess(expectedExecutable, runToken);
        if (launched == null
                || !ExecutionIdentity.sameExecutableTarget(
                        expectedExecutable, launched.executablePath()
                )
                || !runToken.equals(launched.runToken())) {
            throw new ExecutionVerificationFailedException(
                    "Could not identify the newly launched bundled process"
            );
        }
        records.write(launched);
        if (!verify(launched)) {
            throw new ExecutionVerificationFailedException(
                    "New process identity did not verify after recording"
            );
        }
        return launched;
    }

    /**
     * Finds the live bundled process that carries one run token inside this manager's session.
     *
     * <p>A launch whose durable record could not be written is identified this way before it may
     * be signaled. The returned identity is an inspection result, not an authorization: signaling
     * still requires {@link #signalIfOwned} to re-verify it.</p>
     */
    ExecutionIdentity findLaunchedProcess(String runToken) throws IOException {
        return inspector.findLaunchedProcess(expectedExecutable, runToken);
    }

    /** One launch's creation boundary as far as durable evidence and the live process prove it. */
    public static final class LaunchConfirmation {
        public enum State {
            /** The bundled process is live and exactly matches the durable evidence. */
            OWNED,
            /** Evidence is durable and the terminal exec has not replaced the shell yet. */
            HANDOFF_IN_FLIGHT,
            /** Evidence is durable and the recorded process is proven to be gone. */
            PROCESS_GONE,
            /** No recognized state could be proven from the current evidence and process table. */
            UNRESOLVED
        }

        private final State state;
        private final ExecutionIdentity identity;

        private LaunchConfirmation(State state, ExecutionIdentity identity) {
            this.state = state;
            this.identity = identity;
        }

        public State state() {
            return state;
        }

        /** Verified durable identity of the launched process, present exactly for OWNED. */
        public ExecutionIdentity identity() {
            return identity;
        }

        /**
         * Whether the creation boundary is proven, so every concurrent recovery classifier now
         * fails closed instead of treating the launch as absent.
         */
        public boolean recognized() {
            return state != State.UNRESOLVED;
        }
    }

    /**
     * Classifies one launch's creation boundary without acquiring any new root capability.
     *
     * <p>The classification only reads durable evidence and the live process table through the
     * session that provides this manager. When the launch has reached exact ownership, its
     * canonical durable record is written before this method returns, so recovery sees an owned
     * execution as soon as the process-start reservation is released.</p>
     *
     * @param runToken run token the launch script was encoded with
     * @return the recognized state, or UNRESOLVED while the launch has not proven itself yet
     */
    LaunchConfirmation confirmLaunch(String runToken) {
        Objects.requireNonNull(runToken);
        ExecutionRecordStore.ReadResult stored = records.read();
        if (stored.status() != ExecutionRecordStore.ReadResult.Status.VALID) {
            // The launch script has not written its complete evidence yet.
            return unresolved();
        }
        ExecutionIdentity recorded = stored.identity();
        if (!runToken.equals(recorded.runToken())
                || !ExecutionIdentity.sameExecutableTarget(
                        expectedExecutable, recorded.executablePath())) {
            // This evidence belongs to another launch, so this one has not reached its handoff.
            return unresolved();
        }
        ExecutionInspector.InspectionResult inspection;
        try {
            inspection = inspector.inspect(recorded.pid());
        } catch (IOException | RuntimeException e) {
            return unresolved();
        }
        if (inspection == null
                || inspection.status()
                == ExecutionInspector.InspectionResult.Status.UNKNOWN) {
            return unresolved();
        }
        if (inspection.status()
                == ExecutionInspector.InspectionResult.Status.PROCESS_ABSENT) {
            return new LaunchConfirmation(LaunchConfirmation.State.PROCESS_GONE, null);
        }
        ExecutionIdentity live = inspection.identity();
        if (recorded.matches(live)) {
            // Exact ownership is proven by the field comparison; make it durable before the
            // caller may release the process-start reservation.
            try {
                records.write(live);
            } catch (IOException e) {
                return unresolved();
            }
            return new LaunchConfirmation(LaunchConfirmation.State.OWNED, live);
        }
        return recorded.sameKernelProcess(live)
                ? new LaunchConfirmation(LaunchConfirmation.State.HANDOFF_IN_FLIGHT, null)
                : unresolved();
    }

    /** Reports a launch whose creation boundary is not proven by the current evidence. */
    static LaunchConfirmation unresolved() {
        return new LaunchConfirmation(LaunchConfirmation.State.UNRESOLVED, null);
    }

    SignalAttempt signalIfOwned(ExecutionIdentity identity, Signal signal) {
        if (identity == null) return SignalAttempt.NOT_OWNED;
        RecoveryAssessment assessment = recover();
        if (assessment.classification() != Classification.OWNED_EXECUTION
                || !identity.matches(assessment.ownedExecution())
                || !verify(identity)) {
            return SignalAttempt.NOT_OWNED;
        }
        try {
            return signals.sendSignal(identity.pid(), signal.value())
                    == SignalResult.SIGNALED
                    ? SignalAttempt.SIGNALED
                    : SignalAttempt.SIGNAL_FAILED;
        } catch (IOException | RuntimeException e) {
            return SignalAttempt.SIGNAL_FAILED;
        }
    }

    public Observation observe(ExecutionIdentity identity) {
        if (identity == null) return Observation.NOT_OWNED;
        try {
            String bootId = inspector.currentBootId();
            if (!identity.bootId().equals(bootId)) return Observation.EXITED;
            ExecutionInspector.InspectionResult inspection = inspector.inspect(identity.pid());
            if (inspection == null
                    || inspection.status()
                    == ExecutionInspector.InspectionResult.Status.UNKNOWN) {
                return Observation.UNKNOWN;
            }
            if (inspection.status()
                    == ExecutionInspector.InspectionResult.Status.PROCESS_ABSENT) {
                return Observation.EXITED;
            }
            ExecutionIdentity live = inspection.identity();
            if (!identity.sameProcess(live)) {
                return Observation.EXITED;
            }
            if (!identity.matches(live)) return Observation.NOT_OWNED;

            ExecutionRecordStore.ReadResult stored = records.read();
            return stored.status() == ExecutionRecordStore.ReadResult.Status.VALID
                    && identity.matches(stored.identity())
                    ? Observation.OWNED
                    : Observation.NOT_OWNED;
        } catch (IOException | RuntimeException e) {
            return Observation.UNKNOWN;
        }
    }

    public boolean verify(ExecutionIdentity identity) {
        return observe(identity) == Observation.OWNED;
    }

    boolean clearAfterExit(ExecutionIdentity identity) throws IOException {
        if (identity == null) return false;
        String currentBootId;
        try {
            currentBootId = inspector.currentBootId();
            if (identity.bootId().equals(currentBootId)) {
                ExecutionInspector.InspectionResult inspection =
                        inspector.inspect(identity.pid());
                if (inspection == null
                        || inspection.status()
                        == ExecutionInspector.InspectionResult.Status.UNKNOWN) {
                    return false;
                }
                if (inspection.status()
                        == ExecutionInspector.InspectionResult.Status.LIVE
                        && identity.sameProcess(inspection.identity())) {
                    return false;
                }
            } else {
                // A different kernel boot proves that the recorded process cannot still exist.
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return records.deleteIfRunTokenMatches(identity.runToken());
    }

    /**
     * Clears the durable record of one stale execution.
     *
     * @return whether the matching durable evidence was proven removed
     */
    private boolean deleteStaleRecord(ExecutionIdentity identity) {
        try {
            return records.deleteIfRunTokenMatches(identity.runToken());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static RecordEvidence recordEvidence(ExecutionRecordStore.ReadResult.Status status) {
        switch (status) {
            case VALID:
                return RecordEvidence.VALID;
            case MISSING:
                return RecordEvidence.MISSING;
            case CORRUPT:
                return RecordEvidence.CORRUPT;
            case UNSUPPORTED_VERSION:
                return RecordEvidence.UNSUPPORTED_VERSION;
            case READ_FAILED:
            default:
                return RecordEvidence.READ_FAILED;
        }
    }

    private static RecoveryAssessment assessment(
            Classification classification,
            RecordEvidence recordEvidence,
            CandidateEvidence candidateEvidence,
            ExecutionIdentity ownedExecution
    ) {
        return new RecoveryAssessment(
                classification, recordEvidence, candidateEvidence, ownedExecution
        );
    }

    private static RecoveryAssessment assessment(
            Classification classification,
            RecordEvidence recordEvidence,
            CandidateEvidence candidateEvidence,
            ExecutionIdentity ownedExecution,
            InspectionEvidence inspectionEvidence
    ) {
        return new RecoveryAssessment(
                classification, recordEvidence, candidateEvidence,
                inspectionEvidence, ownedExecution
        );
    }
}
