package com.nutomic.syncthingandroid.runtime;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * Reconciles the output a root Syncthing run accumulated while no application process was reading.
 *
 * <p>A surviving root run keeps writing into its app-owned spool after the application dies. Once
 * recovery has proven that no bundled process is alive any more, leftover serve output is appended
 * to the shared Syncthing log and the leftover spool directory is removed. One-shot output stays
 * operation-scoped and is discarded instead of polluting the long-running log.</p>
 *
 * <p>Which runs own shared-log output is decided by the closed command vocabulary itself
 * ({@link SyncthingCommand#writesServeLog()}), so this reconciliation and the live launch path can
 * never disagree about a command's output policy. A run whose recorded command cannot be resolved
 * to that vocabulary is retained untouched.</p>
 *
 * <p>Reconciliation uses the same {@link RootServeLogWriter} as the live run, so it resumes at the
 * durably recorded consumption offset rather than replaying a whole run: output that already
 * reached the log is never appended twice, and a reconciliation that could not delete its spool
 * appends nothing on the next attempt.</p>
 *
 * <p>A leftover run that cannot be attributed to a bundled command is never removed, so neither
 * its evidence nor output is ever destroyed by a failed reconciliation.</p>
 */
final class RootRunSpoolReconciler {
    private final File spoolRoot;
    private final File logFile;
    private final File logTemporaryDirectory;

    RootRunSpoolReconciler(File spoolRoot, File logFile, File logTemporaryDirectory) {
        this.spoolRoot = Objects.requireNonNull(spoolRoot);
        this.logFile = logFile;
        this.logTemporaryDirectory = logTemporaryDirectory;
    }

    /** Returns the directory that holds the per-run spool directories. */
    File spoolRoot() {
        return spoolRoot;
    }

    /**
     * Reconciles every leftover run except the one that is currently active.
     *
     * @param activeSpool spool of the live run that must not be touched, or {@code null}
     * @return number of leftover runs removed
     */
    int reconcile(RootRunSpool activeSpool) {
        int reconciled = 0;
        RootServeLogWriter appendedWriter = null;
        for (File directory : RootRunSpool.listRunDirectories(spoolRoot)) {
            if (activeSpool != null && directory.equals(activeSpool.directory())) {
                continue;
            }
            // A run spool is only ever inspected, appended to, or deleted while
            // reconciliation holds its exclusive lease, and the lease is held until the whole
            // decision - including any deletion - has completed. A run that an in-process
            // preparation or execution still owns is skipped, because that handle is still
            // reading or writing it. The lease lives in a stable sibling file that deleting the
            // run never touches, so it is an operating-system lock that disappears with the
            // owning application process and an orphaned run stays reconcilable afterwards.
            RootRunSpool.Lease lease = RootRunSpool.tryAcquireLease(
                    RootRunSpool.leaseFileFor(spoolRoot, directory.getName())
            );
            if (lease == null) {
                continue;
            }
            try {
                if (RootSpoolEvidence.readPendingLaunch(directory).status()
                        != ExecutionRecordStore.PendingLaunch.Status.NONE) {
                    // The run still carries durable pre-delivery state, so its transport may yet
                    // become the bundled process and neither its evidence nor its spool may be
                    // removed here.
                    continue;
                }
                SyncthingCommand command =
                        SyncthingCommand.fromPersistedName(RootRunSpool.readCommandName(directory));
                if (command == null) {
                    // A run whose recorded command is missing or unknown to the closed vocabulary
                    // has no defined output policy, so its evidence and any output stay untouched.
                    continue;
                }
                if (!RootRunSpool.hasOutput(directory)) {
                    if (RootRunSpool.deleteDirectory(directory)) {
                        RootRunSpool.deleteLeaseFileWhileHeld(spoolRoot, directory.getName());
                        reconciled++;
                    }
                    continue;
                }
                if (command.writesServeLog()) {
                    RootServeLogWriter writer = RootServeLogWriter.forRunDirectory(
                            directory, logFile, logTemporaryDirectory
                    );
                    try {
                        writer.appendPendingOutput();
                    } catch (IOException e) {
                        // Output that did not reach the log keeps its spool, so nothing is lost.
                        continue;
                    }
                    appendedWriter = writer;
                }
                if (RootRunSpool.deleteDirectory(directory)) {
                    RootRunSpool.deleteLeaseFileWhileHeld(spoolRoot, directory.getName());
                    reconciled++;
                }
            } finally {
                lease.release();
            }
        }
        if (appendedWriter != null) {
            appendedWriter.trimLog();
        }
        // Lease files of runs that no longer exist are not evidence and never block recovery, so
        // they are removed here while this process can still take their lock.
        RootRunSpool.deleteOrphanLeaseFiles(spoolRoot);
        return reconciled;
    }
}
