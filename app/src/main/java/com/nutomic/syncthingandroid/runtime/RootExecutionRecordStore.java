package com.nutomic.syncthingandroid.runtime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Persists the durable identity of one root Syncthing execution as bounded, versioned text.
 *
 * <p>The version-1 schema is the exact line sequence written by the audited launch script:
 * {@code version=1}, {@code pid=}, {@code start_ticks=}, {@code boot_id=}, {@code exe=}, and
 * {@code token=}. Any deviation in that sequence is reported as corrupt, so recovery fails closed
 * instead of trusting partial evidence. The record file lives in the app's no-backup private
 * storage and is written through a temporary file that is renamed into place.</p>
 */
final class RootExecutionRecordStore implements ExecutionRecordStore {
    static final String VERSION_LINE = "version=1";
    /** Version of the pre-delivery launch state written before any launch byte is delivered. */
    static final String PENDING_VERSION_LINE = "version=2";
    static final String PID_PREFIX = "pid=";
    static final String START_TICKS_PREFIX = "start_ticks=";
    static final String BOOT_ID_PREFIX = "boot_id=";
    static final String EXECUTABLE_PREFIX = "exe=";
    static final String RUN_TOKEN_PREFIX = "token=";
    static final String STATE_PREFIX = "state=";
    static final String PENDING_STATE = "pending";

    private static final int SCHEMA_LINES = 6;
    private static final int PENDING_SCHEMA_LINES = 7;
    private static final long MAX_RECORD_BYTES = 16 * 1024;

    private final File recordFile;

    RootExecutionRecordStore(File recordFile) {
        this.recordFile = Objects.requireNonNull(recordFile);
    }

    @Override
    public synchronized ReadResult read() {
        return readEvidenceFile(recordFile);
    }

