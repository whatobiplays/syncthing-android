package com.nutomic.syncthingandroid.runtime;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * Reads the durable execution identity that the audited launch script writes before {@code exec}.
 *
 * <p>Every root run owns a spool directory, and its evidence file is written by the launch protocol
 * itself, before the bundled process replaces the shell. That evidence therefore survives an Android
 * process death that happens immediately after the launch, when the canonical record has not been
 * written yet.</p>
 *
 * <p>Only complete, schema-valid evidence counts. A missing, partial, or corrupt file is never used
 * as ownership evidence and never authorizes a signal; it only makes recovery fail closed. When more
 * than one run left valid evidence behind, the newest one is reported, because a signal still
 * requires the exact live process identity to match that evidence, and candidate discovery
 * independently blocks any ambiguity.</p>
 */
final class RootSpoolEvidence {
    private final File spoolRoot;

    RootSpoolEvidence(File spoolRoot) {
        this.spoolRoot = Objects.requireNonNull(spoolRoot);
    }

    /**
     * Returns the newest valid pre-exec evidence, or the most severe non-valid status when no
     * usable evidence exists, so partial, corrupt, unsupported, or unreadable evidence is never
     * reported as missing.
     */
    ExecutionRecordStore.ReadResult read() {
        ExecutionRecordStore.ReadResult newestResult = null;
        File newestFile = null;
        ExecutionRecordStore.ReadResult unusable = null;
        for (File directory : RootRunSpool.listRunDirectories(spoolRoot)) {
            File evidence = new File(directory, RootRunSpool.EVIDENCE_FILE);
            if (!evidence.isFile() || evidence.length() == 0) {
                // The app pre-creates an empty evidence file for every run; a run that never
                // reached its launch protocol recorded nothing.
                continue;
            }
            ExecutionRecordStore.ReadResult result =
                    RootExecutionRecordStore.readEvidenceFile(evidence);
            if (result.status() != ExecutionRecordStore.ReadResult.Status.VALID) {
                unusable = unusable == null
                        ? result
                        : ExecutionRecordStore.ReadResult.moreSevere(unusable, result);
                continue;
            }
            if (newestFile == null || isNewer(evidence, newestFile)) {
                newestFile = evidence;
                newestResult = result;
            }
        }
        if (newestResult != null) {
            return newestResult;
        }
        return unusable == null ? ExecutionRecordStore.ReadResult.missing() : unusable;
    }

    /**
     * Reads the most severe durable pre-delivery launch state found below the spool root.
     *
     * <p>State that cannot be read outranks state that could be read, because a damaged record
     * must never hide the possibility that a launch transport is still in flight. When several
     * readable pending records exist, the newest one is reported: it was recorded by the latest
     * launch, so its transport process is the one that may still become the bundled process.</p>
     */
    ExecutionRecordStore.PendingLaunch readPendingLaunch() {
        ExecutionRecordStore.PendingLaunch newest = ExecutionRecordStore.PendingLaunch.none();
        File newestFile = null;
        for (File directory : RootRunSpool.listRunDirectories(spoolRoot)) {
            File evidence = new File(directory, RootRunSpool.EVIDENCE_FILE);
            ExecutionRecordStore.PendingLaunch result = readPendingLaunch(directory);
            if (result.status() == ExecutionRecordStore.PendingLaunch.Status.UNRESOLVED) {
                return result;
            }
            if (result.status() != ExecutionRecordStore.PendingLaunch.Status.PENDING) {
                continue;
            }
            if (newestFile == null || isNewer(evidence, newestFile)) {
                newestFile = evidence;
                newest = result;
            }
        }
        return newest;
    }

    /**
     * Reads the durable pre-delivery launch state recorded by one run directory.
     *
     * <p>Callers that only need to protect one leftover run, such as spool reconciliation, use this
     * entry point because it never inspects unrelated runs.</p>
     */
    static ExecutionRecordStore.PendingLaunch readPendingLaunch(File runDirectory) {
        return RootExecutionRecordStore.readPendingEvidence(
                new File(runDirectory, RootRunSpool.EVIDENCE_FILE)
        );
    }

    /** Token-safe cleanup: removes only the evidence that names this run token. */
    boolean deleteIfRunTokenMatches(String runToken) throws IOException {
        Objects.requireNonNull(runToken);
        boolean deleted = false;
        for (File directory : RootRunSpool.listRunDirectories(spoolRoot)) {
            File evidence = new File(directory, RootRunSpool.EVIDENCE_FILE);
            ExecutionRecordStore.ReadResult result =
                    RootExecutionRecordStore.readEvidenceFile(evidence);
            if (result.status() != ExecutionRecordStore.ReadResult.Status.VALID
                    || !runToken.equals(result.identity().runToken())) {
                continue;
            }
            if (!evidence.delete()) {
                throw new IOException("Could not delete pre-execution evidence");
            }
            deleted = true;
        }
        return deleted;
    }

    private static boolean isNewer(File candidate, File reference) {
        if (candidate.lastModified() != reference.lastModified()) {
            return candidate.lastModified() > reference.lastModified();
        }
        return candidate.getPath().compareTo(reference.getPath()) > 0;
    }
}
