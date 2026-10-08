package com.nutomic.syncthingandroid.service;

import com.nutomic.syncthingandroid.runtime.SyncthingCommand;

import java.util.Objects;

/** Retains startup work that must wait for process-wide certificate restoration. */
final class CertificateRestoreDeferredStart {
    private SyncthingCommand command;

    /** Records a command without allowing automatic serving to replace an explicit delta reset. */
    void defer(SyncthingCommand requested) {
        Objects.requireNonNull(requested);
        if (command == SyncthingCommand.RESET_DELTAS
                && requested != SyncthingCommand.RESET_DELTAS) {
            return;
        }
        command = requested;
    }

    /** Keeps lifecycle start admission closed until the deferred command is resumed or cancelled. */
    boolean isPending() {
        return command != null;
    }

    /** Run Conditions cancel automatic serving but do not cancel an explicit maintenance reset. */
    void onRunConditionChanged(boolean shouldRun) {
        if (!shouldRun && command != SyncthingCommand.RESET_DELTAS) {
            command = null;
        }
    }

    /** Cancels deferred work after an explicit user STOP and returns the cancelled command. */
    SyncthingCommand cancelForExplicitStop() {
        SyncthingCommand cancelled = command;
        command = null;
        return cancelled;
    }

    /** Returns the pending command without releasing lifecycle admission. */
    SyncthingCommand peek() {
        return command;
    }

    /** Returns and clears the command after the certificate restore continuation runs. */
    SyncthingCommand take() {
        SyncthingCommand deferred = command;
        command = null;
        return deferred;
    }

    /** Requires safe storage release and lifecycle admission before any deferred command resumes. */
    static boolean canResume(
            SyncthingCommand command,
            boolean safeToStart,
            boolean shouldRun,
            boolean serviceCanStart
    ) {
        return command != null
                && safeToStart
                && serviceCanStart
                && (command == SyncthingCommand.RESET_DELTAS || shouldRun);
    }
}
