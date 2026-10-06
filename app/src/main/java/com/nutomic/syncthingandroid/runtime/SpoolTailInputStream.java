package com.nutomic.syncthingandroid.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Objects;

/**
 * Blocking reader for the output spool of one root Syncthing run.
 *
 * <p>The spool file grows while the UID-0 process runs, so the stream returns data as it appears
 * and reports end of stream only after the owning process has exited and every written byte has
 * been consumed. The liveness source and the poll interval are injected, so tests can drive both
 * transitions without relying on timing.</p>
 */
final class SpoolTailInputStream extends InputStream {
    /** Reports whether the process that writes the spool has already exited. */
    @FunctionalInterface
    interface Liveness {
        boolean hasExited();
    }

    private final RandomAccessFile file;
    private final Liveness liveness;
    private final long pollMillis;
    private final byte[] singleByte = new byte[1];
    private boolean closed;

    SpoolTailInputStream(File spoolFile, Liveness liveness, long pollMillis) throws IOException {
        this.file = new RandomAccessFile(Objects.requireNonNull(spoolFile), "r");
        this.liveness = Objects.requireNonNull(liveness);
        this.pollMillis = pollMillis;
    }

    @Override
    public int read() throws IOException {
        while (true) {
            int count = readAvailable(singleByte, 0, 1);
            if (count == 1) {
                return singleByte[0] & 0xFF;
            }
            if (count < 0) {
                return -1;
            }
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.requireNonNull(buffer);
        if (offset < 0 || length < 0 || length > buffer.length - offset) {
            throw new IndexOutOfBoundsException("Invalid spool read bounds");
        }
        if (length == 0) {
            return 0;
        }
        while (true) {
            int count = readAvailable(buffer, offset, length);
            if (count != 0) {
                return count;
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        file.close();
    }

    /**
     * @return the number of bytes read, {@code 0} when no byte is available yet, or {@code -1} at
     *     end of stream
     */
    private int readAvailable(byte[] buffer, int offset, int length) throws IOException {
        if (closed) {
            throw new IOException("The spool stream is closed");
        }
        long available = file.length() - file.getFilePointer();
        if (available > 0) {
            return file.read(buffer, offset, (int) Math.min(available, length));
        }
        if (liveness.hasExited()) {
            return file.length() - file.getFilePointer() > 0
                    ? file.read(buffer, offset, length)
                    : -1;
        }
        waitForOutput();
        return 0;
    }

    private void waitForOutput() throws IOException {
        if (pollMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(pollMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for root Syncthing output", e);
        }
    }
}
