package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.io.InputStream;

/** Backend contract for launching and controlling bundled Syncthing executions. */
public interface PrivilegeBackend {
    void validateLaunchPrerequisites() throws IOException, ExecutableNotFoundException;

    Execution start(SyncthingCommand command, SyncthingEnvironment environment)
            throws IOException, ExecutableNotFoundException;

    ExecutionOwnershipManager.RecoveryAssessment recoverExecutions();

    default ExecutionOwnershipManager.SignalAttempt signalIfOwned(
            ExecutionIdentity identity,
            ExecutionOwnershipManager.Signal signal
    ) {
        return ExecutionOwnershipManager.SignalAttempt.NOT_OWNED;
    }

    default ExecutionOwnershipManager.Observation observe(ExecutionIdentity identity) {
        return ExecutionOwnershipManager.Observation.UNKNOWN;
    }

    default boolean clearAfterExit(ExecutionIdentity identity) throws IOException {
        return false;
    }

    ConfigStorage configStorage();

    FolderWriteability validateCandidateFolder(String path);

    ConflictDiscoveryResult discoverConflicts(ConfiguredFolderReference folder);

    FolderIgnoreResult loadFolderIgnoreList(ConfiguredFolderReference folder);

    void saveFolderIgnoreList(ConfiguredFolderReference folder, String[] ignore);

    void runFolderScripts(ConfiguredFolderReference folder, FolderEvent event);

    interface Execution {
        InputStream stdout();

        InputStream stderr();

        int await() throws InterruptedException;

        void destroy();

        default ExecutionIdentity identity() {
            return null;
        }

        default ExecutionOwnershipManager.Observation observe() {
            return ExecutionOwnershipManager.Observation.UNKNOWN;
        }

        default ExecutionOwnershipManager.SignalAttempt signalIfOwned(
                ExecutionOwnershipManager.Signal signal
        ) {
            return ExecutionOwnershipManager.SignalAttempt.NOT_OWNED;
        }
    }
}
