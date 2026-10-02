package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class ExecutionOwnershipManagerTest {
    private static final String EXECUTABLE = "/data/app/lib/libsyncthingnative.so";
    private static final ExecutionIdentity IDENTITY =
            new ExecutionIdentity(41, 9001, "boot-a", EXECUTABLE, "run-a");

    @Test
    public void exactLiveRecordIsTheOnlyOwnedRecoveryClassification() throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(IDENTITY);
        FakeInspector inspector = new FakeInspector("boot-a");
        inspector.add(IDENTITY);

        ExecutionOwnershipManager.RecoveryAssessment result =
                manager(records, inspector, new RecordingSignals()).recover();

        assertEquals(ExecutionOwnershipManager.Classification.OWNED_EXECUTION,
                result.classification());
        assertEquals(ExecutionOwnershipManager.RecordEvidence.VALID, result.recordEvidence());
        assertEquals(ExecutionOwnershipManager.CandidateEvidence.EXACTLY_RECORDED_PROCESS,
                result.candidateEvidence());
        assertSame(IDENTITY, result.ownedExecution());
        assertFalse(result.mayLaunch());
    }

    @Test
    public void recordedOldInstallPathRemainsOwnedAndCanBeSignaled() throws Exception {
        ExecutionIdentity recorded = new ExecutionIdentity(
                43, 9003, "boot-a", "/data/app/previous/lib/libsyncthingnative.so", "run-old"
        );
        InMemoryRecordStore records = new InMemoryRecordStore(recorded);
        FakeInspector inspector = new FakeInspector("boot-a");
        inspector.add(recorded);
        RecordingSignals signals = new RecordingSignals();
        ExecutionOwnershipManager manager = manager(records, inspector, signals);

        assertFalse(EXECUTABLE.equals(recorded.executablePath()));
        ExecutionOwnershipManager.RecoveryAssessment recovery = manager.recover();

        assertEquals(ExecutionOwnershipManager.Classification.OWNED_EXECUTION,
                recovery.classification());
        assertSame(recorded, recovery.ownedExecution());
        assertEquals(ExecutionOwnershipManager.Observation.OWNED, manager.observe(recorded));
        assertEquals(ExecutionOwnershipManager.SignalAttempt.SIGNALED,
                manager.signalIfOwned(recorded, ExecutionOwnershipManager.Signal.SIGINT));
        assertEquals(Collections.singletonList(ExecutionOwnershipManager.Signal.SIGINT),
                signals.sent);
        assertSame(recorded, records.record);
    }

    @Test
    public void procDeletedSuffixPreservesRecordedOwnershipAndCanBeSignaled() throws Exception {
        String recordedPath = "/data/app/previous/lib/libsyncthingnative.so";
        ExecutionIdentity recorded = new ExecutionIdentity(
                44, 9004, "boot-a", recordedPath, "run-old"
        );
        ExecutionIdentity procfsIdentity = new ExecutionIdentity(
                recorded.pid(), recorded.processStartTimeTicks(), recorded.bootId(),
                recordedPath + " (deleted)", recorded.runToken()
        );
        InMemoryRecordStore records = new InMemoryRecordStore(recorded);
        FakeInspector inspector = new FakeInspector("boot-a");
        inspector.add(procfsIdentity);
        RecordingSignals signals = new RecordingSignals();
        ExecutionOwnershipManager manager = manager(records, inspector, signals);

        ExecutionOwnershipManager.RecoveryAssessment recovery = manager.recover();

        assertEquals(ExecutionOwnershipManager.Classification.OWNED_EXECUTION,
                recovery.classification());
        assertSame(recorded, recovery.ownedExecution());
        assertEquals(ExecutionOwnershipManager.Observation.OWNED, manager.observe(recorded));
        assertEquals(ExecutionOwnershipManager.SignalAttempt.SIGNALED,
                manager.signalIfOwned(recorded, ExecutionOwnershipManager.Signal.SIGINT));
        assertEquals(Collections.singletonList(ExecutionOwnershipManager.Signal.SIGINT),
                signals.sent);
        assertTrue(records.hasRecord());
        assertSame(recorded, records.record);
    }

    @Test
    public void missingRecordAndNoCandidateRetainEvidenceAndPermitContinuation() throws Exception {
        ExecutionOwnershipManager.RecoveryAssessment result = manager(
                new InMemoryRecordStore(null), new FakeInspector("boot-a"),
                new RecordingSignals()
        ).recover();

        assertEquals(ExecutionOwnershipManager.RecordEvidence.MISSING, result.recordEvidence());
        assertEquals(ExecutionOwnershipManager.CandidateEvidence.NONE, result.candidateEvidence());
        assertEquals(ExecutionOwnershipManager.Classification.NO_BUNDLED_CANDIDATE,
                result.classification());
        assertTrue(result.mayLaunch());
    }

    @Test
    public void missingRecordWithCandidateIsAmbiguousAndNeverAuthorizesSignal() throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(null);
        FakeInspector inspector = new FakeInspector("boot-a");
        inspector.add(IDENTITY);
        RecordingSignals signals = new RecordingSignals();
        ExecutionOwnershipManager manager = manager(records, inspector, signals);

        ExecutionOwnershipManager.RecoveryAssessment result = manager.recover();
        ExecutionOwnershipManager.SignalAttempt signal = manager.signalIfOwned(
                IDENTITY, ExecutionOwnershipManager.Signal.SIGINT
        );

        assertEquals(ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                result.classification());
        assertEquals(ExecutionOwnershipManager.RecordEvidence.MISSING, result.recordEvidence());
        assertEquals(ExecutionOwnershipManager.CandidateEvidence.UNOWNED_CANDIDATE,
                result.candidateEvidence());
        assertFalse(result.mayLaunch());
        assertNull(result.ownedExecution());
        assertEquals(ExecutionOwnershipManager.SignalAttempt.NOT_OWNED, signal);
        assertTrue(signals.sent.isEmpty());
    }

    @Test
    public void priorInstallCandidateKeepsMissingCorruptAndNonmatchingRecordsAmbiguous()
            throws Exception {
        ExecutionIdentity previousInstall = new ExecutionIdentity(
                42, 9002, "boot-a", "/data/app/previous/lib/libsyncthingnative.so", "run-old"
        );

        InMemoryRecordStore missing = new InMemoryRecordStore(null);
        assertPriorInstallCandidateBlocksLaunch(missing, previousInstall, null);

        InMemoryRecordStore corrupt = new InMemoryRecordStore(null);
        corrupt.corrupt = true;
        assertPriorInstallCandidateBlocksLaunch(corrupt, previousInstall, null);

        InMemoryRecordStore nonmatching = new InMemoryRecordStore(IDENTITY);
        ExecutionIdentity otherAtRecordedPid = new ExecutionIdentity(
                IDENTITY.pid(), IDENTITY.processStartTimeTicks(), IDENTITY.bootId(),
                "/data/app/other/lib/other.so", "run-other"
        );
        assertPriorInstallCandidateBlocksLaunch(
                nonmatching, previousInstall, otherAtRecordedPid
        );
    }

    @Test
    public void corruptEvidenceWithoutCandidateCanContinueButRemainsCorrupt() throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(null);
        records.corrupt = true;

        ExecutionOwnershipManager.RecoveryAssessment result = manager(
                records, new FakeInspector("boot-a"), new RecordingSignals()
        ).recover();

        assertEquals(ExecutionOwnershipManager.RecordEvidence.CORRUPT, result.recordEvidence());
        assertEquals(ExecutionOwnershipManager.CandidateEvidence.NONE, result.candidateEvidence());
        assertEquals(ExecutionOwnershipManager.Classification.NO_BUNDLED_CANDIDATE,
                result.classification());
        assertTrue(result.mayLaunch());
    }

    @Test
    public void unreadableRecordBlocksLaunchEvenWhenCandidateScanIsEmpty() throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(null);
        records.readFailed = true;

        ExecutionOwnershipManager.RecoveryAssessment result = manager(
                records, new FakeInspector("boot-a"), new RecordingSignals()
        ).recover();

        assertEquals(ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                result.classification());
        assertEquals(ExecutionOwnershipManager.RecordEvidence.READ_FAILED,
                result.recordEvidence());
        assertEquals(ExecutionOwnershipManager.CandidateEvidence.NONE,
                result.candidateEvidence());
        assertFalse(result.mayLaunch());
    }

    @Test
    public void corruptEvidenceWithCandidateIsAmbiguousAndCannotBeSignaled() throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(null);
        records.corrupt = true;
        FakeInspector inspector = new FakeInspector("boot-a");
        inspector.add(IDENTITY);
        RecordingSignals signals = new RecordingSignals();

        ExecutionOwnershipManager manager = manager(records, inspector, signals);
        ExecutionOwnershipManager.RecoveryAssessment result = manager.recover();

        assertEquals(ExecutionOwnershipManager.RecordEvidence.CORRUPT, result.recordEvidence());
        assertEquals(ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                result.classification());
        assertFalse(result.mayLaunch());
        assertEquals(ExecutionOwnershipManager.SignalAttempt.NOT_OWNED,
                manager.signalIfOwned(IDENTITY, ExecutionOwnershipManager.Signal.SIGINT));
        assertTrue(signals.sent.isEmpty());
    }

    @Test
    public void nonmatchingRecordAndCandidateRemainAmbiguous() throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(IDENTITY);
        FakeInspector inspector = new FakeInspector("boot-a");
        inspector.add(new ExecutionIdentity(41, 9100, "boot-a", EXECUTABLE, "run-other"));

        ExecutionOwnershipManager.RecoveryAssessment result =
                manager(records, inspector, new RecordingSignals()).recover();

        assertEquals(ExecutionOwnershipManager.RecordEvidence.NONMATCHING, result.recordEvidence());
        assertEquals(ExecutionOwnershipManager.CandidateEvidence.UNOWNED_CANDIDATE,
                result.candidateEvidence());
        assertEquals(ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                result.classification());
        assertFalse(result.mayLaunch());
    }

    @Test
    public void nonmatchingRecordWithoutBundledCandidateRetainsEvidenceAndAllowsLaunch()
            throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(IDENTITY);
        FakeInspector inspector = new FakeInspector("boot-a");
        inspector.set(IDENTITY.pid(), new ExecutionIdentity(
                IDENTITY.pid(),
                IDENTITY.processStartTimeTicks() + 1,
                "boot-a",
                "/data/app/other-native.so",
                "run-other"
        ));

        ExecutionOwnershipManager.RecoveryAssessment result =
                manager(records, inspector, new RecordingSignals()).recover();

        assertEquals(ExecutionOwnershipManager.RecordEvidence.NONMATCHING,
                result.recordEvidence());
        assertEquals(ExecutionOwnershipManager.CandidateEvidence.NONE,
                result.candidateEvidence());
        assertEquals(ExecutionOwnershipManager.Classification.NONMATCHING_RECORD,
                result.classification());
        assertTrue(result.mayLaunch());
        assertFalse(records.hasRecord());
    }

    @Test
    public void liveProcessWithDifferentRunTokenIsAmbiguous() throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(IDENTITY);
        FakeInspector inspector = new FakeInspector("boot-a");
        ExecutionIdentity tokenMismatch = new ExecutionIdentity(
                IDENTITY.pid(), IDENTITY.processStartTimeTicks(), IDENTITY.bootId(),
                IDENTITY.executablePath(), "run-other"
        );
        inspector.add(tokenMismatch);
        RecordingSignals signals = new RecordingSignals();
        ExecutionOwnershipManager manager = manager(records, inspector, signals);

        ExecutionOwnershipManager.RecoveryAssessment result = manager.recover();

        assertEquals(ExecutionOwnershipManager.RecordEvidence.NONMATCHING,
                result.recordEvidence());
        assertEquals(ExecutionOwnershipManager.CandidateEvidence.UNOWNED_CANDIDATE,
                result.candidateEvidence());
        assertEquals(ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                result.classification());
        assertEquals(ExecutionOwnershipManager.SignalAttempt.NOT_OWNED,
                manager.signalIfOwned(IDENTITY, ExecutionOwnershipManager.Signal.SIGKILL));
        assertTrue(signals.sent.isEmpty());
    }

    @Test
    public void liveProcessWithDifferentExecutableIsNotOwned() throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(IDENTITY);
        FakeInspector inspector = new FakeInspector("boot-a");
        inspector.set(IDENTITY.pid(), new ExecutionIdentity(
                IDENTITY.pid(),
                IDENTITY.processStartTimeTicks(),
                IDENTITY.bootId(),
                "/data/app/other-native.so",
                IDENTITY.runToken()
        ));
        RecordingSignals signals = new RecordingSignals();
        ExecutionOwnershipManager manager = manager(records, inspector, signals);

        assertEquals(ExecutionOwnershipManager.Observation.EXITED, manager.observe(IDENTITY));
        assertEquals(ExecutionOwnershipManager.SignalAttempt.NOT_OWNED,
                manager.signalIfOwned(IDENTITY, ExecutionOwnershipManager.Signal.SIGINT));
        assertTrue(signals.sent.isEmpty());
    }

    @Test
    public void competingCandidateMakesEvenAnExactRecordAmbiguousAndUnsignalable()
            throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(IDENTITY);
        FakeInspector inspector = new FakeInspector("boot-a");
        inspector.add(IDENTITY);
        inspector.add(new ExecutionIdentity(
                42, 9002, "boot-a", EXECUTABLE, "run-other"
        ));
        RecordingSignals signals = new RecordingSignals();
        ExecutionOwnershipManager manager = manager(records, inspector, signals);

        ExecutionOwnershipManager.RecoveryAssessment result = manager.recover();

        assertEquals(ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                result.classification());
        assertFalse(result.mayLaunch());
        assertEquals(ExecutionOwnershipManager.SignalAttempt.NOT_OWNED,
                manager.signalIfOwned(IDENTITY, ExecutionOwnershipManager.Signal.SIGINT));
        assertTrue(signals.sent.isEmpty());
    }

    @Test
    public void processGoneAndBootMismatchAreDistinctAndTokenSafelyCleaned() throws Exception {
        InMemoryRecordStore goneRecord = new InMemoryRecordStore(IDENTITY);
        ExecutionOwnershipManager.RecoveryAssessment gone = manager(
                goneRecord, new FakeInspector("boot-a"), new RecordingSignals()
        ).recover();
        assertEquals(ExecutionOwnershipManager.Classification.RECORDED_PROCESS_GONE,
                gone.classification());
        assertTrue(gone.mayLaunch());
        assertFalse(goneRecord.hasRecord());

        InMemoryRecordStore oldBootRecord = new InMemoryRecordStore(IDENTITY);
        ExecutionOwnershipManager.RecoveryAssessment oldBoot = manager(
                oldBootRecord, new FakeInspector("boot-b"), new RecordingSignals()
        ).recover();
        assertEquals(ExecutionOwnershipManager.Classification.BOOT_ID_MISMATCH,
                oldBoot.classification());
        assertEquals(ExecutionOwnershipManager.RecordEvidence.BOOT_ID_MISMATCH,
                oldBoot.recordEvidence());
        assertTrue(oldBoot.mayLaunch());
        assertFalse(oldBootRecord.hasRecord());
    }

    @Test
    public void unknownInspectionPreservesRecordAndBlocksRecoveryAndCleanup() throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(IDENTITY);
        FakeInspector inspector = new FakeInspector("boot-a");
        inspector.setUnknown(IDENTITY.pid());
        ExecutionOwnershipManager manager = manager(
                records, inspector, new RecordingSignals()
        );

        ExecutionOwnershipManager.RecoveryAssessment recovery = manager.recover();

        assertEquals(ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                recovery.classification());
        assertEquals(ExecutionOwnershipManager.RecordEvidence.VALID,
                recovery.recordEvidence());
        assertEquals(ExecutionOwnershipManager.InspectionEvidence.UNKNOWN,
                recovery.inspectionEvidence());
        assertEquals(ExecutionOwnershipManager.Observation.UNKNOWN,
                manager.observe(IDENTITY));
        assertFalse(manager.clearAfterExit(IDENTITY));
        assertTrue(records.hasRecord());
        assertFalse(recovery.mayLaunch());
    }

    @Test
    public void confirmedRecordedExitRefreshesCandidatesBeforeClassifying() throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(IDENTITY);
        FakeInspector inspector = new FakeInspector("boot-a");
        inspector.add(IDENTITY);
        inspector.set(IDENTITY.pid(), null);
        inspector.setCandidateSnapshot(0, Collections.singletonList(IDENTITY));
        inspector.setCandidateSnapshot(1, Collections.emptyList());

        ExecutionOwnershipManager.RecoveryAssessment recovery = manager(
                records, inspector, new RecordingSignals()
        ).recover();

        assertEquals(ExecutionOwnershipManager.Classification.RECORDED_PROCESS_GONE,
                recovery.classification());
        assertEquals(ExecutionOwnershipManager.CandidateEvidence.NONE,
                recovery.candidateEvidence());
        assertTrue(recovery.mayLaunch());
        assertEquals(2, inspector.candidateScanCount);
        assertFalse(records.hasRecord());
    }

    @Test
    public void pidReuseIsRejectedAndEachSignalPerformsFreshVerification() throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(IDENTITY);
        FakeInspector inspector = new FakeInspector("boot-a");
        inspector.add(IDENTITY);
        RecordingSignals signals = new RecordingSignals();
        ExecutionOwnershipManager manager = manager(records, inspector, signals);

        assertEquals(ExecutionOwnershipManager.SignalAttempt.SIGNALED,
                manager.signalIfOwned(IDENTITY, ExecutionOwnershipManager.Signal.SIGINT));
        inspector.set(IDENTITY.pid(), new ExecutionIdentity(
                IDENTITY.pid(), IDENTITY.processStartTimeTicks() + 1,
                IDENTITY.bootId(), IDENTITY.executablePath(), IDENTITY.runToken()
        ));
        assertEquals(ExecutionOwnershipManager.SignalAttempt.NOT_OWNED,
                manager.signalIfOwned(IDENTITY, ExecutionOwnershipManager.Signal.SIGKILL));
        assertEquals(Collections.singletonList(ExecutionOwnershipManager.Signal.SIGINT),
                signals.sent);
        assertEquals(3, inspector.inspectCount);
    }

    @Test
    public void oldCompletionCannotDeleteANewerRunRecord() throws Exception {
        InMemoryRecordStore records = new InMemoryRecordStore(IDENTITY);
        ExecutionOwnershipManager manager = manager(
                records, new FakeInspector("boot-a"), new RecordingSignals()
        );
        ExecutionIdentity newer = new ExecutionIdentity(
                42, 9002, "boot-a", EXECUTABLE, "run-b"
        );
        records.write(newer);

        assertFalse(manager.clearAfterExit(IDENTITY));
        assertSame(newer, records.record);
    }

    private static ExecutionOwnershipManager manager(
            ExecutionRecordStore records, FakeInspector inspector, RecordingSignals signals
    ) {
        return new ExecutionOwnershipManager(EXECUTABLE, records, inspector, signals);
    }

    private static void assertPriorInstallCandidateBlocksLaunch(
            InMemoryRecordStore records,
            ExecutionIdentity previousInstall,
            ExecutionIdentity liveAtRecordedPid
    ) throws Exception {
        FakeInspector inspector = new FakeInspector("boot-a");
        inspector.add(previousInstall);
        if (liveAtRecordedPid != null) {
            inspector.set(liveAtRecordedPid.pid(), liveAtRecordedPid);
        }
        RecordingSignals signals = new RecordingSignals();
        ExecutionOwnershipManager manager = manager(records, inspector, signals);

        ExecutionOwnershipManager.RecoveryAssessment result = manager.recover();

        assertEquals(ExecutionOwnershipManager.Classification.AMBIGUOUS_EXECUTION,
                result.classification());
        assertEquals(ExecutionOwnershipManager.CandidateEvidence.UNOWNED_CANDIDATE,
                result.candidateEvidence());
        assertFalse(result.mayLaunch());
        assertEquals(ExecutionOwnershipManager.SignalAttempt.NOT_OWNED,
                manager.signalIfOwned(IDENTITY, ExecutionOwnershipManager.Signal.SIGINT));
        assertTrue(signals.sent.isEmpty());
    }

    private static final class InMemoryRecordStore implements ExecutionRecordStore {
        private ExecutionIdentity record;
        private boolean corrupt;
        private boolean readFailed;

        private InMemoryRecordStore(ExecutionIdentity record) {
            this.record = record;
        }

        @Override
        public ReadResult read() {
            if (readFailed) return ReadResult.readFailed();
            if (corrupt) return ReadResult.corrupt();
            return record == null ? ReadResult.missing() : ReadResult.valid(record);
        }

        @Override
        public void write(ExecutionIdentity identity) {
            record = identity;
            corrupt = false;
            readFailed = false;
        }

        @Override
        public boolean deleteIfRunTokenMatches(String runToken) {
            if (record == null || !record.runToken().equals(runToken)) return false;
            record = null;
            return true;
        }

        private boolean hasRecord() {
            return record != null || corrupt;
        }
    }

    private static final class FakeInspector implements ExecutionInspector {
        private final String bootId;
        private final List<ExecutionIdentity> candidates = new ArrayList<>();
        private final List<ExecutionIdentity> byPid = new ArrayList<>();
        private final Set<Integer> unknownPids = new HashSet<>();
        private final List<List<ExecutionIdentity>> candidateSnapshots = new ArrayList<>();
        private int inspectCount;
        private int candidateScanCount;

        private FakeInspector(String bootId) {
            this.bootId = bootId;
        }

        private void add(ExecutionIdentity identity) {
            candidates.add(identity);
            set(identity.pid(), identity);
        }

        private void set(int pid, ExecutionIdentity identity) {
            while (byPid.size() <= pid) byPid.add(null);
            byPid.set(pid, identity);
            unknownPids.remove(pid);
        }

        private void setUnknown(int pid) {
            unknownPids.add(pid);
        }

        private void setCandidateSnapshot(int index, List<ExecutionIdentity> snapshot) {
            while (candidateSnapshots.size() <= index) candidateSnapshots.add(null);
            candidateSnapshots.set(index, snapshot);
        }

        @Override
        public String currentBootId() {
            return bootId;
        }

        @Override
        public InspectionResult inspect(int pid) {
            inspectCount++;
            if (unknownPids.contains(pid)) return InspectionResult.unknown();
            ExecutionIdentity identity = pid < byPid.size() ? byPid.get(pid) : null;
            return identity == null
                    ? InspectionResult.processAbsent()
                    : InspectionResult.live(identity);
        }

        @Override
        public List<ExecutionIdentity> findBundledCandidates(String executablePath) {
            int scan = candidateScanCount++;
            if (scan < candidateSnapshots.size() && candidateSnapshots.get(scan) != null) {
                return new ArrayList<>(candidateSnapshots.get(scan));
            }
            return new ArrayList<>(candidates);
        }

        @Override
        public ExecutionIdentity findLaunchedProcess(String executablePath, String runToken) {
            for (ExecutionIdentity item : candidates) {
                if (item.executablePath().equals(executablePath)
                        && item.runToken().equals(runToken)) return item;
            }
            return null;
        }
    }

    private static final class RecordingSignals implements ProcessSignalTransport {
        private final List<ExecutionOwnershipManager.Signal> sent = new ArrayList<>();

        @Override
        public ExecutionOwnershipManager.SignalResult sendSignal(int pid, int signal) {
            sent.add(ExecutionOwnershipManager.Signal.fromValue(signal));
            return ExecutionOwnershipManager.SignalResult.SIGNALED;
        }
    }
}
