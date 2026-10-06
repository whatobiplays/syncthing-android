package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.topjohnwu.superuser.NoShellException;
import com.topjohnwu.superuser.Shell;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Covers the deterministic classification helpers of the libsu factory.
 *
 * <p>The test uses the numeric shell statuses that libsu defines: {@code NON_ROOT_SHELL} is
 * {@code 0}, {@code ROOT_SHELL} is {@code 1}, and an unverified shell reports {@code -1}.</p>
 */
public class LibsuRootShellFactoryTest {
    private static final int NON_ROOT_SHELL = 0;
    private static final int ROOT_SHELL = 1;
    private static final int UNKNOWN_SHELL = -1;

    @Test
    public void internalTimeoutStaysBelowTheActivationDeadline() {
        long timeout = LibsuRootShellFactory.internalShellTimeoutSeconds(60_000);

        assertEquals(40, timeout);
        assertTrue(
                "libsu shell timeout plus margin must fit inside the activation deadline",
                timeout * 1000 + LibsuRootShellFactory.INTERNAL_TIMEOUT_MARGIN_MILLIS <= 60_000);
    }

    @Test
    public void internalTimeoutIsAtLeastOneSecondForShortDeadlines() {
        assertEquals(1, LibsuRootShellFactory.internalShellTimeoutSeconds(1));
        assertEquals(1, LibsuRootShellFactory.internalShellTimeoutSeconds(10_000));
        assertEquals(3, LibsuRootShellFactory.internalShellTimeoutSeconds(23_000));
    }

    @Test
    public void classifiesLibsuTimeoutAsActivationTimeout() {
        RuntimeException failure = LibsuRootShellFactory.classifyBuildFailure(
                "Shell check timeout Unable to create a shell!");

        assertTrue(failure instanceof RootTransportException);
        assertEquals(
                RootFailure.ROOT_ACTIVATION_TIMEOUT,
                ((RootTransportException) failure).failure()
        );
    }

    @Test
    public void classifiesTerminatedTransportAsDenied() {
        assertEquals(
                RootFailure.ROOT_DENIED,
                failureOf(LibsuRootShellFactory.classifyBuildFailure(
                        "Created process has terminated Unable to create a shell! su: access denied"))
        );
    }

    @Test
    public void classifiesPermissionErrorsAsDenied() {
        assertEquals(
                RootFailure.ROOT_DENIED,
                failureOf(LibsuRootShellFactory.classifyBuildFailure(
                        "su: permission denied"))
        );
        assertEquals(
                RootFailure.ROOT_DENIED,
                failureOf(LibsuRootShellFactory.classifyBuildFailure(
                        "Created process is not a shell"))
        );
    }

    @Test
    public void classifiesUnknownBuildFailuresAsTransportFailures() {
        assertEquals(
                RootFailure.ROOT_TRANSPORT_FAILED,
                failureOf(LibsuRootShellFactory.classifyBuildFailure("something unexpected"))
        );
        assertEquals(
                RootFailure.ROOT_TRANSPORT_FAILED,
                failureOf(LibsuRootShellFactory.classifyBuildFailure(null))
        );
    }

    @Test
    public void acceptsAVerifiedRootShell() {
        LibsuRootShellFactory.requireRootShellStatus(ROOT_SHELL, ROOT_SHELL, NON_ROOT_SHELL);
    }

