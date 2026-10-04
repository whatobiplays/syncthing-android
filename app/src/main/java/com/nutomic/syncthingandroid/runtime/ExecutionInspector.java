package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.List;

/** Reads Linux process identity and discovers executable candidates without authorizing signals. */
interface ExecutionInspector {
    final class InspectionResult {
        enum Status { LIVE, PROCESS_ABSENT, UNKNOWN }

        private final Status status;
        private final ExecutionIdentity identity;

        private InspectionResult(Status status, ExecutionIdentity identity) {
            this.status = status;
            this.identity = identity;
        }

        static InspectionResult live(ExecutionIdentity identity) {
            return new InspectionResult(Status.LIVE, java.util.Objects.requireNonNull(identity));
        }

        static InspectionResult processAbsent() {
            return new InspectionResult(Status.PROCESS_ABSENT, null);
        }

        static InspectionResult unknown() {
            return new InspectionResult(Status.UNKNOWN, null);
        }

        Status status() {
            return status;
        }

        ExecutionIdentity identity() {
            return identity;
        }
    }

    String currentBootId() throws IOException;

    InspectionResult inspect(int pid) throws IOException;

    List<ExecutionIdentity> findBundledCandidates(String executablePath) throws IOException;

    ExecutionIdentity findLaunchedProcess(String executablePath, String runToken) throws IOException;
}
