package com.nutomic.syncthingandroid.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads process identity from Linux procfs using APIs available on Android API 23 and later. */
final class ProcExecutionInspector implements ExecutionInspector {
    static final String RUN_TOKEN_ENVIRONMENT = "STANDROID_RUN_TOKEN";
    private static final String PROC_DELETED_SUFFIX = " (deleted)";
    private static final File PROC_DIRECTORY = new File("/proc");
    private static final File BOOT_ID_FILE = new File("/proc/sys/kernel/random/boot_id");

    @Override
    public String currentBootId() throws IOException {
        return parseBootId(readAll(BOOT_ID_FILE));
    }

    @Override
    public InspectionResult inspect(int pid) {
        try {
            return readProcess(pid, currentBootId(), true);
        } catch (IOException | RuntimeException e) {
            return InspectionResult.unknown();
        }
    }

    @Override
    public List<ExecutionIdentity> findBundledCandidates(String executablePath) throws IOException {
        File[] processDirectories = PROC_DIRECTORY.listFiles();
        if (processDirectories == null) throw new IOException("Could not list procfs");
        List<ExecutionIdentity> candidates = new ArrayList<>();
        String bootId = currentBootId();
        for (File processDirectory : processDirectories) {
            int pid = parsePid(processDirectory.getName());
            if (pid <= 0) continue;
            InspectionResult inspection = readProcess(pid, bootId, false);
            if (inspection.status() == InspectionResult.Status.UNKNOWN) {
                throw new IOException("Could not inspect procfs process " + pid);
            }
            if (inspection.status() == InspectionResult.Status.PROCESS_ABSENT) continue;
            ExecutionIdentity snapshot = inspection.identity();
            if (isBundledExecutableCandidate(snapshot.executablePath(), executablePath)) {
                candidates.add(snapshot);
            }
        }
        return candidates;
    }

    @Override
    public ExecutionIdentity findLaunchedProcess(String executablePath, String runToken)
            throws IOException {
        File[] processDirectories = PROC_DIRECTORY.listFiles();
        if (processDirectories == null) throw new IOException("Could not list procfs");
        ExecutionIdentity found = null;
        String bootId = currentBootId();
        for (File processDirectory : processDirectories) {
            int pid = parsePid(processDirectory.getName());
            if (pid <= 0) continue;
            InspectionResult inspection = readProcess(pid, bootId, false);
            if (inspection.status() == InspectionResult.Status.UNKNOWN) {
                throw new IOException("Could not inspect procfs process " + pid);
            }
            if (inspection.status() == InspectionResult.Status.PROCESS_ABSENT) continue;
            ExecutionIdentity candidate = inspection.identity();
            if (!hasExactExecutablePath(candidate.executablePath(), executablePath)) {
                continue;
            }
            String candidateToken;
            try {
                candidateToken = readRunToken(new File(
                        new File(PROC_DIRECTORY, Integer.toString(pid)), "environ"
                ));
            } catch (IOException e) {
                if (processPresence(pid) == ProcessPresence.ABSENT) {
                    continue;
                }
                throw e;
            }
            if (!runToken.equals(candidateToken)) continue;
            if (found != null) {
                throw new IOException("Multiple processes carry the new launch token");
            }
            found = new ExecutionIdentity(
                    candidate.pid(), candidate.processStartTimeTicks(), candidate.bootId(),
                    candidate.executablePath(), candidateToken
            );
        }
        return found;
    }

    private InspectionResult readProcess(int pid, String bootId, boolean includeRunToken) {
        File processDirectory = new File(PROC_DIRECTORY, Integer.toString(pid));
        File executable = new File(processDirectory, "exe");
        ProcessPresence presence = processPresence(pid);
        if (presence == ProcessPresence.ABSENT) return InspectionResult.processAbsent();
        if (presence == ProcessPresence.UNKNOWN) return InspectionResult.unknown();
        if (!processDirectory.isDirectory()) return InspectionResult.unknown();
        String executablePath;
        try {
            executablePath = Os.readlink(executable.getAbsolutePath());
        } catch (ErrnoException e) {
            return inspectionAfterReadFailure(pid);
        }
        long startTicks;
        String runToken = "";
        try {
            startTicks = readStartTimeTicks(new File(processDirectory, "stat"));
            if (includeRunToken) {
                runToken = readRunToken(new File(processDirectory, "environ"));
            }
        } catch (IOException e) {
            return inspectionAfterReadFailure(pid);
        }
        try {
            return InspectionResult.live(new ExecutionIdentity(
                    pid, startTicks, bootId, executablePath,
                    runToken == null ? "" : runToken
            ));
        } catch (IllegalArgumentException e) {
            return inspectionAfterReadFailure(pid);
        }
    }

