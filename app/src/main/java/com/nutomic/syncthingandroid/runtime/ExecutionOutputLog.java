package com.nutomic.syncthingandroid.runtime;

import com.nutomic.syncthingandroid.service.SyncthingLogFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Streams the raw output of one bundled Syncthing execution while the process runs.
 *
 * <p>The durable shared Syncthing log receives the output of long-running bundled invocations.
 * Whether an execution's output belongs there is decided by
 * {@link PrivilegeBackend.Execution#outputBelongsToSharedLog()}: a rooted one-shot database reset
 * reports operation-scoped output, so the bytes are drained and discarded here instead of entering
 * the long-running log. A rooted serve run reports the same, because it routes its own output into
 * the shared log through its serve log pump, so nothing is appended twice.</p>
 *
 * <p>The class is mode-neutral: it never inspects the backend that produced the execution.</p>
 */
final class ExecutionOutputLog {
    private ExecutionOutputLog() {
    }

    /**
     * Starts the worker that drains one execution's output, saving every line to the shared log
     * when the execution's output belongs there.
     *
     * <p>The stream is always drained to its end, whether or not bytes are saved, so an execution
     * can never stall behind an unread stream. Saved output goes through
     * {@link SyncthingLogFile#append(File, byte[])} for each line instead of holding one file
     * descriptor for the lifetime of the execution. The log therefore cannot be rotated out from
     * under an active writer.</p>
     *
     * @param sharedLogFile durable log that receives the saved lines
     * @param execution execution whose output is drained
     * @param onFailure notified when the output could not be read or saved
     * @return the running worker, which finishes when the output stream ends
     */
    static Thread stream(
            File sharedLogFile,
            SyncthingExecution execution,
            SyncthingExecution.OutputFailureHandler onFailure
    ) {
        Objects.requireNonNull(sharedLogFile);
        Objects.requireNonNull(execution);
        SyncthingExecution.OutputFailureHandler failureHandler = Objects.requireNonNull(onFailure);
        boolean saveToSharedLog = execution.outputBelongsToSharedLog();
        InputStream output = execution.stdout();
        Thread worker = new Thread(
                () -> drain(sharedLogFile, output, saveToSharedLog, failureHandler)
        );
        worker.start();
        return worker;
    }

    private static void drain(
            File sharedLogFile,
            InputStream output,
            boolean saveToSharedLog,
            SyncthingExecution.OutputFailureHandler onFailure
    ) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(output, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (saveToSharedLog) {
                    SyncthingLogFile.append(
                            sharedLogFile,
                            (line + "\n").getBytes(StandardCharsets.UTF_8)
                    );
                }
            }
        } catch (IOException e) {
            // Output is best effort: failing to read or save it must not fail the invocation that
            // produced it, so the error is reported to the caller instead.
            onFailure.onFailure(e);
        }
    }
}
