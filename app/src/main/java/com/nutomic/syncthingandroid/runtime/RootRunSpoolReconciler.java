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
 * <p>Reconciliation uses the same {@link RootServeLogWriter} as the live run, so it resumes at the
 * durably recorded consumption offset rather than replaying a whole run: output that already
 * reached the log is never appended twice, and a reconciliation that could not delete its spool
 * appends nothing on the next attempt.</p>
 *
 * <p>A leftover run that cannot be attributed to a bundled command is never removed while it still
 * holds output, so evidence is never destroyed by a failed reconciliation.</p>
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
            if (RootSpoolEvidence.readPendingLaunch(directory).status()
                    != ExecutionRecordStore.PendingLaunch.Status.NONE) {
                // The run still carries durable pre-delivery state, so its transport may yet
                // become the bundled process and neither its evidence nor its spool may be
                // removed here.
                continue;
            }
            String commandName = RootRunSpool.readCommandName(directory);
            if (commandName == null) {
                // A run that cannot be attributed to a bundled command keeps its evidence.
                continue;
            }
            if (!RootRunSpool.hasOutput(directory)) {
                RootRunSpool.deleteDirectory(directory);
                reconciled++;
                continue;
            }
            if (SyncthingCommand.SERVE.name().equals(commandName)) {
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
                reconciled++;
            }
        }
        if (appendedWriter != null) {
            appendedWriter.trimLog();
        }
        return reconciled;
    }
}
