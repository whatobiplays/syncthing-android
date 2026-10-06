package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads Linux process identity through operation-scoped root shells.
 *
 * <p>The inspector classifies possible bundled processes for recovery and re-verification. It never
 * authorizes a signal: candidate discovery reports ambiguity only, and a signal may follow only
 * after the ownership manager has matched the durable record against a live process.</p>
 *
 * <p>Bulk discovery skips entries whose executable link cannot be resolved, because such an entry
 * cannot be shown to run the bundled executable. An entry whose executable matches but whose start
 * time cannot be read raises an error instead, so recovery fails closed rather than treating a
 * possible bundled process as unrelated.</p>
 */
final class RootExecutionInspector implements ExecutionInspector {
    private final RootShellProvider shells;

    RootExecutionInspector(RootShellProvider shells) {
        this.shells = Objects.requireNonNull(shells);
    }

    @Override
    public String currentBootId() throws IOException {
        try (RootShell shell = shells.acquireHelperShell()) {
            return shell.readBootId();
        }
    }

    @Override
    public InspectionResult inspect(int pid) throws IOException {
        try (RootShell shell = shells.acquireHelperShell()) {
            String bootId = shell.readBootId();
            RootShell.ProcessEntry entry = findEntry(shell.listProcesses(), pid);
            if (entry == null) {
                return InspectionResult.processAbsent();
            }
            if (entry.executablePath() == null || entry.processStartTimeTicks() <= 0) {
                return InspectionResult.unknown();
            }
            return InspectionResult.live(identity(entry, shell.readRunToken(pid), bootId));
        }
    }

    @Override
    public List<ExecutionIdentity> findBundledCandidates(String executablePath) throws IOException {
        try (RootShell shell = shells.acquireHelperShell()) {
            String bootId = shell.readBootId();
            List<ExecutionIdentity> candidates = new ArrayList<>();
            for (RootShell.ProcessEntry entry : shell.listProcesses()) {
                String candidatePath = entry.executablePath();
                if (candidatePath == null) {
                    continue;
                }
                if (!ProcExecutionInspector.isBundledExecutableCandidate(
                        candidatePath, executablePath)) {
                    continue;
                }
                candidates.add(candidateIdentity(entry, bootId));
            }
            return candidates;
        }
    }

    @Override
    public ExecutionIdentity findLaunchedProcess(String executablePath, String runToken)
            throws IOException {
        try (RootShell shell = shells.acquireHelperShell()) {
            String bootId = shell.readBootId();
            ExecutionIdentity found = null;
            for (RootShell.ProcessEntry entry : shell.listProcesses()) {
                String candidatePath = entry.executablePath();
                if (candidatePath == null) {
                    continue;
                }
                if (!ProcExecutionInspector.hasExactExecutablePath(candidatePath, executablePath)) {
                    continue;
                }
                if (entry.processStartTimeTicks() <= 0) {
                    throw new IOException(
                            "Could not read the start time of a possible bundled process"
                    );
                }
                String candidateToken = shell.readRunToken(entry.pid());
                if (!runToken.equals(candidateToken)) {
                    continue;
                }
                if (found != null) {
                    throw new IOException("Multiple processes carry the new launch token");
                }
                found = identity(entry, candidateToken, bootId);
            }
            return found;
        }
    }

    private static ExecutionIdentity candidateIdentity(RootShell.ProcessEntry entry, String bootId)
            throws IOException {
        if (entry.processStartTimeTicks() <= 0) {
            throw new IOException("Could not read the start time of a possible bundled process");
        }
        return identity(entry, "", bootId);
    }

    private static ExecutionIdentity identity(
            RootShell.ProcessEntry entry,
            String runToken,
            String bootId
    ) {
        return new ExecutionIdentity(
                entry.pid(),
                entry.processStartTimeTicks(),
                bootId,
                entry.executablePath(),
                runToken == null ? "" : runToken
        );
    }

    private static RootShell.ProcessEntry findEntry(List<RootShell.ProcessEntry> entries, int pid) {
        for (RootShell.ProcessEntry entry : entries) {
            if (entry.pid() == pid) {
                return entry;
            }
        }
        return null;
    }
}
