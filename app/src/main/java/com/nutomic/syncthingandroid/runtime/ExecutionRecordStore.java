package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;

/** Stores the single active Normal Mode process identity. */
interface ExecutionRecordStore {
    ReadResult read();

    void write(ExecutionIdentity identity) throws IOException;

    boolean deleteIfRunTokenMatches(String runToken) throws IOException;

    /**
     * Reads the durable pre-delivery state of a launch transport.
     *
     * <p>A launch records this state before its first launch byte can be delivered, so recovery in
     * a later application process can still prove that a transport may become the bundled process
     * even though neither canonical record nor pre-exec evidence exists yet.</p>
     */
    default PendingLaunch readPendingLaunch() {
        return PendingLaunch.none();
    }

    /**
     * Durable pre-delivery state of one launch transport.
     *
     * <p>The state is intentionally narrow: it either states that no pending launch exists, names
     * the kernel identity of the transport that may still become the bundled process, or reports
     * that pending state exists but cannot be read. The last case is never treated as absence,
     * because unreadable durable state cannot prove that no launch is in flight.</p>
     */
    final class PendingLaunch {
        enum Status {
            /** No launch transport recorded pre-delivery state. */
            NONE,
            /** A launch transport is recorded and may still become the bundled process. */
            PENDING,
            /** Pending launch state is present but unreadable, so recovery fails closed. */
            UNRESOLVED
        }

        private final Status status;
        private final ExecutionIdentity transportIdentity;

        private PendingLaunch(Status status, ExecutionIdentity transportIdentity) {
            this.status = status;
            this.transportIdentity = transportIdentity;
        }

        static PendingLaunch none() {
            return new PendingLaunch(Status.NONE, null);
        }

        static PendingLaunch pending(ExecutionIdentity transportIdentity) {
            if (transportIdentity == null) {
                throw new IllegalArgumentException("A pending launch needs a transport identity");
            }
            return new PendingLaunch(Status.PENDING, transportIdentity);
        }

        static PendingLaunch unresolved() {
            return new PendingLaunch(Status.UNRESOLVED, null);
        }

        Status status() {
            return status;
        }

        /** Returns the recorded transport identity; only meaningful for {@link Status#PENDING}. */
        ExecutionIdentity transportIdentity() {
            return transportIdentity;
        }
    }

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

        /**
         * Returns the more severe of two non-valid results, so evidence that exists but cannot be
         * read keeps failing recovery closed instead of degrading into a weaker status.
         *
         * <p>Severity orders {@link Status#READ_FAILED} above {@link Status#CORRUPT} above
         * {@link Status#UNSUPPORTED_VERSION} above {@link Status#MISSING}. A tie keeps the first
         * result.</p>
         */
        static ReadResult moreSevere(ReadResult first, ReadResult second) {
            return severity(second.status()) > severity(first.status()) ? second : first;
        }

        private static int severity(Status status) {
            switch (status) {
                case READ_FAILED:
                    return 4;
                case CORRUPT:
                    return 3;
                case UNSUPPORTED_VERSION:
                    return 2;
                case MISSING:
                    return 1;
                default:
                    return 0;
            }
        }

        Status status() {
            return status;
        }

        ExecutionIdentity identity() {
            return identity;
        }
    }
}
