package com.nutomic.syncthingandroid.runtime;

/**
 * Reports that an execution ended without an authenticated Syncthing exit status.
 *
 * <p>A backend can prove that the bundled process is gone while the status reported for it cannot
 * be attributed to that process: a detached root transport keeps its own client process, so the
 * status that client reports describes the client and not Syncthing. Whenever a backend proves the
 * process exited but cannot prove whose status it received, it reports this typed result instead
 * of an exit code, so no caller can feed an unauthenticated number into the ordinary Syncthing
 * restart or crash policy.</p>
 *
 * <p>An execution whose exit could not be verified at all reports this failure as well, wrapped
 * around the typed root failure that prevented the verification. Callers tell the two situations
 * apart through {@link PrivilegeBackend.Execution#exitProven()}: only a proven process exit lets
 * the runtime release its admission, and a local transport client that disappeared never releases
 * it on its own.</p>
 *
 * <p>The exception is unchecked because the mode-neutral runtime seam only declares checked I/O
 * and interruption failures, and because this is a terminal result rather than a recoverable I/O
 * error.</p>
 */
public final class ExecutionExitStatusUnavailableException extends IllegalStateException {

    public ExecutionExitStatusUnavailableException(String detail) {
        super(detail);
    }

    public ExecutionExitStatusUnavailableException(String detail, Throwable cause) {
        super(detail, cause);
    }
}
