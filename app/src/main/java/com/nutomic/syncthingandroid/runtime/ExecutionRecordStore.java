package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;

/** Stores the single active Normal Mode process identity. */
interface ExecutionRecordStore {
    ReadResult read();

    void write(ExecutionIdentity identity) throws IOException;

    boolean deleteIfRunTokenMatches(String runToken) throws IOException;

    final class ReadResult {
        enum Status {
            MISSING,
            VALID,
            CORRUPT,
            UNSUPPORTED_VERSION,
            READ_FAILED
        }

        private final Status status;
        private final ExecutionIdentity identity;

        private ReadResult(Status status, ExecutionIdentity identity) {
            this.status = status;
            this.identity = identity;
        }

        static ReadResult missing() {
            return new ReadResult(Status.MISSING, null);
        }

        static ReadResult valid(ExecutionIdentity identity) {
            return new ReadResult(Status.VALID, identity);
        }

        static ReadResult corrupt() {
            return new ReadResult(Status.CORRUPT, null);
        }

        static ReadResult unsupportedVersion() {
            return new ReadResult(Status.UNSUPPORTED_VERSION, null);
        }

        static ReadResult readFailed() {
            return new ReadResult(Status.READ_FAILED, null);
        }

        Status status() {
            return status;
        }

        ExecutionIdentity identity() {
            return identity;
        }
    }
}
