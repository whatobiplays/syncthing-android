package com.nutomic.syncthingandroid.runtime;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mode-neutral handle for one admitted bundled Syncthing invocation.
 *
 * <p>A completed wait releases runtime admission. Destruction requests a SIGKILL only when the
 * handle's durable identity still verifies exactly.</p>
 */
public final class SyncthingExecution {
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

    public InputStream stderr() {
        return execution.stderr();
    }

    public int await() throws InterruptedException {
        int exitCode = execution.await();
        exitObserved.set(true);
        release();
        return exitCode;
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
        if (execution.identity() == null) throw new ExecutionIdentityUnavailableException();
    }

    public void destroy() {
        execution.destroy();
    }

    public ExecutionIdentity identity() {
        return execution.identity();
    }

    public ExecutionOwnershipManager.Observation observe() {
        if (exitObserved.get()) return ExecutionOwnershipManager.Observation.EXITED;
        return execution.observe();
    }

    public ExecutionOwnershipManager.SignalResult signalIfOwned(
            ExecutionOwnershipManager.Signal signal
    ) {
        return execution.signalIfOwned(signal);
    }

    private void release() {
        if (released.compareAndSet(false, true)) {
            releaseAdmission.run();
        }
    }
}
