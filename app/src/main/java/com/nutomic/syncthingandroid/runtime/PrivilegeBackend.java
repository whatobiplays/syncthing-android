package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.io.InputStream;

/** Backend contract for launching and controlling bundled Syncthing executions. */
public interface PrivilegeBackend {
    Execution start(SyncthingCommand command, SyncthingEnvironment environment)
            throws IOException, ExecutableNotFoundException;

    default ExecutionOwnershipManager.RecoveryAssessment recoverExecutions() {
        return ExecutionOwnershipManager.RecoveryAssessment.noCandidate();
    }

    default ExecutionOwnershipManager.SignalResult signalIfOwned(
            ExecutionIdentity identity,
            ExecutionOwnershipManager.Signal signal
    ) {
        return ExecutionOwnershipManager.SignalResult.NOT_OWNED;
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

        default ExecutionOwnershipManager.SignalResult signalIfOwned(
                ExecutionOwnershipManager.Signal signal
        ) {
            return ExecutionOwnershipManager.SignalResult.NOT_OWNED;
        }
    }
}