    /**
     * Reads one evidence file written in this class's version-1 schema.
     *
     * <p>The audited launch script writes its pre-{@code exec} evidence in exactly the same schema,
     * so recovery can parse evidence the app never had a chance to convert into the canonical
     * record. A missing file reports {@link ReadResult.Status#MISSING}, unreadable bytes report
     * {@link ReadResult.Status#READ_FAILED}, and any deviation from the schema reports
     * {@link ReadResult.Status#CORRUPT} or {@link ReadResult.Status#UNSUPPORTED_VERSION}.</p>
     */
    static ReadResult readEvidenceFile(File evidenceFile) {
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(evidenceFile)) {
            bytes = readBounded(input);
        } catch (FileNotFoundException e) {
            return evidenceFile.exists() ? ReadResult.readFailed() : ReadResult.missing();
        } catch (IOException e) {
            return ReadResult.readFailed();
        }
        if (bytes == null) {
            return ReadResult.corrupt();
        }
        return parse(bytes);
    }

    @Override
    public synchronized void write(ExecutionIdentity identity) throws IOException {
        StringBuilder text = new StringBuilder()
                .append(VERSION_LINE).append('\n')
                .append(PID_PREFIX).append(identity.pid()).append('\n')
                .append(START_TICKS_PREFIX).append(identity.processStartTimeTicks()).append('\n')
                .append(BOOT_ID_PREFIX).append(identity.bootId()).append('\n')
                .append(EXECUTABLE_PREFIX).append(identity.executablePath()).append('\n')
                .append(RUN_TOKEN_PREFIX).append(identity.runToken()).append('\n');
        writeEvidenceFile(recordFile, text.toString());
    }

    /**
     * Writes the pre-delivery launch state of one transport into a run spool evidence file.
     *
     * <p>The state is written before the audited launch script delivers its first byte, so a later
     * application process can still prove that the transport may become the bundled process. It
     * names the same kernel identity fields as the pre-exec evidence the launch script writes
     * later, and the launch script atomically renames its complete evidence over this state before
     * {@code exec}.</p>
     */
    static void writePendingEvidence(File evidenceFile, ExecutionIdentity transportIdentity)
            throws IOException {
        Objects.requireNonNull(evidenceFile);
        Objects.requireNonNull(transportIdentity);
        StringBuilder text = new StringBuilder()
                .append(PENDING_VERSION_LINE).append('\n')
                .append(STATE_PREFIX).append(PENDING_STATE).append('\n')
                .append(PID_PREFIX).append(transportIdentity.pid()).append('\n')
                .append(START_TICKS_PREFIX)
                .append(transportIdentity.processStartTimeTicks()).append('\n')
                .append(BOOT_ID_PREFIX).append(transportIdentity.bootId()).append('\n')
                .append(EXECUTABLE_PREFIX).append(transportIdentity.executablePath()).append('\n')
                .append(RUN_TOKEN_PREFIX).append(transportIdentity.runToken()).append('\n');
        writeEvidenceFile(evidenceFile, text.toString());
    }

    /**
     * Reads the pending launch state of one evidence file.
     *
     * <p>A file that carries no pending state is reported as absent, because committed pre-exec
     * evidence and the untouched empty file the run spool pre-creates both prove that no delivery
     * is in flight. A file that announces pending state but cannot be parsed completely is
     * reported as unresolved, which callers must treat as fail-closed.</p>
     */
    static ExecutionRecordStore.PendingLaunch readPendingEvidence(File evidenceFile) {
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(evidenceFile)) {
            bytes = readBounded(input);
        } catch (FileNotFoundException e) {
            return evidenceFile.exists()
                    ? ExecutionRecordStore.PendingLaunch.unresolved()
                    : ExecutionRecordStore.PendingLaunch.none();
        } catch (IOException e) {
            return ExecutionRecordStore.PendingLaunch.unresolved();
        }
        if (bytes == null) {
            return ExecutionRecordStore.PendingLaunch.unresolved();
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.startsWith(PENDING_VERSION_LINE)) {
            return ExecutionRecordStore.PendingLaunch.none();
        }
        String[] lines = text.split("\n", -1);
        if (lines.length != PENDING_SCHEMA_LINES + 1
                || !lines[PENDING_SCHEMA_LINES].isEmpty()
                || !(STATE_PREFIX + PENDING_STATE).equals(lines[1])) {
            return ExecutionRecordStore.PendingLaunch.unresolved();
        }
        ExecutionIdentity transportIdentity = identity(lines, 2);
        return transportIdentity == null
                ? ExecutionRecordStore.PendingLaunch.unresolved()
                : ExecutionRecordStore.PendingLaunch.pending(transportIdentity);
    }

    private static void writeEvidenceFile(File evidenceFile, String text) throws IOException {
        File parent = evidenceFile.getParentFile();
        if (parent == null) {
            throw new IOException("The execution record path has no parent directory");
        }
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create the execution record directory");
        }
        File temporary = new File(evidenceFile.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        if (!temporary.renameTo(evidenceFile)) {
            throw new IOException("Could not atomically replace the execution record");
        }
    }

    @Override
    public synchronized boolean deleteIfRunTokenMatches(String runToken) throws IOException {
        ReadResult current = read();
        if (current.status() != ReadResult.Status.VALID
                || !current.identity().runToken().equals(runToken)) {
            return false;
        }
        if (!recordFile.delete()) {
            throw new IOException("Could not delete the execution record");
        }
        return true;
    }

    private static byte[] readBounded(FileInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > MAX_RECORD_BYTES) {
                return null;
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static ReadResult parse(byte[] bytes) {
        if (bytes.length == 0) {
            return ReadResult.corrupt();
        }
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        if (PENDING_VERSION_LINE.equals(lines[0])) {
            return parsePending(lines);
        }
        if (lines.length != SCHEMA_LINES + 1 || !lines[SCHEMA_LINES].isEmpty()) {
            return ReadResult.corrupt();
        }
        if (!VERSION_LINE.equals(lines[0])) {
            return lines[0].startsWith("version=")
                    ? ReadResult.unsupportedVersion()
                    : ReadResult.corrupt();
        }
        ExecutionIdentity identity = identity(lines, 1);
        return identity == null ? ReadResult.corrupt() : ReadResult.valid(identity);
    }

    /**
     * Parses the pre-delivery launch schema, which carries a state line before the shared identity.
     *
     * <p>Any deviation from the exact schema is corrupt rather than unsupported, because a partial
     * or damaged pre-delivery record can never be treated as a weaker state that permits a
     * replacement launch.</p>
     *
     * <p>A complete pre-delivery record is reported as a valid identity even though the process it
     * names may still be the launch transport. It carries the exact kernel identity of the process
     * and the executable that process is expected to become, so every reader that treats durable
     * identities the same way treats this transport as an in-flight launch that owns its run: the
     * creation confirmation recognizes its handoff, and recovery refuses to treat it as launchable
     * or to signal it. {@link #readPendingEvidence(File)} adds the pre-delivery vocabulary for
     * callers that must distinguish that state from committed pre-exec evidence.</p>
     */
    private static ReadResult parsePending(String[] lines) {
        if (lines.length != PENDING_SCHEMA_LINES + 1
                || !lines[PENDING_SCHEMA_LINES].isEmpty()
                || !(STATE_PREFIX + PENDING_STATE).equals(lines[1])) {
            return ReadResult.corrupt();
        }
        ExecutionIdentity identity = identity(lines, 2);
        return identity == null ? ReadResult.corrupt() : ReadResult.valid(identity);
    }

    /** Parses the identity fields shared by both durable launch schemas. */
    private static ExecutionIdentity identity(String[] lines, int offset) {
        String pidText = value(lines[offset], PID_PREFIX);
        String ticksText = value(lines[offset + 1], START_TICKS_PREFIX);
        String bootId = value(lines[offset + 2], BOOT_ID_PREFIX);
        String executable = value(lines[offset + 3], EXECUTABLE_PREFIX);
        String runToken = value(lines[offset + 4], RUN_TOKEN_PREFIX);
        if (pidText == null || ticksText == null || bootId == null
                || executable == null || runToken == null) {
            return null;
        }
        if (bootId.isEmpty() || executable.isEmpty() || runToken.isEmpty()) {
            return null;
        }
        try {
            return new ExecutionIdentity(
                    Integer.parseInt(pidText),
                    Long.parseLong(ticksText),
                    bootId,
                    executable,
                    runToken
            );
        } catch (IllegalArgumentException invalidRecord) {
            return null;
        }
    }

    private static String value(String line, String prefix) {
        return line.startsWith(prefix) ? line.substring(prefix.length()) : null;
    }
}
