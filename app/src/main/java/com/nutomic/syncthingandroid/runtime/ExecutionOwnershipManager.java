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
        AMBIGUOUS_EXECUTION
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

    public enum SignalResult {
        SIGNALED,
        NOT_OWNED,
        SIGNAL_FAILED
    }

    public static final class RecoveryAssessment {
        private final Classification classification;
        private final RecordEvidence recordEvidence;
        private final CandidateEvidence candidateEvidence;
        private final ExecutionIdentity ownedExecution;

        private RecoveryAssessment(
                Classification classification,
                RecordEvidence recordEvidence,
                CandidateEvidence candidateEvidence,
                ExecutionIdentity ownedExecution
        ) {
            this.classification = classification;
            this.recordEvidence = recordEvidence;
            this.candidateEvidence = candidateEvidence;
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
                    evidence == RecordEvidence.VALID ? RecordEvidence.READ_FAILED : evidence,
                    CandidateEvidence.UNKNOWN,
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
            deleteStaleRecord(recorded);
            return candidates.isEmpty()
                    ? assessment(
                            Classification.BOOT_ID_MISMATCH, RecordEvidence.BOOT_ID_MISMATCH,
                            CandidateEvidence.NONE, null
                    )
                    : assessment(
                            Classification.AMBIGUOUS_EXECUTION, RecordEvidence.BOOT_ID_MISMATCH,
                            CandidateEvidence.UNOWNED_CANDIDATE, null
                    );
        }

        ExecutionIdentity live;
        try {
            live = inspector.inspect(recorded.pid());
        } catch (IOException | RuntimeException e) {
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION, RecordEvidence.READ_FAILED,
                    CandidateEvidence.UNKNOWN, null
            );
        }

        if (live == null) {
            deleteStaleRecord(recorded);
            return candidates.isEmpty()
                    ? assessment(
                            Classification.RECORDED_PROCESS_GONE, RecordEvidence.PROCESS_GONE,
                            CandidateEvidence.NONE, null
                    )
                    : assessment(
                            Classification.AMBIGUOUS_EXECUTION, RecordEvidence.PROCESS_GONE,
                            CandidateEvidence.UNOWNED_CANDIDATE, null
                    );
        }

        if (recorded.matches(live)) {
            boolean competingCandidate = candidates.stream()
                    .anyMatch(candidate -> !recorded.sameProcess(candidate));
            return !competingCandidate
                    ? assessment(
                            Classification.OWNED_EXECUTION, RecordEvidence.VALID,
                            candidates.isEmpty()
                                    ? CandidateEvidence.NONE
                                    : CandidateEvidence.EXACTLY_RECORDED_PROCESS,
                            recorded
                    )
                    : assessment(
                            Classification.AMBIGUOUS_EXECUTION, RecordEvidence.VALID,
                            CandidateEvidence.UNOWNED_CANDIDATE,
                            null
                    );
        }

        if (recorded.sameProcess(live)) {
            return assessment(
                    Classification.AMBIGUOUS_EXECUTION,
                    RecordEvidence.NONMATCHING,
                    CandidateEvidence.UNOWNED_CANDIDATE,
                    null
            );
        }

        boolean oldProcessGone = !recorded.sameProcess(live);
        if (oldProcessGone) deleteStaleRecord(recorded);

        return candidates.isEmpty()
                ? assessment(
                        Classification.NONMATCHING_RECORD, RecordEvidence.NONMATCHING,
                        CandidateEvidence.NONE, null
                )
                : assessment(
                        Classification.AMBIGUOUS_EXECUTION, RecordEvidence.NONMATCHING,
                        CandidateEvidence.UNOWNED_CANDIDATE, null
                );
    }

    ExecutionIdentity recordLaunchedProcess(String runToken) throws IOException {
        ExecutionIdentity launched = inspector.findLaunchedProcess(expectedExecutable, runToken);
        if (launched == null
                || !expectedExecutable.equals(launched.executablePath())
                || !runToken.equals(launched.runToken())) {
            throw new IOException("Could not identify the newly launched bundled process");
        }
        records.write(launched);
        if (verify(launched) != SignalResult.SIGNALED) {
            throw new IOException("New process identity did not verify after recording");
        }
        return launched;
    }

    SignalResult signalIfOwned(ExecutionIdentity identity, Signal signal) {
        if (identity == null) return SignalResult.NOT_OWNED;
        RecoveryAssessment assessment = recover();
        if (assessment.classification() != Classification.OWNED_EXECUTION
                || !identity.matches(assessment.ownedExecution())
                || verify(identity) != SignalResult.SIGNALED) {
            return SignalResult.NOT_OWNED;
        }
        try {
            signals.sendSignal(identity.pid(), signal.value());
            return SignalResult.SIGNALED;
        } catch (IOException | RuntimeException e) {
            return SignalResult.SIGNAL_FAILED;
        }
    }

    public Observation observe(ExecutionIdentity identity) {
        if (identity == null) return Observation.NOT_OWNED;
        try {
            String bootId = inspector.currentBootId();
            if (!identity.bootId().equals(bootId)) return Observation.EXITED;
            ExecutionIdentity live = inspector.inspect(identity.pid());
            if (live == null) return Observation.EXITED;
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

    public SignalResult verify(ExecutionIdentity identity) {
        return observe(identity) == Observation.OWNED
                ? SignalResult.SIGNALED
                : SignalResult.NOT_OWNED;
    }

    boolean clearAfterExit(ExecutionIdentity identity) throws IOException {
        if (identity == null) return false;
        String currentBootId;
        try {
            currentBootId = inspector.currentBootId();
            if (identity.bootId().equals(currentBootId)) {
                ExecutionIdentity live = inspector.inspect(identity.pid());
                if (identity.sameProcess(live)) {
                    return false;
                }
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return records.deleteIfRunTokenMatches(identity.runToken());
    }

    private void deleteStaleRecord(ExecutionIdentity identity) {
        try {
            records.deleteIfRunTokenMatches(identity.runToken());
        } catch (IOException | RuntimeException ignored) {
            // Stale evidence remains available for the next recovery attempt.
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
}
