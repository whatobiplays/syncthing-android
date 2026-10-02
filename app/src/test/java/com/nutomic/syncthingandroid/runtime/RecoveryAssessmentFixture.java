package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Builds recovery evidence through the same ownership manager used by runtime launches. */
public final class RecoveryAssessmentFixture {
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
}
