package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

public class AppUidBackendTest {
    @Test
    public void successfulOneShotCanUseOutputWhenChildExitedBeforeIdentityCapture()
            throws Exception {
        FakeProcess process = new FakeProcess("device-id-output\n");
        process.exit(0);
        File binary = File.createTempFile("syncthing", ".bin");
        try {
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(
                    backend(binary, process)
            );

            SyncthingExecution execution = runtime.start(
                    SyncthingCommand.DEVICE_ID, environment()
            );

            assertEquals(0, execution.await());
            execution.requireIdentityForSuccessfulOneShotResult();
            assertEquals("device-id-output\n", readAll(execution.stdout()));
        } finally {
            assertTrue(binary.delete());
        }
    }

    @Test
    public void identityFailureForLiveChildRejectsOutputAndRetainsAdmissionUntilExit()
            throws Exception {
        FakeProcess process = new FakeProcess("untrusted-output\n");
        File binary = File.createTempFile("syncthing", ".bin");
        try {
            DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(
                    backend(binary, process)
            );
            SyncthingExecution execution = runtime.start(
                    SyncthingCommand.DEVICE_ID, environment()
            );

            assertThrows(
                    ExecutionAdmissionException.class,
                    () -> runtime.start(SyncthingCommand.GENERATE, environment())
            );
            process.exit(0);

            assertEquals(0, execution.await());
            assertThrows(
                    ExecutionIdentityUnavailableException.class,
                    execution::requireIdentityForSuccessfulOneShotResult
            );
            SyncthingExecution afterExit = runtime.start(
                    SyncthingCommand.GENERATE, environment()
            );
            assertEquals(0, afterExit.await());
        } finally {
            process.exit(0);
            assertTrue(binary.delete());
        }
    }

    private static AppUidBackend backend(File binary, FakeProcess process) {
        ExecutionRecordStore records = new ExecutionRecordStore() {
            @Override
            public ReadResult read() {
                return ReadResult.missing();
            }

            @Override
            public void write(ExecutionIdentity identity) {
                throw new AssertionError("No launched identity should be recorded");
            }

            @Override
            public boolean deleteIfRunTokenMatches(String runToken) {
                return false;
            }
        };
        ExecutionInspector inspector = new ExecutionInspector() {
            @Override
            public String currentBootId() {
                return "boot-a";
            }

            @Override
            public InspectionResult inspect(int pid) {
                return InspectionResult.unknown();
            }

            @Override
            public List<ExecutionIdentity> findBundledCandidates(String executablePath) {
                return Collections.emptyList();
            }

            @Override
            public ExecutionIdentity findLaunchedProcess(String executablePath, String runToken) {
                return null;
            }
        };
        ExecutionOwnershipManager ownership = new ExecutionOwnershipManager(
                binary.getAbsolutePath(), records, inspector,
                (pid, signal) -> ExecutionOwnershipManager.SignalResult.SIGNAL_FAILED
        );
        AppUidProcessLauncher launcher = (argv, environment) -> process;
        ConfigStorage storage = new ConfigStorage() {
            @Override
            public boolean canRead() {
                return false;
            }

            @Override
            public byte[] load() throws IOException {
                throw new IOException("No test config");
            }

            @Override
            public boolean canWrite() {
                return false;
            }

            @Override
            public void save(byte[] contents) throws IOException {
                throw new IOException("No test config");
            }
        };
        return new AppUidBackend(binary, launcher, ownership, storage);
    }

    private static SyncthingEnvironment environment() {
        return SyncthingEnvironment.builder()
                .home("/home")
                .syncthingHome("/state")
                .trace("")
                .monitored()
                .noUpgrade()
                .versionExtra("app")
                .sqliteTemporaryDirectory("/tmp")
                .gogc(100)
                .build();
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[128];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class FakeProcess extends Process {
        private final ByteArrayInputStream stdout;
        private final CountDownLatch exited = new CountDownLatch(1);
        private volatile Integer exitCode;

        private FakeProcess(String output) {
            stdout = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        }

        private void exit(int code) {
            exitCode = code;
            exited.countDown();
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return exitCode;
        }

        @Override
        public int exitValue() {
            if (exitCode == null) throw new IllegalThreadStateException("still running");
            return exitCode;
        }

        @Override
        public void destroy() {
            exit(137);
        }
    }
}