    private enum ProcessPresence { PRESENT, ABSENT, UNKNOWN }

    private static ProcessPresence processPresence(int pid) {
        try {
            Os.stat(new File(PROC_DIRECTORY, Integer.toString(pid)).getAbsolutePath());
            return ProcessPresence.PRESENT;
        } catch (ErrnoException e) {
            return e.errno == OsConstants.ENOENT
                    ? ProcessPresence.ABSENT
                    : ProcessPresence.UNKNOWN;
        }
    }

    private static InspectionResult inspectionAfterReadFailure(int pid) {
        return processPresence(pid) == ProcessPresence.ABSENT
                ? InspectionResult.processAbsent()
                : InspectionResult.unknown();
    }

    /**
     * Matches a possible bundled process by executable basename for ambiguity detection only.
     *
     * <p>Process discovery deliberately accepts paths from earlier app installations. Linux also
     * appends {@code " (deleted)"} to procfs executable targets after the executable is unlinked.
     * This weak match must never be used to authorize a signal; exact ownership compares the full
     * recorded executable target.</p>
     */
    static boolean isBundledExecutableCandidate(String candidatePath, String bundledPath) {
        if (candidatePath == null || bundledPath == null) return false;
        return executableBasename(candidatePath).equals(executableBasename(bundledPath));
    }

    /** Matches the full executable target for identifying a process launched in this run. */
    static boolean hasExactExecutablePath(String processPath, String expectedPath) {
        return expectedPath != null
                && ExecutionIdentity.sameExecutableTarget(processPath, expectedPath);
    }

    static String parseBootId(byte[] contents) throws IOException {
        String bootId = new String(contents, StandardCharsets.UTF_8).trim();
        if (bootId.isEmpty()) throw new IOException("Empty procfs boot ID");
        return bootId;
    }

    private static String executableBasename(String path) {
        String livePath = path.endsWith(PROC_DELETED_SUFFIX)
                ? path.substring(0, path.length() - PROC_DELETED_SUFFIX.length())
                : path;
        return new File(livePath).getName();
    }

    static long readStartTimeTicks(byte[] stat) throws IOException {
        String contents = new String(stat, StandardCharsets.UTF_8);
        int closingParenthesis = contents.lastIndexOf(')');
        if (closingParenthesis < 0 || closingParenthesis + 2 >= contents.length()) {
            throw new IOException("Malformed proc stat record");
        }
        String[] fields = contents.substring(closingParenthesis + 2).trim().split("\\s+");
        int startTimeIndex = 19; // stat field 22; the suffix starts at field 3.
        if (fields.length <= startTimeIndex) throw new IOException("Short proc stat record");
        try {
            long startTicks = Long.parseLong(fields[startTimeIndex]);
            if (startTicks <= 0) throw new NumberFormatException("nonpositive start time");
            return startTicks;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid proc start time", e);
        }
    }

    private static long readStartTimeTicks(File statFile) throws IOException {
        return readStartTimeTicks(readAll(statFile));
    }

    private static String readRunToken(File environFile) throws IOException {
        byte[] contents = readAll(environFile);
        int start = 0;
        for (int index = 0; index <= contents.length; index++) {
            if (index != contents.length && contents[index] != 0) continue;
            String entry = new String(
                    contents, start, index - start, StandardCharsets.UTF_8
            );
            String prefix = RUN_TOKEN_ENVIRONMENT + "=";
            if (entry.startsWith(prefix)) return entry.substring(prefix.length());
            start = index + 1;
        }
        return null;
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static int parsePid(String name) {
        if (name.isEmpty()) return -1;
        for (int index = 0; index < name.length(); index++) {
            if (!Character.isDigit(name.charAt(index))) return -1;
        }
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
