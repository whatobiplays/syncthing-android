package com.nutomic.syncthingandroid.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Runs the sync-completion scripts of one folder without letting their result decide the fate of
 * the completion announcement.
 *
 * <p>Both belong to the same event but must not depend on each other. A script that exits with a
 * non-zero status, and a dispatch that cannot start at all, are reported to the caller and the
 * caller's remaining work still runs - in particular the announcement that the folder finished
 * syncing, which is why the rule is kept in one place instead of at every call site.</p>
 *
 * <p>The class also owns admission against an orderly teardown. A dispatch is admitted immediately
 * before it starts and released when it ends, so a teardown either waits for the dispatch it
 * admitted or prevents the dispatch from starting at all.</p>
 */
public final class FolderScriptDispatch {
    /** Runs one folder script set through the selected backend. */
    @FunctionalInterface
    public interface ScriptRunner {
        List<FolderScriptOutcome> run(ConfiguredFolderReference folder, FolderEvent event)
                throws FolderOperationException;
    }

    /** Receives one report about a dispatch that did not run every script successfully. */
    @FunctionalInterface
    public interface ReportSink {
        void report(String message);
    }

    private final FolderScriptAdmission admission;
    private final ScriptRunner runner;

    /**
     * @param admission gate that is held for the duration of one dispatch
     * @param runner    operation that runs the approved scripts of one folder
     */
    public FolderScriptDispatch(FolderScriptAdmission admission, ScriptRunner runner) {
        this.admission = Objects.requireNonNull(admission, "The admission gate is required");
        this.runner = Objects.requireNonNull(runner, "The script runner is required");
    }

    /**
     * Runs the approved scripts of one folder, unless a teardown already closed admission.
     *
     * <p>This method never raises. Every dispatch that is not a clean set of successful scripts is
     * reported through the sink, and the admission is always released again, so one failed dispatch
     * cannot block the next one or the teardown that waits for it.</p>
     *
     * @return {@code true} when a dispatch was admitted, {@code false} when the teardown already
     *     closed admission and no script was started
     */
    public boolean dispatch(
            ConfiguredFolderReference folder,
            FolderEvent event,
            ReportSink sink
    ) {
        Objects.requireNonNull(folder, "The configured folder reference is required");
        Objects.requireNonNull(event, "The folder event is required");
        Objects.requireNonNull(sink, "The report sink is required");
        if (!admission.tryAdmit()) {
            return false;
        }
        try {
            for (FolderScriptOutcome outcome : runner.run(folder, event)) {
                if (!outcome.succeeded()) {
                    sink.report(
                            "Script '" + outcome.scriptName() + "' exited with status "
                                    + outcome.exitStatus()
                    );
                }
            }
            return true;
        } catch (FolderOperationException | RuntimeException failure) {
            sink.report("The scripts could not be run: " + failure.getMessage());
            return true;
        } finally {
            admission.leave();
        }
    }
}
