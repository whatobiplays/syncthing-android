package com.nutomic.syncthingandroid.runtime;

import com.nutomic.syncthingandroid.service.SyncthingLogFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Objects;

/**
 * Moves root serve output into the shared Syncthing log, once per byte in the normal case.
 *
 * <p>While the app is alive its spool is streamed to the log, and after app death recovery has to
 * append whatever accumulated in the meantime. Both paths use this writer, so output cannot reach
 * the log twice: each chunk is appended and flushed first, and only then is the consumed offset
 * advanced durably. A crash between those two steps duplicates at most {@link #CHUNK_BYTES} of
 * output, a crash before the flush cannot lose any, and the durable offset means a retry resumes at
 * the last recorded position instead of replaying a whole run.</p>
 *
 * <p>Because the offset survives restarts, a reconciliation that could not delete its spool
 * directory appends nothing on the next attempt.</p>
 */
final class RootServeLogWriter {
    /** Largest amount of output that can be duplicated when a crash lands between append and offset. */
    static final int CHUNK_BYTES = 64 * 1024;
    /** Largest consumption-offset text this writer accepts, in bytes. */
    private static final int MAXIMUM_OFFSET_BYTES = 32;

    private final File outputFile;
    private final File consumedFile;
    private final File logFile;
    private final File logTemporaryDirectory;

    RootServeLogWriter(
            File outputFile,
            File consumedFile,
            File logFile,
            File logTemporaryDirectory
    ) {
        this.outputFile = Objects.requireNonNull(outputFile);
        this.consumedFile = Objects.requireNonNull(consumedFile);
        this.logFile = logFile;
        this.logTemporaryDirectory = logTemporaryDirectory;
    }

    /**
     * Creates the writer that moves one run directory's output into the shared log.
     *
     * <p>Live streaming and post-crash reconciliation use this same construction, so both paths
     * share one durable consumption offset and output cannot reach the log twice.</p>
     */
    static RootServeLogWriter forRunDirectory(
            File runDirectory,
            File logFile,
            File logTemporaryDirectory
    ) {
        Objects.requireNonNull(runDirectory);
        return new RootServeLogWriter(
                new File(runDirectory, RootRunSpool.OUTPUT_FILE),
                new File(runDirectory, RootRunSpool.CONSUMED_FILE),
                logFile,
                logTemporaryDirectory
        );
    }

    /**
     * Appends every byte of this run's output that has not reached the log yet.
     *
     * @return the number of bytes appended by this call
     */
    long appendPendingOutput() throws IOException {
        if (logFile == null) {
            throw new IOException("No shared Syncthing log is configured for root output");
        }
        long consumed = readConsumedOffset();
        long length = outputFile.isFile() ? outputFile.length() : 0;
        if (length <= consumed) {
            // Nothing new, and in particular no empty log is created for a run without output.
            return 0;
        }
        File parent = logFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create the directory of the shared Syncthing log");
        }
        long appended = 0;
        try (RandomAccessFile source = new RandomAccessFile(outputFile, "r");
             FileOutputStream log = new FileOutputStream(logFile, true)) {
            source.seek(consumed);
            byte[] buffer = new byte[CHUNK_BYTES];
            while (consumed + appended < length) {
                int wanted = (int) Math.min(CHUNK_BYTES, length - (consumed + appended));
                int read = source.read(buffer, 0, wanted);
                if (read <= 0) {
                    break;
                }
                log.write(buffer, 0, read);
                log.flush();
                log.getFD().sync();
                appended += read;
                writeConsumedOffset(consumed + appended);
            }
        }
        return appended;
    }

    /** Applies the shared log-retention policy after output was appended. */
    void trimLog() {
        if (logFile == null || logTemporaryDirectory == null) {
            return;
        }
        try {
            SyncthingLogFile.trim(logFile, logTemporaryDirectory);
        } catch (IOException ignored) {
            // The next serve start trims the log again before it appends new output.
        }
    }

    /** Reads the durable consumption offset, falling back to zero when it is absent or unusable. */
    long readConsumedOffset() {
        try (java.io.FileInputStream input = new java.io.FileInputStream(consumedFile);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[16];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAXIMUM_OFFSET_BYTES) {
                    return 0;
                }
                output.write(buffer, 0, count);
            }
            long offset = Long.parseLong(
                    new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim()
            );
            return Math.max(offset, 0);
        } catch (IOException | NumberFormatException unusable) {
            // An unreadable offset only means the next append resumes conservatively from zero,
            // which duplicates output but never loses it.
            return 0;
        }
    }

    /** Durably records how much of this run's output already reached the shared log. */
    void writeConsumedOffset(long offset) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("The consumption offset must not be negative");
        }
        File temporary = new File(consumedFile.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(Long.toString(offset).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        if (!temporary.renameTo(consumedFile)) {
            throw new IOException("Could not atomically record the consumed output offset");
        }
    }
}
