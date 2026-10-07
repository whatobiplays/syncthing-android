package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.service.SyncthingLogFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class ExecutionOutputLogTest {
    @Test
    public void trimmingWhileExecutionIsLoggingDoesNotDetachTheActiveWriter() throws Exception {
        File root = temporaryDirectory();
        try {
            File log = new File(root, "syncthing.log");
            try (FileOutputStream seed = new FileOutputStream(log)) {
                byte[] line = "x\n".getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < SyncthingLogFile.LOG_FILE_MAX_LINES + 2; i++) {
                    seed.write(line);
                }
            }

            PipedInputStream stdout = new PipedInputStream();
            PipedOutputStream producer = new PipedOutputStream(stdout);
            SyncthingExecution execution = new SyncthingExecution(
                    new StreamingExecution(stdout),
                    () -> { }
            );
            AtomicReference<IOException> failure = new AtomicReference<>();
            Thread worker = execution.streamOutput(log, failure::set);

            producer.write("before-rotation\n".getBytes(StandardCharsets.UTF_8));
            producer.flush();
            awaitContains(log, "before-rotation");

            SyncthingLogFile.trim(log, root);

            producer.write("after-rotation\n".getBytes(StandardCharsets.UTF_8));
            producer.flush();
            producer.close();
            worker.join(5_000);

            assertNull(failure.get());
            assertTrue(
                    "output written after rotation must remain visible through the log pathname",
                    readText(log).contains("after-rotation")
            );
        } finally {
            RootRunSpool.deleteDirectory(root);
        }
    }

    private static void awaitContains(File file, String value) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (file.exists() && readText(file).contains(value)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for log output: " + value);
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static File temporaryDirectory() throws IOException {
        File directory = File.createTempFile("execution-output-log", "");
        if (!directory.delete() || !directory.mkdir()) {
            throw new IOException("Could not prepare a temporary directory");
        }
        return directory;
    }

    private static final class StreamingExecution implements PrivilegeBackend.Execution {
        private final InputStream stdout;

        private StreamingExecution(InputStream stdout) {
            this.stdout = stdout;
        }

        @Override
        public InputStream stdout() {
            return stdout;
        }

        @Override
        public InputStream stderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int await() {
            return 0;
        }

        @Override
        public void destroy() {
        }
    }
}
