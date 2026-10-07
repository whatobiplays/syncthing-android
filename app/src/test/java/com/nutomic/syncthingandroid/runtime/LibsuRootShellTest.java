package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.topjohnwu.superuser.Shell;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class LibsuRootShellTest {
    @Test
    public void parsesPidExecutableAndStartTime() {
        List<RootShell.ProcessEntry> entries = LibsuRootShell.parseProcessList(lines(
                LibsuRootShell.PID_MARKER + "41",
                "/data/app/lib/libsyncthingnative.so",
                LibsuRootShell.STAT_MARKER,
                statLine(41, "syncthing", 9001)
        ));

        assertEquals(1, entries.size());
        assertEquals(41, entries.get(0).pid());
        assertEquals("/data/app/lib/libsyncthingnative.so", entries.get(0).executablePath());
        assertEquals(9001, entries.get(0).processStartTimeTicks());
    }

    @Test
    public void reportsAnUnreadableExecutableAsNull() {
        List<RootShell.ProcessEntry> entries = LibsuRootShell.parseProcessList(lines(
                LibsuRootShell.PID_MARKER + "7",
                "",
                LibsuRootShell.STAT_MARKER,
                statLine(7, "kthreadd", 120)
        ));

        assertEquals(1, entries.size());
        assertNull(entries.get(0).executablePath());
        assertEquals(120, entries.get(0).processStartTimeTicks());
    }

    @Test
    public void reportsAMissingStatLineAsZeroStartTime() {
        List<RootShell.ProcessEntry> entries = LibsuRootShell.parseProcessList(lines(
                LibsuRootShell.PID_MARKER + "9",
                "/data/app/lib/libsyncthingnative.so",
                LibsuRootShell.STAT_MARKER
        ));

        assertEquals(1, entries.size());
        assertEquals(0, entries.get(0).processStartTimeTicks());
    }

    @Test
    public void startTimeUsesTheLastClosingParenthesis() {
        assertEquals(9001, LibsuRootShell.parseStartTimeTicks(
                statLine(41, "we)ird (name)", 9001)));
        assertEquals(0, LibsuRootShell.parseStartTimeTicks("not a stat line"));
        assertEquals(0, LibsuRootShell.parseStartTimeTicks(null));
    }

    @Test
    public void findsTheRunTokenInTheNulSeparatedEnvironment() {
        assertEquals("token-a", LibsuRootShell.parseRunToken(Arrays.asList(
                "HOME=/data/user/0/app",
                LibsuRootShell.RUN_TOKEN_ENVIRONMENT + "=token-a",
                "PATH=/system/bin"
        )));
        assertNull(LibsuRootShell.parseRunToken(Arrays.asList("HOME=/data/user/0/app")));
    }

    @Test
    public void runTokenScriptReadsTheProcessEnvironment() {
        assertEquals(
                "tr '\\0' '\\n' < /proc/41/environ 2>/dev/null",
                LibsuRootShell.runTokenScript(41)
        );
    }

    @Test
    public void processListScriptHasNoMountMasterAndListsProcEntries() {
        String script = LibsuRootShell.processListScript();

        assertTrue(script.contains("for standroid_proc in /proc/[0-9]*"));
        assertTrue(script.contains("readlink \"$standroid_proc/exe\""));
        assertTrue(script.contains("cat \"$standroid_proc/stat\""));
        assertFalse(script.contains("--mount-master"));
    }

    private static List<String> lines(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static String statLine(int pid, String name, long startTicks) {
        return pid + " (" + name + ") R 1 " + pid + " " + pid + " 0 -1 4194624 100 0 0 0 1 2 0 0"
                + " 20 0 3 0 " + startTicks + " 12345";
    }

    @Test
    public void missingStatLineDoesNotConsumeTheFollowingEntry() {
        List<RootShell.ProcessEntry> entries = LibsuRootShell.parseProcessList(lines(
                LibsuRootShell.PID_MARKER + "4001",
                "/data/app/lib/libsyncthingnative.so",
                LibsuRootShell.STAT_MARKER,
                LibsuRootShell.PID_MARKER + "4002",
                "/data/app/lib/libsyncthingnative.so",
                LibsuRootShell.STAT_MARKER,
                statLine(4002, "syncthing", 9500)
        ));

        assertEquals("both entries must be reported", 2, entries.size());
        assertEquals(4001, entries.get(0).pid());
        assertEquals(
                "the entry whose stat line could not be read reports an unknown start time",
                0,
                entries.get(0).processStartTimeTicks()
        );
        assertEquals(4002, entries.get(1).pid());
        assertEquals(
                "/data/app/lib/libsyncthingnative.so",
                entries.get(1).executablePath()
        );
        assertEquals(
                "the following bundled candidate must keep its real start time",
                9500,
                entries.get(1).processStartTimeTicks()
        );
    }

    @Test
    public void timedOutHelperOperationClosesThroughTheTransportFallback() throws Exception {
        FailingCloseShell shell = new FailingCloseShell();
        DestroyRecordingProcess process = new DestroyRecordingProcess();
        LibsuRootShell transport = new LibsuRootShell(shell, process, 50);

        try {
            transport.currentUid();
            fail("Expected the helper operation to time out");
        } catch (IOException expected) {
            assertTrue(
                    "the timeout has to surface unchanged",
                    expected.getMessage().contains("timed out")
            );
        }

        assertTrue("the stalled helper job is cancelled", shell.awaitJobInterrupted());
        assertTrue("the libsu shell is asked to close first", shell.closeAttempted());
        assertEquals(
                "a shell that libsu cannot close has its transport process destroyed",
                1,
                process.destroyCount()
        );
    }

    @Test
    public void interruptedHelperOperationClosesThroughTheTransportFallback() throws Exception {
        FailingCloseShell shell = new FailingCloseShell();
        DestroyRecordingProcess process = new DestroyRecordingProcess();
        LibsuRootShell transport = new LibsuRootShell(shell, process);
        AtomicReference<IOException> failure = new AtomicReference<>();
        AtomicBoolean interruptStatus = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                transport.currentUid();
            } catch (IOException e) {
                failure.set(e);
                interruptStatus.set(Thread.currentThread().isInterrupted());
            }
        }, "interrupted-helper-caller");

        caller.start();
        assertTrue("the helper job has to be running", shell.awaitJobStarted());
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse("the caller has to finish", caller.isAlive());

        assertNotNull("the interruption has to reach the caller", failure.get());
        assertTrue(
                "the interruption has to surface unchanged",
                failure.get().getMessage().contains("interrupted")
        );
        assertTrue("the caller's interrupt status is restored", interruptStatus.get());
        assertTrue("the stalled helper job is cancelled", shell.awaitJobInterrupted());
        assertTrue(shell.closeAttempted());
        assertEquals(
                "a shell that libsu cannot close has its transport process destroyed",
                1,
                process.destroyCount()
        );
    }

    @Test
    public void teardownFailureNeverMasksTheTimedOutHelperOperation() throws Exception {
        FailingCloseShell shell = new FailingCloseShell();
        LibsuRootShell transport = new LibsuRootShell(shell, new DestroyFailingProcess(), 50);

        try {
            transport.currentUid();
            fail("Expected the helper operation to time out");
        } catch (IOException expected) {
            assertTrue(
                    "a failed teardown must not replace the timeout",
                    expected.getMessage().contains("timed out")
            );
        }

        assertTrue(shell.closeAttempted());
    }

    @Test
    public void uncheckedTeardownFailureStillDestroysTheTransportProcess() throws Exception {
        FailingCloseShell shell = new FailingCloseShell(
                new IllegalStateException("libsu cannot close a shell in this state"));
        DestroyRecordingProcess process = new DestroyRecordingProcess();
        LibsuRootShell transport = new LibsuRootShell(shell, process, 50);

        try {
            transport.currentUid();
            fail("Expected the helper operation to time out");
        } catch (IOException expected) {
            assertTrue(
                    "an unchecked teardown failure must not replace the timeout",
                    expected.getMessage().contains("timed out")
            );
        }

        assertTrue(shell.closeAttempted());
        assertEquals(
                "an unchecked close failure still destroys the transport process",
                1,
                process.destroyCount()
        );
    }

    @Test
    public void processListScriptCannotFailBecauseTheLastProcEntryVanished() {
        String script = LibsuRootShell.processListScript();

        assertTrue(
                "process enumeration must force a successful aggregate shell status after the"
                        + " per-process best-effort reads",
                script.trim().endsWith("true")
        );
    }

    /**
     * A libsu shell double whose close always fails, either with the declared I/O failure or
     * with an unchecked state error, and whose jobs block until they are cancelled, so the
     * deterministic replacement is the transport-level process teardown.
     */
    private static class FailingCloseShell extends Shell {
        private final CountDownLatch jobStarted = new CountDownLatch(1);
        private final CountDownLatch jobInterrupted = new CountDownLatch(1);
        private final AtomicBoolean closeAttempted = new AtomicBoolean();
        private final RuntimeException uncheckedFailure;

        FailingCloseShell() {
            this(null);
        }

        FailingCloseShell(RuntimeException uncheckedFailure) {
            this.uncheckedFailure = uncheckedFailure;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public void execTask(Task task) {
            throw new AssertionError("Helper operations never use raw terminal tasks");
        }

        @Override
        public void submitTask(Task task) {
            throw new AssertionError("Helper operations never submit raw tasks");
        }

        @Override
        public Job newJob() {
            return new BlockingJob();
        }

        @Override
        public int getStatus() {
            return ROOT_SHELL;
        }

        @Override
        public boolean waitAndClose(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void close() throws IOException {
            closeAttempted.set(true);
            if (uncheckedFailure != null) {
                throw uncheckedFailure;
            }
            throw new IOException("libsu cannot flush the exit command");
        }

        private boolean awaitJobStarted() throws InterruptedException {
            return jobStarted.await(5, TimeUnit.SECONDS);
        }

        private boolean awaitJobInterrupted() throws InterruptedException {
            return jobInterrupted.await(5, TimeUnit.SECONDS);
        }

        private boolean closeAttempted() {
            return closeAttempted.get();
        }

        private final class BlockingJob extends Job {
            @Override
            public Job to(List<String> stdout) {
                return this;
            }

            @Override
            public Job to(List<String> stdout, List<String> stderr) {
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
                jobStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException cancelled) {
                    jobInterrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return null;
            }

            @Override
            public void submit(Executor executor, ResultCallback callback) {
                throw new AssertionError("Helper operations run their job synchronously");
            }

            @Override
            public Future<Result> enqueue() {
                throw new AssertionError("Helper operations never enqueue jobs");
            }
        }
    }

    /** A transport process double that records every destroy call. */
    private static class DestroyRecordingProcess extends Process {
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

        private int destroyCount() {
            return destroyCount.get();
        }
    }

    /** A transport process double whose teardown fails as well. */
    private static final class DestroyFailingProcess extends DestroyRecordingProcess {
        @Override
        public void destroy() {
            super.destroy();
            throw new IllegalStateException("the transport process cannot be destroyed");
        }
    }

    @Test
    public void parsesTheParentProcessIdentifierOfAStatLine() {
        assertEquals(
                4242,
                LibsuRootShell.parseParentProcessId(ScriptedLibsuShell.ATTACHED_STAT_LINE)
        );
        assertEquals(
                "the executable name may contain spaces and parentheses",
                4242,
                LibsuRootShell.parseParentProcessId("77 (we)ird (name) S 4242 77 77")
        );
    }

    @Test
    public void reportsAnUnprovableParentProcessIdentifierAsZero() {
        assertEquals(0, LibsuRootShell.parseParentProcessId(null));
        assertEquals(0, LibsuRootShell.parseParentProcessId("not a stat line"));
        assertEquals(0, LibsuRootShell.parseParentProcessId("77 (sh) S"));
        assertEquals(0, LibsuRootShell.parseParentProcessId("77 (sh) S zero 77 77"));
        assertEquals(0, LibsuRootShell.parseParentProcessId("77 (sh) S 0 77 77"));
    }

    @Test
    public void provenanceIsProvenOnlyForAShellThatIsTheClientItself() throws Exception {
        LibsuRootShell attached = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                1_000
        );
        attached.determineExitStatusProvenance(ScriptedLibsuShell.OWNER_PROCESS_ID);

        assertTrue(
                "a client that the shell replaced owns every status the shell reports",
                attached.exitStatusBelongsToLaunchedProcess()
        );

        LibsuRootShell detached = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.DETACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                1_000
        );
        detached.determineExitStatusProvenance(ScriptedLibsuShell.OWNER_PROCESS_ID);

        assertFalse(
                "a shell that outlived its client never owns the status that client reports",
                detached.exitStatusBelongsToLaunchedProcess()
        );
    }

    @Test
    public void unreadableProvenanceStaysUnprovableInsteadOfFailingTheShell() throws Exception {
        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(null, new IllegalStateException("the transport died")),
                new DestroyRecordingProcess(),
                1_000
        );

        transport.determineExitStatusProvenance(ScriptedLibsuShell.OWNER_PROCESS_ID);

        assertFalse(transport.exitStatusBelongsToLaunchedProcess());
    }

    @Test
    public void provenanceIsRecordedOnceWhileTheClientIsStillAlive() throws Exception {
        ScriptedLibsuShell shell = new ScriptedLibsuShell(
                ScriptedLibsuShell.DETACHED_STAT_LINE, null
        );
        LibsuRootShell transport = new LibsuRootShell(shell, new DestroyRecordingProcess(), 1_000);

        transport.determineExitStatusProvenance(ScriptedLibsuShell.OWNER_PROCESS_ID);
        transport.determineExitStatusProvenance(ScriptedLibsuShell.OWNER_PROCESS_ID);

        assertFalse(transport.exitStatusBelongsToLaunchedProcess());
        assertEquals("the shell is asked exactly once", 1, shell.statReads());
    }
}
