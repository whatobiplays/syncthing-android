package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Builds recovery evidence through the same ownership manager used by runtime launches. */
public final class RecoveryAssessmentFixture {
    private static final ExecutionIdentity OWNED_IDENTITY = new ExecutionIdentity(
            42, 9002, "boot-a", "/data/app/lib/libsyncthingnative.so", "owned-run"
    );

    private RecoveryAssessmentFixture() { }

    public static ExecutionOwnershipManager.RecoveryAssessment ambiguousMissingRecord() {
        String executable = "/data/app/lib/libsyncthingnative.so";
        ExecutionIdentity candidate = new ExecutionIdentity(41, 9001, "boot-a", executable, "other");
        ExecutionRecordStore records = new ExecutionRecordStore() {
            @Override
            public ReadResult read() {
                return ReadResult.missing();
            }

            @Override
            public void write(ExecutionIdentity identity) throws IOException {
                throw new AssertionError("Recovery must not write a record");
            }

            @Override
            public boolean deleteIfRunTokenMatches(String runToken) {
                throw new AssertionError("Recovery must not delete a missing record");
            }
        };
        ExecutionInspector inspector = new ExecutionInspector() {
            @Override
            public String currentBootId() {
                return "boot-a";
            }

            @Override
            public InspectionResult inspect(int pid) {
                throw new AssertionError("Missing record has no PID to inspect");
            }

            @Override
            public List<ExecutionIdentity> findBundledCandidates(String path) {
                return Collections.singletonList(candidate);
            }

            @Override
            public ExecutionIdentity findLaunchedProcess(String path, String token) {
                throw new AssertionError("Recovery must not discover a new launch");
            }
        };
        ProcessSignalTransport signals = (identity, signal) -> {
            throw new AssertionError("Candidate discovery must not authorize signals");
        };
        return new ExecutionOwnershipManager(executable, records, inspector, signals).recover();
    }

    public static ExecutionIdentity ownedIdentity() {
        return OWNED_IDENTITY;
    }

    public static ExecutionOwnershipManager.RecoveryAssessment ownedExecution() {
        ExecutionRecordStore records = new ExecutionRecordStore() {
            @Override
            public ReadResult read() {
                return ReadResult.valid(OWNED_IDENTITY);
            }

            @Override
            public void write(ExecutionIdentity identity) {
                throw new AssertionError("Recovery must not write an existing record");
            }

            @Override
            public boolean deleteIfRunTokenMatches(String runToken) {
                throw new AssertionError("A live execution record must not be deleted");
            }
        };
        ExecutionInspector inspector = new ExecutionInspector() {
            @Override
            public String currentBootId() {
                return OWNED_IDENTITY.bootId();
            }

            @Override
            public InspectionResult inspect(int pid) {
                return pid == OWNED_IDENTITY.pid()
                        ? InspectionResult.live(OWNED_IDENTITY)
                        : InspectionResult.processAbsent();
            }

            @Override
            public List<ExecutionIdentity> findBundledCandidates(String executablePath) {
                return Collections.singletonList(OWNED_IDENTITY);
            }

            @Override
            public ExecutionIdentity findLaunchedProcess(String executablePath, String runToken) {
                throw new AssertionError("Recovery must not perform launch identity discovery");
            }
        };
        ProcessSignalTransport signals = (identity, signal) -> {
            throw new AssertionError("The runtime recovery callback owns shutdown");
        };
        return new ExecutionOwnershipManager(
                OWNED_IDENTITY.executablePath(), records, inspector, signals
        ).recover();
    }
}
