package com.nutomic.syncthingandroid.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Trims the shared Syncthing log file so it keeps only its most recent lines.
 *
 * <p>The class owns the log-size policy for every component that appends to the log, including the
 * root execution spool reconciler, so the policy has a single implementation and the log cannot
 * grow without bound.</p>
 */
public final class SyncthingLogFile {
    /** Number of lines the log file is allowed to keep. */
    public static final int LOG_FILE_MAX_LINES = 200000;
    /** Buffer size; a multiple of the filesystem block size so reads stay block aligned. */
    private static final int LOG_FILE_BUFFER_SIZE = 1024 * 1024;

    private SyncthingLogFile() {
    }

    /**
     * Keeps only the last {@link #LOG_FILE_MAX_LINES} lines of the log file.
     *
     * @param logFile the log file to trim; a missing file is left untouched
     * @param temporaryDirectory directory that receives the temporary file used for the rewrite
     */
    public static void trim(File logFile, File temporaryDirectory) throws IOException {
        if (!logFile.exists()) {
            return;
        }

        try (RandomAccessFile input = new RandomAccessFile(logFile, "r")) {
            // Find the offset of the (n + 1)th newline with constant memory. The last n lines is
            // everything after that point. This reads in block-aligned chunks while
            // LOG_FILE_BUFFER_SIZE is a multiple of the filesystem block size.
            byte[] buffer = new byte[LOG_FILE_BUFFER_SIZE];
            long length = input.length();
            // Ceiling division written without Math.ceilDiv, which is unavailable on the
            // minimum supported Android version.
            long chunks = length / buffer.length + (length % buffer.length == 0 ? 0 : 1);
            int newlinesRemaining = LOG_FILE_MAX_LINES + 1;
            long truncationOffset = -1;

            for (long chunk = chunks - 1; chunk >= 0; chunk--) {
                long offset = buffer.length * chunk;
                input.seek(offset);

                // The last chunk can be smaller than the whole buffer.
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
                // The file already contains fewer than the maximum number of lines.
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

            temporaryFile.renameTo(logFile);
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
