package com.nutomic.syncthingandroid.runtime;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * Durable identity evidence for root executions.
 *
 * <p>Two sources describe the same execution. The canonical record is written by the app after it
 * has verified a launch, and the pre-exec evidence is written by the audited launch protocol before
 * the bundled process is created. Recovery must trust either one, because the app can die in the
 * window between the two, and an execution that survived app death has to stay recoverable as an
 * exact owned execution instead of degrading into ambiguity.</p>
 *
 * <p>Precedence: a valid canonical record wins, because it is the record the app verified. When the
 * canonical record is missing, the newest valid pre-exec evidence is used. When neither is valid,
 * the more severe failure is reported so that partial or corrupt evidence still fails closed.</p>
 */
final class RootEvidenceStore implements ExecutionRecordStore {
    private final RootExecutionRecordStore canonical;
    private final RootSpoolEvidence preExec;

    RootEvidenceStore(File canonicalRecordFile, File spoolRoot) {
        this.canonical = new RootExecutionRecordStore(
                Objects.requireNonNull(canonicalRecordFile)
        );
        this.preExec = new RootSpoolEvidence(Objects.requireNonNull(spoolRoot));
    }

    @Override
    public ReadResult read() {
        ReadResult stored = canonical.read();
        if (stored.status() == ReadResult.Status.VALID) {
            return stored;
        }
        ReadResult launched = preExec.read();
        if (launched.status() == ReadResult.Status.VALID) {
            return launched;
        }
        return ReadResult.moreSevere(stored, launched);
    }

    @Override
    public void write(ExecutionIdentity identity) throws IOException {
        canonical.write(identity);
    }

    /**
     * Reports durable pre-delivery launch state from the run spool.
     *
     * <p>Only the spool can carry that state: the canonical record is written after a launch was
     * verified, and the pre-exec evidence is written by the launch script itself. Both therefore
     * prove that delivery already happened.</p>
     */
    @Override
    public PendingLaunch readPendingLaunch() {
        return preExec.readPendingLaunch();
    }

    @Override
    public boolean deleteIfRunTokenMatches(String runToken) throws IOException {
        boolean canonicalDeleted = canonical.deleteIfRunTokenMatches(runToken);
        boolean preExecDeleted = preExec.deleteIfRunTokenMatches(runToken);
        return canonicalDeleted || preExecDeleted;
    }
}
