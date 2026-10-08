package com.nutomic.syncthingandroid.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Owns shared Syncthing log appends and rotation.
 *
 * <p>Every app-owned writer and the rotation path synchronize on one process-local lock. Rotation
 * replaces the pathname, so a writer must never retain a descriptor across that replacement:
 * each append opens, writes, and closes the current pathname while holding the same lock as
 * {@link #trim(File, File)}.</p>
 */
public final class SyncthingLogFile {
    /** Number of lines the log file is allowed to keep. */
    public static final int LOG_FILE_MAX_LINES = 200000;
    /** Buffer size; a multiple of the filesystem block size so reads stay block aligned. */
    private static final int LOG_FILE_BUFFER_SIZE = 1024 * 1024;
    private static final Object LOG_LOCK = new Object();

    private SyncthingLogFile() {
    }

    /** Appends bytes to the visible shared log without retaining a descriptor across rotation. */
    public static void append(File logFile, byte[] data) throws IOException {
        append(logFile, data, 0, data.length, false);
    }

    /** Appends one byte range and fsyncs it before returning. */
    public static void appendDurably(
            File logFile,
            byte[] data,
            int offset,
            int length
    ) throws IOException {
        append(logFile, data, offset, length, true);
    }

    private static void append(
            File logFile,
            byte[] data,
            int offset,
            int length,
            boolean durable
    ) throws IOException {
        synchronized (LOG_LOCK) {
            File parent = logFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create the directory of the shared Syncthing log");
            }
            try (FileOutputStream output = new FileOutputStream(logFile, true)) {
                output.write(data, offset, length);
                output.flush();
                if (durable) {
                    output.getFD().sync();
                }
            }
        }
    }

    /**
     * Keeps only the last {@link #LOG_FILE_MAX_LINES} lines of the log file.
     *
     * @param logFile the log file to trim; a missing file is left untouched
     * @param temporaryDirectory directory that receives the temporary file used for the rewrite
     */
    public static void trim(File logFile, File temporaryDirectory) throws IOException {
        synchronized (LOG_LOCK) {
            trimLocked(logFile, temporaryDirectory);
        }
    }

    private static void trimLocked(File logFile, File temporaryDirectory) throws IOException {
        if (!logFile.exists()) {
            return;
        }

        try (RandomAccessFile input = new RandomAccessFile(logFile, "r")) {
            byte[] buffer = new byte[LOG_FILE_BUFFER_SIZE];
            long length = input.length();
            long chunks = length / buffer.length + (length % buffer.length == 0 ? 0 : 1);
            int newlinesRemaining = LOG_FILE_MAX_LINES + 1;
            long truncationOffset = -1;

            for (long chunk = chunks - 1; chunk >= 0; chunk--) {
                long offset = buffer.length * chunk;
                input.seek(offset);
                int size = (int) Math.min(length - offset, buffer.length);
                input.readFully(buffer, 0, size);

                int found = findNthLastNewline(buffer, size, newlinesRemaining);
                if (found >= 0) {
                    truncationOffset = offset + found + 1;
                    break;
                }
                newlinesRemaining = -found;
            }

            if (truncationOffset < 0) {
                return;
            }

            input.seek(truncationOffset);
            File temporaryFile = new File(temporaryDirectory, "syncthing.log.tmp");
            long remaining = length - truncationOffset;

            try (FileOutputStream output = new FileOutputStream(temporaryFile)) {
                while (remaining > 0) {
                    int size = (int) Math.min(remaining, buffer.length);
                    input.readFully(buffer, 0, size);
                    output.write(buffer, 0, size);
                    remaining -= size;
                }
            }

            if (!temporaryFile.renameTo(logFile)) {
                throw new IOException("Could not replace the shared Syncthing log");
            }
        }
    }

    /**
     * Finds the nth newline counted from the end of one buffer.
     *
     * @return the offset of that newline inside the buffer, or the negative of the number of
     *     newlines still missing when the buffer does not contain it
     */
    private static int findNthLastNewline(byte[] data, int size, int nth) {
        if (nth <= 0) {
            throw new IllegalArgumentException("nth must be positive: " + nth);
        }

        for (int i = size - 1; i >= 0; i--) {
            if (data[i] == '\n') {
                nth--;

                if (nth == 0) {
                    return i;
                }
            }
        }

        return -nth;
    }
}