    @Test
    public void rejectsTheNonRootFallback() {
        try {
            LibsuRootShellFactory.requireRootShellStatus(NON_ROOT_SHELL, ROOT_SHELL, NON_ROOT_SHELL);
            fail("Expected the non-root fallback to be rejected");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.ROOT_DENIED, expected.failure());
            assertTrue(expected.getMessage().contains("non-root"));
        }
    }

    @Test
    public void rejectsAnUnverifiedShellStatus() {
        try {
            LibsuRootShellFactory.requireRootShellStatus(
                    UNKNOWN_SHELL, ROOT_SHELL, NON_ROOT_SHELL);
            fail("Expected the unverified shell status to be rejected");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.ROOT_DENIED, expected.failure());
        }
    }

    @Test
    public void acceptsOnlyUserZero() {
        LibsuRootShellFactory.requireRootUser("0");

        try {
            LibsuRootShellFactory.requireRootUser("10134");
            fail("Expected a non-zero user id to be rejected");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.UID_VERIFICATION_FAILED, expected.failure());
        }

        try {
            LibsuRootShellFactory.requireRootUser("");
            fail("Expected an empty user id to be rejected");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.UID_VERIFICATION_FAILED, expected.failure());
        }
    }

    private static RootFailure failureOf(RuntimeException failure) {
        assertTrue(failure instanceof RootTransportException);
        return ((RootTransportException) failure).failure();
    }

    @Test
    public void statusRejectionTearsDownThroughTheTransportWrapper() {
        VerificationFailingShell shell = new VerificationFailingShell(NON_ROOT_SHELL, null, null);
        DestroyCountingProcess process = new DestroyCountingProcess();

        try {
            acquireOverTransport(process, shell);
            fail("Expected the non-root fallback to be rejected");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.ROOT_DENIED, expected.failure());
        }

        assertTrue("the rejected shell must attempt the libsu close", shell.closeAttempted());
        assertEquals(
                "a failed libsu close must destroy the transport process exactly once",
                1,
                process.destroyCount()
        );
    }

    @Test
    public void uidVerificationFailureTearsDownThroughTheTransportWrapper() {
        VerificationFailingShell shell = new VerificationFailingShell(
                ROOT_SHELL, probeResult(Arrays.asList("10134"), 0), null);
        DestroyCountingProcess process = new DestroyCountingProcess();

        try {
            acquireOverTransport(process, shell);
            fail("Expected the non-zero user id to be rejected");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.UID_VERIFICATION_FAILED, expected.failure());
        }

        assertTrue("the rejected shell must attempt the libsu close", shell.closeAttempted());
        assertEquals(
                "a failed libsu close must destroy the transport process exactly once",
                1,
                process.destroyCount()
        );
    }

    @Test
    public void malformedUidProbeTearsDownThroughTheTransportWrapper() {
        VerificationFailingShell shell = new VerificationFailingShell(
                ROOT_SHELL, probeResult(Collections.<String>emptyList(), 0), null);
        DestroyCountingProcess process = new DestroyCountingProcess();

        try {
            acquireOverTransport(process, shell);
            fail("Expected the empty user id probe to fail verification");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.ROOT_TRANSPORT_FAILED, expected.failure());
        }

        assertTrue("the failed shell must attempt the libsu close", shell.closeAttempted());
        assertEquals(
                "a failed libsu close must destroy the transport process exactly once",
                1,
                process.destroyCount()
        );
    }

    @Test
    public void probeTransportFailureTearsDownThroughTheTransportWrapper() {
        VerificationFailingShell shell = new VerificationFailingShell(
                ROOT_SHELL, null, new IllegalStateException("the probe transport died"));
        DestroyCountingProcess process = new DestroyCountingProcess();

        try {
            acquireOverTransport(process, shell);
            fail("Expected the failed probe transport to be reported");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.ROOT_TRANSPORT_FAILED, expected.failure());
        }

        assertTrue("the failed shell must attempt the libsu close", shell.closeAttempted());
        assertEquals(
                "a failed libsu close must destroy the transport process exactly once",
                1,
                process.destroyCount()
        );
    }

    @Test
    public void teardownFailureNeverReplacesTheVerificationVerdict() {
        VerificationFailingShell shell = new VerificationFailingShell(NON_ROOT_SHELL, null, null);
        DestroyFailingProcess process = new DestroyFailingProcess();

        try {
            acquireOverTransport(process, shell);
            fail("Expected the non-root fallback to be rejected");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.ROOT_DENIED, expected.failure());
            assertTrue(
                    "the teardown failure must not replace the original typed failure",
                    expected.getMessage().contains("non-root")
            );
        }

        assertTrue("teardown must still run through the wrapper", shell.closeAttempted());
        assertEquals("the wrapper attempted the process teardown", 1, process.destroyCount());
    }

    @Test
    public void buildFailureDestroysTheRawTransportProcessWithoutAWrapper() {
        DestroyCountingProcess process = new DestroyCountingProcess();

        try {
            new LibsuRootShellFactory().acquire(
                    60_000,
                    process,
                    (transportProcess, timeoutSeconds) -> {
                        throw new NoShellException("Created process has terminated");
                    }
            );
            fail("Expected the terminated build to be classified as denied");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.ROOT_DENIED, expected.failure());
        }

        assertEquals(
                "a build failure before wrapper construction destroys the raw process",
                1,
                process.destroyCount()
        );
    }

    @Test
    public void uncheckedBuildFailureDestroysTheRawTransportProcessWithoutAWrapper() {
        DestroyCountingProcess process = new DestroyCountingProcess();

        try {
            new LibsuRootShellFactory().acquire(
                    60_000,
                    process,
                    (transportProcess, timeoutSeconds) -> {
                        throw new IllegalStateException("libsu cannot build a shell");
                    }
            );
            fail("Expected the unchecked build failure to be reported as a transport failure");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.ROOT_TRANSPORT_FAILED, expected.failure());
        }

        assertEquals(
                "an unchecked build failure before wrapper construction destroys the raw process",
                1,
                process.destroyCount()
        );
    }

    @Test
    public void missingShellResultDestroysTheRawTransportProcessWithoutAWrapper() {
        DestroyCountingProcess process = new DestroyCountingProcess();

        try {
            new LibsuRootShellFactory().acquire(
                    60_000,
                    process,
                    (transportProcess, timeoutSeconds) -> null
            );
            fail("Expected the missing shell to be reported as a transport failure");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.ROOT_TRANSPORT_FAILED, expected.failure());
        }

        assertEquals(
                "a missing shell before wrapper construction destroys the raw process",
                1,
                process.destroyCount()
        );
    }

    private static RootShell acquireOverTransport(Process process, Shell shell) {
        return new LibsuRootShellFactory().acquire(
                60_000,
                process,
                (transportProcess, timeoutSeconds) -> shell
        );
    }

    private static Shell.Result probeResult(List<String> stdout, int code) {
        return new Shell.Result() {
            @Override
            public List<String> getOut() {
                return stdout;
            }

            @Override
            public List<String> getErr() {
                return Collections.emptyList();
            }

            @Override
            public int getCode() {
                return code;
            }
        };
    }

    /**
     * A libsu shell double that reports a scripted verification state and whose raw close always
     * fails, so only the transport-level process teardown can release the privileged process.
     */
    private static final class VerificationFailingShell extends Shell {
        private final int status;
        private final Shell.Result probe;
        private final RuntimeException probeFailure;
        private final AtomicBoolean closeAttempted = new AtomicBoolean();

        VerificationFailingShell(int status, Shell.Result probe, RuntimeException probeFailure) {
            this.status = status;
            this.probe = probe;
            this.probeFailure = probeFailure;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public void execTask(Task task) {
            throw new AssertionError("Verification never runs raw terminal tasks");
        }

        @Override
        public void submitTask(Task task) {
            throw new AssertionError("Verification never submits raw tasks");
        }

        @Override
        public Job newJob() {
            return new ProbeJob();
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public boolean waitAndClose(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void close() throws IOException {
            closeAttempted.set(true);
            throw new IOException("libsu cannot flush the exit command");
        }

        boolean closeAttempted() {
            return closeAttempted.get();
        }

        private final class ProbeJob extends Job {
            private List<String> capturedStdout;

            @Override
            public Job to(List<String> stdout) {
                capturedStdout = stdout;
                return this;
            }

            @Override
            public Job to(List<String> stdout, List<String> stderr) {
                capturedStdout = stdout;
                return this;
            }

            @Override
            public Job add(String... commands) {
                return this;
            }

            @Override
            public Job add(InputStream in) {
                return this;
            }

            @Override
            public Result exec() {
                if (probeFailure != null) {
                    throw probeFailure;
                }
                if (probe != null) {
                    capturedStdout.addAll(probe.getOut());
                }
                return probe;
            }

            @Override
            public void submit(Executor executor, ResultCallback callback) {
                throw new AssertionError("Verification runs its probe synchronously");
            }

            @Override
            public Future<Result> enqueue() {
                throw new AssertionError("Verification never enqueues probes");
            }
        }
    }

    /** A transport process double that records every destroy call. */
    private static class DestroyCountingProcess extends Process {
        private final AtomicInteger destroyCount = new AtomicInteger();

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyCount.incrementAndGet();
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        int destroyCount() {
            return destroyCount.get();
        }
    }

    /** A transport process double whose destroy fails as well. */
    private static final class DestroyFailingProcess extends DestroyCountingProcess {
        @Override
        public void destroy() {
            super.destroy();
            throw new IllegalStateException("the transport process cannot be destroyed");
        }
    }
}
