package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;

/** Root operations the service can invoke without changing the selected execution mode. */
public interface RootAuthorizationCapability {
    /** Acquires and verifies root for an explicit user action. */
    void verifyAuthorization() throws IOException;

    /** Reports only authorization already confirmed in this process; it never acquires root. */
    boolean isAuthorizationAvailableWithoutPrompt();

    /** Reapplies optional inotify tuning only when root authorization is already available. */
    TuningOutcome applyInotifyWatchLimitIfAuthorized();
}
