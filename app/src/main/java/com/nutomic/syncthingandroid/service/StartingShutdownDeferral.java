package com.nutomic.syncthingandroid.service;

/** Tracks a shutdown requested while STARTING that has not yet entered normal shutdown. */
final class StartingShutdownDeferral {
    private boolean pending;

    /** Marks the deferred shutdown as the owner of the next lifecycle transition. */
    void defer() {
        pending = true;
    }

    boolean isPending() {
        return pending;
    }

    /** Reports whether either the deferred request or an active shutdown blocks startup. */
    boolean blocksStartup(boolean shutdownInProgress) {
        return pending || shutdownInProgress;
    }

    /** Transfers lifecycle ownership to the ordinary shutdown state. */
    void transferToShutdown() {
        pending = false;
    }
}
