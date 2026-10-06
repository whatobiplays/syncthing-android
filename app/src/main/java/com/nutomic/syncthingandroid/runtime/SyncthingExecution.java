package com.nutomic.syncthingandroid.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mode-neutral handle for one admitted bundled Syncthing invocation.
 *
 * <p>A completed wait releases runtime admission. Destruction requests a SIGKILL only when the
 * handle's durable identity still verifies exactly.</p>
 */
public final class SyncthingExecution {
    /** Receives the I/O failures reported while one execution's output is streamed. */
    @FunctionalInterface
    public interface OutputFailureHandler {
        void onFailure(IOException error);
    }

    private final PrivilegeBackend.Execution execution;
    private final Runnable releaseAdmission;
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final AtomicBoolean exitObserved = new AtomicBoolean(false);

    SyncthingExecution(PrivilegeBackend.Execution execution, Runnable releaseAdmission) {
        this.execution = execution;
        this.releaseAdmission = releaseAdmission;
    }

    public InputStream stdout() {
        return execution.stdout();
    }

    /**
     * Whether the durable shared Syncthing log may receive this execution's raw output.
     *
     * <p>The answer is delegated to the backend execution, so a caller can route output without
     * knowing which backend produced it.</p>
     */
    public boolean outputBelongsToSharedLog() {
        return execution.outputBelongsToSharedLog();
    }

    /**
     * Starts the worker that streams this execution's raw output while the process runs.
     *
     * <p>Lines are appended to {@code sharedLogFile} only when this execution's output belongs to
     * the durable shared log. Operation-scoped output - a rooted one-shot such as a database reset,
     * for example - is drained and discarded instead, so it can never enter the long-running log,
     * and output that a backend already routed to that log is never appended twice. The stream is
     * always drained to its end, whether or not the bytes are saved, so the process can never stall
     * behind an unread stream.</p>
     *
     * <p>The decision is delegated to the backend that produced this handle, so callers never need
     * to know which one it was.</p>
     *
     * @param sharedLogFile durable log that receives saved lines
     * @param onFailure notified when the output could not be read or saved
     * @return the running worker, which finishes when the output stream ends
     */
    public Thread streamOutput(File sharedLogFile, OutputFailureHandler onFailure) {
        return ExecutionOutputLog.stream(sharedLogFile, this, onFailure);
    }

    public InputStream stderr() {
        return execution.stderr();
    }

    public int await() throws InterruptedException {
        try {
            int exitCode = execution.await();
            exitObserved.set(true);
            release();
            return exitCode;
        } catch (InterruptedException interrupted) {
            // An interrupted wait may still have proven that the launched process exited. A
            // proven-gone execution no longer owns runtime admission, so admission is released
            // here and the interruption is still reported to the caller.
            if (execution.exitProven()) {
                exitObserved.set(true);
                release();
            }
            throw interrupted;
        }
    }

    /**
     * Rejects a successful one-shot result when the launched child never gained exact identity.
     *
     * <p>Call this only after {@link #await()} has observed the child's exit. That ordering keeps
     * runtime admission occupied while an unidentified child may still be running.</p>
     *
     * @throws IllegalStateException if the child has not exited yet
     * @throws ExecutionIdentityUnavailableException if no durable exact identity was established
     */
    public void requireIdentityForSuccessfulOneShotResult() {
        if (!exitObserved.get()) {
            throw new IllegalStateException("One-shot ownership can be checked only after exit");
        }
        if (execution.identity() == null && !execution.exitedBeforeIdentityCapture()) {
            throw new ExecutionIdentityUnavailableException();
        }
    }

    public void destroy() {
        execution.destroy();
    }

    public ExecutionIdentity identity() {
        return execution.identity();
    }

    private void release() {
        if (released.compareAndSet(false, true)) {
            releaseAdmission.run();
        }
    }
}
