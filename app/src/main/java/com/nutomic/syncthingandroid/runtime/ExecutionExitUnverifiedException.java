package com.nutomic.syncthingandroid.runtime;

/**
 * Reports that an execution ended without a verified exit.
 *
 * <p>This is the mode-neutral form of a backend whose exit verification failed. The launched
 * process may still be running, so the runtime neither finalizes the execution nor releases its
 * admission, and no replacement launch may start before a later verification proves the exit. The
 * backend-specific typed failure stays attached as this exception's cause.</p>
 *
 * <p>A later explicit wait on the same execution is a new privileged operation, so it gets one
 * bounded re-verification attempt of its own. When that attempt proves the exit, the execution
 * settles and reports its real outcome instead of this failure.</p>
 */
public final class ExecutionExitUnverifiedException extends IllegalStateException {

    public ExecutionExitUnverifiedException(String detail, Throwable cause) {
        super(detail, cause);
    }
}
