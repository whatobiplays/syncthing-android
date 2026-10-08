package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.topjohnwu.superuser.Shell;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        LibsuRootShell transport = new LibsuRootShell(
                shell, process, ManagedStateTestSupport.locations(), 50
        );

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
        LibsuRootShell transport = new LibsuRootShell(
                shell, process, ManagedStateTestSupport.locations()
        );
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
    public void managedStateJobsSuperviseTheirProcessGroupOnTimeout() throws Exception {
        ScriptedLibsuShell shell = new ScriptedLibsuShell(
                ScriptedLibsuShell.ATTACHED_STAT_LINE, null
        );
        LibsuRootShell transport = new LibsuRootShell(
                shell,
                new DestroyRecordingProcess(),
                ManagedStateTestSupport.locations(),
                1_000
        );

        assertTrue(transport.stateMemberExists(ManagedStateMember.CONFIG));

        String command = shell.executedCommands().get(0);
        assertTrue("state scripts need a bounded process-group owner", command.contains("timeout -k "));
        assertTrue("the shell supervisor must receive the script over stdin",
                command.contains(" sh <<'STANDROID_TIMEOUT_"));
        assertTrue("the timeout supervisor must terminate its whole process group",
                command.contains("kill -TERM 0") && command.contains("kill -KILL 0"));
        assertFalse("large state scripts must not be passed as one sh -c argument",
                command.contains("sh -c "));
    }

    @Test
    public void boundedStateCommandKeepsLargeScriptsOutOfShellArguments() {
        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                ManagedStateTestSupport.locations(),
                1_000
        );
        String largeBody = "x".repeat(200_000);

        String command = transport.boundedStateCommand(
                "printf '%s' " + RootShellEncoder.quote(largeBody)
        );

        assertTrue("the regression fixture exceeds a typical exec argument limit",
                command.length() > 128 * 1024);
        assertTrue("the state script is supplied through a here-document",
                command.contains(" sh <<'STANDROID_TIMEOUT_"));
        assertFalse("the complete script must never occupy one sh -c argument",
                command.contains("sh -c "));
    }

    @Test
    public void timedOutStateScriptCannotWriteAfterTheRootTransportReturns() throws Exception {
        Path root = Files.createTempDirectory("root-managed-state-timeout-");
        File tools = root.resolve("tools").toFile();
        File marker = root.resolve("late-write").toFile();
        assertTrue(tools.mkdir());
        writeExecutable(new File(tools, "timeout"),
                "#!/usr/bin/env python3\n"
                        + "import os, signal, subprocess, sys, time\n"
                        + "args = sys.argv[1:]\n"
                        + "if len(args) < 4 or args[0] != '-k': sys.exit(125)\n"
                        + "grace, duration = float(args[1]), float(args[2])\n"
                        + "script = sys.stdin.buffer.read()\n"
                        + "child = subprocess.Popen(args[3:], stdin=subprocess.PIPE, start_new_session=True)\n"
                        + "child.stdin.write(script)\n"
                        + "child.stdin.close()\n"
                        + "try:\n"
                        + "    child.wait(timeout=duration)\n"
                        + "except subprocess.TimeoutExpired:\n"
                        + "    try: os.killpg(child.pid, signal.SIGTERM)\n"
                        + "    except ProcessLookupError: pass\n"
                        + "    time.sleep(grace)\n"
                        + "    if child.poll() is None:\n"
                        + "        try: os.killpg(child.pid, signal.SIGKILL)\n"
                        + "        except ProcessLookupError: pass\n"
                        + "    child.wait()\n"
                        + "    sys.exit(124)\n"
                        + "sys.exit(child.returncode)\n");
        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                ManagedStateTestSupport.locations(),
                1_000
        );
        String delayedWrite = "import os,signal,time\n"
                + "def on_term(*args):\n"
                + " time.sleep(0.25)\n"
                + " open(os.environ['MARKER'],'w').write('late')\n"
                + "signal.signal(signal.SIGTERM,on_term)\n"
                + "time.sleep(10)\n";
        String script = transport.boundedStateCommand(
                "python3 -c " + RootShellEncoder.quote(delayedWrite)
        );
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", script)
                .redirectErrorStream(true);
        builder.environment().put(
                "PATH",
                tools.getAbsolutePath() + File.pathSeparator + System.getenv("PATH")
        );
        builder.environment().put("MARKER", marker.getAbsolutePath());
        Process process = builder.start();
        assertTrue("the timed operation must return inside the outer bound",
                process.waitFor(5, TimeUnit.SECONDS));
        assertEquals("the timeout must report a bounded failure", 124, process.exitValue());
        Thread.sleep(400);
        assertFalse("no child may mutate state after timeout completion", marker.exists());
    }

    @Test
    public void teardownFailureNeverMasksTheTimedOutHelperOperation() throws Exception {
        FailingCloseShell shell = new FailingCloseShell();
        LibsuRootShell transport = new LibsuRootShell(
                shell, new DestroyFailingProcess(), ManagedStateTestSupport.locations(), 50
        );

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
        LibsuRootShell transport = new LibsuRootShell(
                shell, process, ManagedStateTestSupport.locations(), 50
        );

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
    public void stateByteTransportPreservesBinaryContentAndLineEndings() throws Exception {
        byte[] content = new byte[] {
                0x00, 0x0d, 0x0a, 0x3c, (byte) 0xff, 0x00, 0x7f, 0x2d, 0x5a, 0x0a
        };
        String encoded = LibsuRootShell.encodeStateBase64(content);
        int split = encoded.length() / 2;

        assertArrayEquals(
                content,
                LibsuRootShell.decodeStateBase64(Arrays.asList(
                        " \t" + encoded.substring(0, split),
                        encoded.substring(split) + "\r\n"
                ))
        );
        assertArrayEquals(
                new byte[0],
                LibsuRootShell.decodeStateBase64(Arrays.asList("", " \t\r\n"))
        );
    }

    @Test
    public void malformedStateByteTransportFailsInsteadOfReturningPartialContent() {
        for (String malformed : Arrays.asList(
                "YWJj!", "YQ", "YQ=", "YQ===", "Y=Q=", "YQ=A", "YR=="
        )) {
            try {
                LibsuRootShell.decodeStateBase64(Arrays.asList(malformed));
                fail("malformed root output must fail closed: " + malformed);
            } catch (IOException expected) {
                // The transport never treats malformed output as partial file content.
            }
        }
    }

    @Test
    public void stateReadFailsWhenBase64CannotReadTheManagedStateFile() throws Exception {
        ScriptedLibsuShell shell = new ScriptedLibsuShell(
                ScriptedLibsuShell.ATTACHED_STAT_LINE, null
        ).failStateReadCommand();
        LibsuRootShell transport = new LibsuRootShell(
                shell, new DestroyRecordingProcess(), ManagedStateTestSupport.locations(), 1_000
        );

        try {
            transport.readStateFile(ManagedStateMember.CONFIG);
            fail("a failed base64 read must not become an empty state file");
        } catch (IOException expected) {
            // The helper status must carry the base64 failure back to the caller.
        }
    }

    @Test
    public void stateJobThatLibsuCouldNotExecuteRetainsItsTypedTransportFailure() {
        ScriptedLibsuShell shell = new ScriptedLibsuShell(
                ScriptedLibsuShell.ATTACHED_STAT_LINE, null
        ).notExecuteStateCommand();
        LibsuRootShell transport = new LibsuRootShell(
                shell, new DestroyRecordingProcess(), ManagedStateTestSupport.locations(), 1_000
        );

        try {
            transport.readStateFile(ManagedStateMember.CONFIG);
            fail("an unexecuted root job must remain a typed transport failure");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.ROOT_TRANSPORT_FAILED, expected.failure());
        } catch (IOException wrongFailure) {
            fail("the transport cause must not be converted to an ordinary state I/O failure");
        }
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
    public void managedStateRepairAlwaysRetriesContextAndValidatesIndexBeforeRecursiveChanges() {
        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                ManagedStateTestSupport.locations(),
                1_000
        );

        String script = transport.repairManagedStateScript();

        int stateRootGuard = script.indexOf(
                "if [ -L \"$standroid_state_root\" ] || { [ -e \"$standroid_state_root\" ]"
        );
        int stateDirectoryGuard = script.indexOf(
                "if [ ! -d \"$standroid_state\" ]"
        );
        assertTrue(stateRootGuard >= 0);
        assertTrue(stateDirectoryGuard >= 0);
        assertTrue("the state root is validated before the member loop",
                stateRootGuard < script.indexOf("for standroid_member in "));
        assertTrue("repair requires an existing state directory before mutation",
                stateDirectoryGuard < script.indexOf("for standroid_member in "));
        assertTrue(script.contains("restorecon \"$standroid_target\""));
        assertTrue(script.contains("restorecon -R \"$standroid_index\""));
        assertFalse(
                "repair attempts context restoration on every retry, including when SELinux is not enforcing",
                script.contains("cat /sys/fs/selinux/enforce")
        );
        int unsafeIndexScan = script.indexOf("standroid_unsafe=$(find \"$standroid_index\"");
        int recursiveOwnership = script.indexOf("chown -R ");
        assertTrue("the whole index tree is checked before recursive ownership changes", unsafeIndexScan >= 0);
        assertTrue("unsafe index entries fail before ownership changes", unsafeIndexScan < recursiveOwnership);
        assertEquals("the generated shell program has balanced conditionals",
                script.split("\\bif\\b", -1).length,
                script.split("\\bfi\\b", -1).length);
    }

    @Test
    public void managedStateRepairRejectsASymlinkedStateRootBeforePrivilegedMutation()
            throws Exception {
        Path root = Files.createTempDirectory("root-managed-state-repair-symlink-");
        File outside = root.resolve("outside").toFile();
        File state = root.resolve("files").toFile();
        File tools = root.resolve("tools").toFile();
        File trace = root.resolve("mutations.log").toFile();
        byte[] externalConfig = new byte[] { 0x31, 0x32 };
        assertTrue(outside.mkdir());
        assertTrue(tools.mkdir());
        Files.write(new File(outside, "config.xml").toPath(), externalConfig);
        Files.createSymbolicLink(state.toPath(), outside.toPath());
        writeExecutable(new File(tools, "id"), "#!/bin/sh\nprintf '0\\n'\n");
        for (String command : Arrays.asList("chown", "chmod", "restorecon")) {
            writeExecutable(
                    new File(tools, command),
                    "#!/bin/sh\nprintf '" + command + "\\n' >> \"$TRACE\"\n"
            );
        }
        writeExecutable(new File(tools, "find"), "#!/bin/sh\nexit 0\n");

        ManagedStateLocations locations = new ManagedStateLocations(
                state, root.resolve("cache").toFile(), 10_000, 10_000
        );
        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                locations,
                1_000
        );
        ProcessBuilder builder = new ProcessBuilder(
                "/bin/sh", "-c", transport.repairManagedStateScript()
        ).redirectErrorStream(true);
        builder.environment().put("PATH",
                tools.getAbsolutePath() + File.pathSeparator + System.getenv("PATH"));
        builder.environment().put("TRACE", trace.getAbsolutePath());
        Process process = builder.start();
        assertTrue("the privileged command must finish promptly", process.waitFor(5, TimeUnit.SECONDS));

        assertEquals(LibsuRootShell.STATE_UNSAFE_TREE_EXIT_CODE, process.exitValue());
        assertFalse("no privileged mutation command ran through the symlink", trace.exists());
        assertArrayEquals(externalConfig, Files.readAllBytes(new File(outside, "config.xml").toPath()));
    }

    @Test
    public void everyManagedStateScriptRejectsASymlinkedStateRootBeforeExternalCommands()
            throws Exception {
        Path root = Files.createTempDirectory("root-managed-state-operation-symlink-");
        File outside = root.resolve("outside").toFile();
        File state = root.resolve("files").toFile();
        File base = root.resolve("cache/managed-state-transfer").toFile();
        File tools = root.resolve("tools").toFile();
        File trace = root.resolve("commands.log").toFile();
        byte[] externalConfig = new byte[] { 0x31, 0x32 };
        assertTrue(outside.mkdir());
        assertTrue(tools.mkdir());
        assertTrue(base.mkdirs());
        Files.write(new File(outside, "config.xml").toPath(), externalConfig);
        Files.createSymbolicLink(state.toPath(), outside.toPath());
        writeExecutable(new File(tools, "id"), "#!/bin/sh\nprintf '0\\n'\n");
        for (String command : Arrays.asList(
                "stat", "find", "head", "cp", "chown", "chmod", "restorecon",
                "base64", "mv", "rm", "mkdir"
        )) {
            writeExecutable(
                    new File(tools, command),
                    "#!/bin/sh\nprintf '" + command + "\\n' >> \"$TRACE\"\nexit 0\n"
            );
        }

        ManagedStateLocations locations = new ManagedStateLocations(state, base, 10_000, 10_000);
        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                locations,
                1_000
        );
        String operation = "op-00000000-0000-0000-0000-000000000000";
        List<String> scripts = Arrays.asList(
                transport.readStateFileScript(ManagedStateMember.CONFIG),
                transport.writeStateFileScript(ManagedStateMember.CONFIG, "YQ=="),
                transport.removeStateMemberScript(ManagedStateMember.CONFIG),
                transport.stateMemberExistsScript(ManagedStateMember.CONFIG),
                transport.stageManagedStateScript(operation),
                transport.installStagedManagedStateScript(operation),
                transport.repairManagedStateScript()
        );

        for (String script : scripts) {
            ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", script)
                    .redirectErrorStream(true);
            builder.environment().put("PATH",
                    tools.getAbsolutePath() + File.pathSeparator + System.getenv("PATH"));
            builder.environment().put("TRACE", trace.getAbsolutePath());
            Process process = builder.start();
            assertTrue("the privileged command must finish promptly",
                    process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(LibsuRootShell.STATE_UNSAFE_TREE_EXIT_CODE, process.exitValue());
            assertFalse("a symlinked root must be rejected before any external command",
                    trace.exists());
        }

        assertArrayEquals(externalConfig, Files.readAllBytes(new File(outside, "config.xml").toPath()));
    }

    @Test
    public void stagingScriptsNeverChangeOwnershipOfTheSharedStagingBase() {
        ManagedStateLocations locations = ManagedStateTestSupport.locations();
        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                locations,
                1_000
        );
        String operation = "op-00000000-0000-0000-0000-000000000000";

        String snapshot = transport.stageManagedStateScript(operation);
        String write = transport.writeStateFileScript(ManagedStateMember.CONFIG, "Y29uZmln");

        assertTrue(snapshot.contains(
                "standroid_find_empty \"$standroid_base\" -maxdepth 0"
        ));
        assertTrue("snapshot output is pinned to the checked operation directory",
                snapshot.contains("standroid_stage_physical=$(pwd -P)"));
        assertTrue("snapshot files are copied through relative destinations",
                snapshot.contains("cp \"$standroid_source\" \"./$standroid_member\""));
        assertFalse("snapshot never writes through the app-replaceable staging pathname",
                snapshot.contains("\"$standroid_stage/$standroid_member\""));
        assertFalse(snapshot.contains("chown " + locations.applicationUid() + ":"
                + locations.applicationGid() + " \"$standroid_base\""));
        assertFalse(write.contains("chown " + locations.applicationUid() + ":"
                + locations.applicationGid() + " \"$standroid_base\""));
        assertTrue("root writes use private scratch inside the selected state filesystem",
                write.contains("standroid_work_name='.standroid-managed-state-"));
        assertFalse("root writes never use a cross-filesystem temporary base",
                write.contains("/data/local/tmp"));
        assertTrue("the completed file is renamed within the state directory",
                write.contains("mv -f ./state \"../config.xml\""));
        assertTrue("only the completed file is handed to the application",
                write.contains("chown " + locations.applicationUid() + ":"
                        + locations.applicationGid() + " ./state"));
        assertTrue(write.contains(
                "[ -f \"../config.xml\" ] && [ ! -L \"../config.xml\" ]"
        ));
        assertTrue(write.indexOf("[ -f \"../config.xml\" ]")
                < write.indexOf("mv -f ./state \"../config.xml\""));
    }

    @Test
    public void rootSnapshotRejectsASwappedStageDirectoryBeforeCopyingState() throws Exception {
        Path root = Files.createTempDirectory("root-managed-state-snapshot-symlink-stage-");
        File state = root.resolve("files").toFile();
        File base = root.resolve("cache/managed-state-transfer").toFile();
        File outside = root.resolve("outside").toFile();
        File tools = root.resolve("tools").toFile();
        File trace = root.resolve("mutations.log").toFile();
        assertTrue(state.mkdirs());
        assertTrue(base.mkdirs());
        assertTrue(outside.mkdir());
        assertTrue(tools.mkdir());
        Files.setPosixFilePermissions(base.toPath(), java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        ));
        byte[] liveConfig = new byte[] { 0x31 };
        Files.write(new File(state, "config.xml").toPath(), liveConfig);
        int uid = ((Number) Files.getAttribute(root, "unix:uid")).intValue();
        int gid = ((Number) Files.getAttribute(root, "unix:gid")).intValue();
        writeExecutable(new File(tools, "id"), "#!/bin/sh\nprintf '0\\n'\n");
        writeExecutable(new File(tools, "mkdir"),
                "#!/bin/sh\n/bin/mkdir \"$1\" || exit $?\n"
                        + "/bin/mv \"$1\" \"$1.moved\" || exit $?\n"
                        + "ln -s \"$OUTSIDE\" \"$1\"\n");
        writeMutationTraceCommands(tools, trace, "cp", "chmod", "chown", "restorecon", "rm");
        ManagedStateLocations locations = new ManagedStateLocations(state, base, uid, gid);
        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                locations,
                1_000
        );
        ProcessBuilder builder = new ProcessBuilder(
                "/bin/sh", "-c", transport.stageManagedStateScript(
                        "op-00000000-0000-0000-0000-000000000000"
                )
        ).redirectErrorStream(true);
        builder.environment().put("PATH",
                tools.getAbsolutePath() + File.pathSeparator + System.getenv("PATH"));
        builder.environment().put("OUTSIDE", outside.getAbsolutePath());
        builder.environment().put("TRACE", trace.getAbsolutePath());
        Process process = builder.start();
        assertTrue("the privileged snapshot must finish promptly", process.waitFor(5, TimeUnit.SECONDS));

        assertEquals(LibsuRootShell.STATE_OPERATION_NAME_EXIT_CODE, process.exitValue());
        assertArrayEquals(liveConfig, Files.readAllBytes(new File(state, "config.xml").toPath()));
        assertFalse("the redirected destination must not receive state files",
                new File(outside, "config.xml").exists());
        assertFalse("no output or metadata mutation may follow the path swap", trace.exists());
    }

    @Test
    public void stateWriteUsesTheManagedStateFilesystemAndRemovesScratchAfterAtomicReplace()
            throws Exception {
        Path root = Files.createTempDirectory("root-managed-state-same-filesystem-write-");
        File state = root.resolve("files").toFile();
        File base = root.resolve("cache").toFile();
        File tools = root.resolve("tools").toFile();
        assertTrue(state.mkdir());
        assertTrue(base.mkdir());
        assertTrue(tools.mkdir());
        Files.write(new File(state, "config.xml").toPath(), new byte[] { 0x31 });
        byte[] expected = new byte[100 * 1024];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index % 251);
        }
        writeExecutable(new File(tools, "id"), "#!/bin/sh\nprintf '0\\n'\n");
        writeExecutable(new File(tools, "stat"),
                "#!/bin/sh\nif [ \"$1\" = '-c' ] && [ \"$2\" = '%u:%a' ]; then\n"
                        + "printf '0:700\\n'\nelse\nexec /usr/bin/stat \"$@\"\nfi\n");
        writeExecutable(new File(tools, "chown"), "#!/bin/sh\nexit 0\n");

        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                new ManagedStateLocations(state, base, 10_000, 10_000),
                1_000
        );
        String writeScript = transport.writeStateFileScript(
                ManagedStateMember.CONFIG, LibsuRootShell.encodeStateBase64(expected)
        );
        assertTrue("the payload exceeds the per-argument limit seen on 4 KiB-page systems",
                writeScript.length() > 128 * 1024);
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-s").redirectErrorStream(true);
        builder.environment().put("PATH",
                tools.getAbsolutePath() + File.pathSeparator + System.getenv("PATH"));
        Process process = builder.start();
        process.getOutputStream().write(writeScript.getBytes());
        process.getOutputStream().close();
        assertTrue("the privileged write must finish promptly", process.waitFor(5, TimeUnit.SECONDS));

        assertEquals(0, process.exitValue());
        assertArrayEquals(expected, Files.readAllBytes(new File(state, "config.xml").toPath()));
        File[] scratch = state.listFiles(file -> file.getName().startsWith(".standroid-managed-state-"));
        assertNotNull(scratch);
        assertEquals("temporary state data must be removed after the atomic rename", 0, scratch.length);
    }

    @Test
    public void stateWriteRejectsASwappedScratchDirectoryBeforeExternalMutation() throws Exception {
        Path root = Files.createTempDirectory("root-managed-state-symlink-scratch-");
        File state = root.resolve("files").toFile();
        File base = root.resolve("cache").toFile();
        File outside = root.resolve("outside").toFile();
        File tools = root.resolve("tools").toFile();
        assertTrue(state.mkdir());
        assertTrue(base.mkdir());
        assertTrue(outside.mkdir());
        assertTrue(tools.mkdir());
        writeExecutable(new File(tools, "id"), "#!/bin/sh\nprintf '0\\n'\n");
        writeExecutable(new File(tools, "mkdir"),
                "#!/bin/sh\n/bin/mkdir \"$1\" || exit $?\n"
                        + "/bin/mv \"$1\" \"$1.moved\" || exit $?\n"
                        + "ln -s \"$OUTSIDE\" \"$1\"\n");
        writeMutationTraceCommands(tools, root.resolve("mutations.log").toFile(),
                "base64", "chown", "mv", "rm");

        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                new ManagedStateLocations(state, base, 10_000, 10_000),
                1_000
        );
        ProcessBuilder builder = new ProcessBuilder(
                "/bin/sh", "-c", transport.writeStateFileScript(ManagedStateMember.CONFIG, "YQ==")
        ).redirectErrorStream(true);
        builder.environment().put("PATH",
                tools.getAbsolutePath() + File.pathSeparator + System.getenv("PATH"));
        builder.environment().put("OUTSIDE", outside.getAbsolutePath());
        builder.environment().put("TRACE", root.resolve("mutations.log").toString());
        Process process = builder.start();
        assertTrue("the privileged write must finish promptly", process.waitFor(5, TimeUnit.SECONDS));

        assertEquals(LibsuRootShell.STATE_OPERATION_NAME_EXIT_CODE, process.exitValue());
        assertFalse("the external directory must remain untouched", new File(outside, "state").exists());
        assertFalse("no live Managed State member may be installed", new File(state, "config.xml").exists());
        assertFalse("no mutation command may run after the scratch path is swapped",
                root.resolve("mutations.log").toFile().exists());
    }

    @Test
    public void stagedInstallChecksShellStatusesWithoutApi23IncompatibleFindModes() {
        ManagedStateLocations locations = ManagedStateTestSupport.locations();
        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                locations,
                1_000
        );

        String script = transport.installStagedManagedStateScript(
                "op-00000000-0000-0000-0000-000000000000"
        );

        assertTrue(script.contains(
                "standroid_find_empty \"$standroid_base\" -maxdepth 0"
        ));
        assertTrue(script.contains("standroid_find_empty() {"));
        assertTrue(script.contains(
                "standroid_find_output=$(find \"$@\" -print 2>/dev/null) || return 1"
        ));
        assertTrue(script.contains(
                "if ! standroid_find_empty \"$standroid_base\" -maxdepth 0"
        ));
        assertTrue("the installer pins the checked state directory before creating scratch",
                script.contains("standroid_state_physical=$(pwd -P)"));
        assertTrue("the installer scratch directory is private and inside Managed State",
                script.contains("standroid_work_name='.standroid-managed-state-")
                        && script.contains("standroid_work=."));
        assertFalse("the installer uses no cross-filesystem temporary base",
                script.contains("/data/local/tmp"));
        assertFalse("installer scratch files never live in app-owned staging",
                script.contains("\"$standroid_stage/.install-"));
        assertFalse("installer scratch files never live beside live state",
                script.contains("$standroid_state/.standroid-import-"));
        assertFalse("API 23 find does not support the slash permission mode",
                script.contains("-perm /0077"));
        assertFalse("find failures must not be hidden behind a successful head command",
                script.contains("| head -n 1"));
        int baseGuardStart = script.indexOf("if ! standroid_find_empty \"$standroid_base\"");
        int stagePathStart = script.indexOf("standroid_stage=", baseGuardStart);
        assertTrue(baseGuardStart >= 0 && stagePathStart > baseGuardStart);
        String baseGuard = script.substring(baseGuardStart, stagePathStart);
        assertTrue("the shared cache GID may differ from the application GID",
                baseGuard.contains("! -user " + locations.applicationUid()));
        assertTrue("the staging base must remain owner-accessible",
                baseGuard.contains("! -perm -0700"));
        assertFalse("the base guard must preserve Android's cache-specific GID",
                baseGuard.contains("! -group "));
        assertTrue(script.contains(
                "standroid_find_empty \"$standroid_stage\" -maxdepth 0"
        ));
        assertTrue(script.contains(
                "standroid_find_empty \"$standroid_stage\" -mindepth 1"
        ));
        assertTrue(script.contains("standroid_base_gid=$(stat -c %g \"$standroid_base\""));
        assertTrue("stat failure must reject staging before an empty GID can be trusted",
                script.contains("2>/dev/null) || exit "
                        + LibsuRootShell.STATE_OPERATION_NAME_EXIT_CODE));
        assertTrue(script.contains("! -group \"$standroid_base_gid\""));
        assertTrue("the staging directory is pinned before its mode is changed",
                script.contains("( cd \"$standroid_stage\"")
                        && script.contains("standroid_stage_pinned=$(pwd -P)"));
        assertTrue("only approved members are snapshotted from the pinned staging directory",
                script.contains("tar -cf - \"$@\"")
                        && script.contains("standroid_stage=./stage-import"));
        int pinnedSnapshot = script.indexOf("standroid_stage=./stage-import");
        int firstCopy = script.indexOf("cp -P");
        assertTrue("all member copies use the private snapshot made from the pinned directory",
                pinnedSnapshot >= 0 && firstCopy > pinnedSnapshot);
        assertTrue(script.contains(
                "if ! standroid_find_empty \"$standroid_stage\" -mindepth 1 -maxdepth 1"
        ));
        assertTrue(script.contains("! -name config.xml"));
        assertTrue(script.contains("! -name sharedpreferences.dat"));
        assertTrue(script.contains("! -user " + locations.applicationUid()));
        assertTrue(script.contains("! -group " + locations.applicationGid()));
        assertTrue("staged ownership accepts either the app GID or inherited cache GID",
                script.contains("! -group " + locations.applicationGid()
                        + " -a ! -group \"$standroid_base_gid\""));
        assertTrue(script.contains("[ -f \"$standroid_stage/sharedpreferences.dat\" ]"));
        assertTrue(script.contains("[ ! -L \"$standroid_stage/sharedpreferences.dat\" ]"));
        assertFalse(script.contains("$standroid_state/sharedpreferences.dat"));

        int memberLoopStart = script.indexOf("for standroid_member in");
        int memberLoopEnd = script.indexOf("done", memberLoopStart);
        assertTrue(memberLoopStart >= 0 && memberLoopEnd > memberLoopStart);
        assertFalse(script.substring(memberLoopStart, memberLoopEnd)
                .contains("sharedpreferences.dat"));

        int memberPreflight = script.indexOf("for standroid_validate_member in");
        int memberPreflightEnd = script.indexOf("done", memberPreflight);
        int indexPreflight = script.indexOf("[ -d \"$standroid_index\" ]");
        int indexTreePreflight = script.indexOf(
                "if ! standroid_find_empty \"$standroid_index\""
        );
        int indexTreeFailureGuard = script.indexOf(
                "then exit " + LibsuRootShell.STATE_UNSAFE_TREE_EXIT_CODE,
                indexTreePreflight
        );
        int indexPreflightEnd = script.indexOf("fi", indexTreeFailureGuard);
        int firstLiveWrite = script.indexOf(
                "mv -f \"$standroid_install_temp\""
        );
        assertTrue(memberPreflight >= 0 && memberPreflightEnd > memberPreflight);
        assertTrue(memberPreflightEnd < firstLiveWrite);
        assertTrue(indexPreflight < firstLiveWrite);
        assertTrue(indexTreeFailureGuard > indexTreePreflight);
        assertTrue(indexPreflightEnd > indexTreeFailureGuard);
        assertTrue(indexPreflightEnd < firstLiveWrite);

        int indexPreparation = script.indexOf(
                "cp -R \"$standroid_index\" \"$standroid_index_work\""
        );
        assertTrue("the replacement index is fully copied before the first live write",
                indexPreparation >= 0 && indexPreparation < firstLiveWrite);
        assertTrue(script.contains("mv \"$standroid_live_index\" \"$standroid_index_backup\""));
        assertTrue(script.contains("mv \"$standroid_index_work\" \"$standroid_live_index\""));
        assertFalse("the live index must never be recursively deleted before replacement",
                script.contains("rm -rf \"$standroid_state/index-v2\""));
    }

    @Test
    public void stagedImportReadsFromItsPinnedDirectoryAfterThePathIsSwapped() throws Exception {
        Path root = Files.createTempDirectory("root-managed-state-import-pinned-stage-");
        File state = root.resolve("files").toFile();
        File base = root.resolve("cache/managed-state-transfer").toFile();
        File operation = new File(base, "op-00000000-0000-0000-0000-000000000000");
        File outside = root.resolve("outside").toFile();
        File tools = root.resolve("tools").toFile();
        assertTrue(state.mkdirs());
        assertTrue(operation.mkdirs());
        assertTrue(outside.mkdir());
        assertTrue(tools.mkdir());
        int uid = ((Number) Files.getAttribute(root, "unix:uid")).intValue();
        int gid = ((Number) Files.getAttribute(root, "unix:gid")).intValue();
        byte[] stagedConfig = new byte[] { 0x41 };
        byte[] liveConfig = new byte[] { 0x31 };
        byte[] livePreferences = new byte[] { 0x51 };
        Files.write(new File(state, "config.xml").toPath(), liveConfig);
        Files.write(new File(state, "sharedpreferences.dat").toPath(), livePreferences);
        Files.write(new File(operation, "config.xml").toPath(), stagedConfig);
        Files.write(new File(operation, "cert.pem").toPath(), new byte[] { 0x42 });
        Files.write(new File(operation, "key.pem").toPath(), new byte[] { 0x43 });
        Files.write(new File(operation, "sharedpreferences.dat").toPath(), new byte[] { 0x52 });
        writeExecutable(new File(tools, "id"), "#!/bin/sh\nprintf '0\\n'\n");
        writeExecutable(new File(tools, "stat"),
                "#!/bin/sh\ncase \"$2\" in\n"
                        + " %u:%a) printf '0:700\\n' ;;\n"
                        + " %u) printf '0\\n' ;;\n"
                        + " %g) printf '" + gid + "\\n' ;;\n"
                        + " %a) printf '700\\n' ;;\n"
                        + " *) exit 1 ;;\nesac\n");
        writeExecutable(new File(tools, "chown"), "#!/bin/sh\nexit 0\n");
        writeExecutable(new File(tools, "tar"),
                "#!/bin/sh\n"
                        + "if [ \"$1\" = '-cf' ]; then\n"
                        + "  /bin/mv \"$STAGE\" \"$STAGE.moved\" || exit $?\n"
                        + "  /bin/ln -s \"$OUTSIDE\" \"$STAGE\" || exit $?\n"
                        + "fi\n"
                        + "exec /usr/bin/tar \"$@\"\n");

        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                new ManagedStateLocations(state, base, uid, gid),
                1_000
        );
        ProcessBuilder builder = new ProcessBuilder(
                "/bin/sh", "-c", transport.installStagedManagedStateScript(
                        "op-00000000-0000-0000-0000-000000000000"
                )
        ).redirectErrorStream(true);
        builder.environment().put("PATH",
                tools.getAbsolutePath() + File.pathSeparator + System.getenv("PATH"));
        builder.environment().put("STAGE", operation.getAbsolutePath());
        builder.environment().put("OUTSIDE", outside.getAbsolutePath());
        Process process = builder.start();
        assertTrue("the privileged importer must finish promptly", process.waitFor(5, TimeUnit.SECONDS));

        assertEquals(0, process.exitValue());
        assertArrayEquals(stagedConfig, Files.readAllBytes(new File(state, "config.xml").toPath()));
        assertArrayEquals(livePreferences,
                Files.readAllBytes(new File(state, "sharedpreferences.dat").toPath()));
        assertFalse("the redirected staging path must not supply imported state",
                new File(outside, "config.xml").exists());
    }

    @Test
    public void stagedInstallFailsClosedWhenFindCannotInspectTheStagingBase() throws Exception {
        Path root = Files.createTempDirectory("root-managed-state-find-failure-");
        File state = root.resolve("files").toFile();
        File base = root.resolve("cache/managed-state-transfer").toFile();
        File operation = new File(base, "op-00000000-0000-0000-0000-000000000000");
        File tools = root.resolve("tools").toFile();
        assertTrue(state.mkdirs());
        assertTrue(operation.mkdirs());
        assertTrue(tools.mkdir());
        byte[] liveConfig = new byte[] { 0x31 };
        Files.write(new File(state, "config.xml").toPath(), liveConfig);
        Files.write(new File(operation, "config.xml").toPath(), new byte[] { 0x41 });
        Files.write(new File(operation, "cert.pem").toPath(), new byte[] { 0x42 });
        Files.write(new File(operation, "key.pem").toPath(), new byte[] { 0x43 });
        writeExecutable(new File(tools, "id"),
                "#!/bin/sh\nprintf '0\\n'\n");
        writeExecutable(new File(tools, "find"), "#!/bin/sh\nexit 7\n");

        ManagedStateLocations locations = new ManagedStateLocations(state, base, 10_000, 10_000);
        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                locations,
                1_000
        );
        ProcessBuilder builder = new ProcessBuilder(
                "/bin/sh", "-c", transport.installStagedManagedStateScript(
                        "op-00000000-0000-0000-0000-000000000000"
                )
        ).redirectErrorStream(true);
        builder.environment().put("PATH",
                tools.getAbsolutePath() + File.pathSeparator + System.getenv("PATH"));
        Process process = builder.start();
        assertTrue("the privileged command must finish promptly", process.waitFor(5, TimeUnit.SECONDS));

        assertEquals(LibsuRootShell.STATE_OPERATION_NAME_EXIT_CODE, process.exitValue());
        assertArrayEquals(liveConfig, Files.readAllBytes(new File(state, "config.xml").toPath()));
    }

    @Test
    public void stagedInstallPreparesTheIndexBeforeChangingAnyLiveMember() throws Exception {
        Path root = Files.createTempDirectory("root-managed-state-index-copy-failure-");
        File state = root.resolve("files").toFile();
        File base = root.resolve("cache/managed-state-transfer").toFile();
        File operation = new File(base, "op-00000000-0000-0000-0000-000000000000");
        File tools = root.resolve("tools").toFile();
        assertTrue(state.mkdirs());
        assertTrue(operation.mkdirs());
        assertTrue(tools.mkdir());
        int uid = ((Number) Files.getAttribute(root, "unix:uid")).intValue();
        int gid = ((Number) Files.getAttribute(root, "unix:gid")).intValue();
        byte[] liveConfig = new byte[] { 0x31 };
        byte[] liveIndex = new byte[] { 0x32 };
        Files.write(new File(state, "config.xml").toPath(), liveConfig);
        File liveIndexEntry = new File(state, "index-v2/old.db");
        assertTrue(liveIndexEntry.getParentFile().mkdirs());
        Files.write(liveIndexEntry.toPath(), liveIndex);
        Files.write(new File(operation, "config.xml").toPath(), new byte[] { 0x41 });
        Files.write(new File(operation, "cert.pem").toPath(), new byte[] { 0x42 });
        Files.write(new File(operation, "key.pem").toPath(), new byte[] { 0x43 });
        File stagedIndexEntry = new File(operation, "index-v2/new.db");
        assertTrue(stagedIndexEntry.getParentFile().mkdirs());
        Files.write(stagedIndexEntry.toPath(), new byte[] { 0x44 });
        writeExecutable(new File(tools, "id"), "#!/bin/sh\nprintf '0\\n'\n");
        writeExecutable(new File(tools, "stat"),
                "#!/bin/sh\ncase \"$2\" in\n"
                        + " %u:%a) printf '0:700\\n' ;;\n"
                        + " %u) printf '0\\n' ;;\n"
                        + " %g) printf '" + gid + "\\n' ;;\n"
                        + " %a) printf '700\\n' ;;\n"
                        + " *) exit 1 ;;\nesac\n");
        writeExecutable(new File(tools, "chown"), "#!/bin/sh\nexit 0\n");
        writeExecutable(new File(tools, "cp"),
                "#!/bin/sh\ncase \"$3\" in */index-work) exit 8;; esac\n"
                        + "exec /bin/cp \"$@\"\n");

        ManagedStateLocations locations = new ManagedStateLocations(state, base, uid, gid);
        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                locations,
                1_000
        );
        ProcessBuilder builder = new ProcessBuilder(
                "/bin/sh", "-c", transport.installStagedManagedStateScript(
                        "op-00000000-0000-0000-0000-000000000000"
                )
        ).redirectErrorStream(true);
        builder.environment().put("PATH",
                tools.getAbsolutePath() + File.pathSeparator + System.getenv("PATH"));
        Process process = builder.start();
        assertTrue("the privileged command must finish promptly", process.waitFor(5, TimeUnit.SECONDS));

        assertEquals(LibsuRootShell.STATE_INSTALL_EXIT_CODE, process.exitValue());
        assertArrayEquals(liveConfig, Files.readAllBytes(new File(state, "config.xml").toPath()));
        assertArrayEquals(liveIndex, Files.readAllBytes(liveIndexEntry.toPath()));
        File[] scratch = state.listFiles(file -> file.getName().startsWith(".standroid-managed-state-"));
        assertNotNull(scratch);
        assertEquals("failed import setup must remove its root-owned copies", 0, scratch.length);
    }

    private static void writeMutationTraceCommands(
            File tools,
            File trace,
            String... commandNames
    ) throws IOException {
        for (String commandName : commandNames) {
            writeExecutable(
                    new File(tools, commandName),
                    "#!/bin/sh\nprintf '" + commandName + "\\n' >> \"$TRACE\"\nexit 0\n"
            );
        }
    }

    private static int runShellScript(String script, File tools, File trace) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", script)
                .redirectErrorStream(true);
        builder.environment().put(
                "PATH",
                tools.getAbsolutePath() + File.pathSeparator + System.getenv("PATH")
        );
        builder.environment().put("TRACE", trace.getAbsolutePath());
        Process process = builder.start();
        assertTrue("the privileged script must finish promptly", process.waitFor(5, TimeUnit.SECONDS));
        return process.exitValue();
    }

    private static void writeExecutable(File target, String contents) throws IOException {
        Files.write(target.toPath(), contents.getBytes(StandardCharsets.UTF_8));
        assertTrue("could not make the test command executable", target.setExecutable(true));
    }

    @Test
    public void provenanceIsProvenOnlyForAShellThatIsTheClientItself() throws Exception {
        LibsuRootShell attached = new LibsuRootShell(
                new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null),
                new DestroyRecordingProcess(),
                ManagedStateTestSupport.locations(),
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
                ManagedStateTestSupport.locations(),
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
        DestroyRecordingProcess process = new DestroyRecordingProcess();
        LibsuRootShell transport = new LibsuRootShell(
                new ScriptedLibsuShell(null, new IllegalStateException("the probe command failed")),
                process,
                ManagedStateTestSupport.locations(),
                1_000
        );

        transport.determineExitStatusProvenance(ScriptedLibsuShell.OWNER_PROCESS_ID);

        assertFalse(transport.exitStatusBelongsToLaunchedProcess());
        assertEquals(
                "a failed probe on a live shell must leave the transport untouched",
                0,
                process.destroyCount()
        );
    }

    @Test
    public void failedProvenanceCommandOnALiveShellStaysUnprovable() throws Exception {
        ScriptedLibsuShell shell = new ScriptedLibsuShell(null, null).failProvenanceCommand();
        DestroyRecordingProcess process = new DestroyRecordingProcess();
        LibsuRootShell transport = new LibsuRootShell(
                shell, process, ManagedStateTestSupport.locations(), 1_000
        );

        transport.determineExitStatusProvenance(ScriptedLibsuShell.OWNER_PROCESS_ID);

        assertFalse(
                "a command that failed on a live shell leaves the status unattributable",
                transport.exitStatusBelongsToLaunchedProcess()
        );
        assertEquals("the live transport must not be closed", 0, shell.closeAttempts());
        assertEquals("the live transport process must not be destroyed", 0, process.destroyCount());
    }

    @Test
    public void deadShellDuringTheProvenanceProbeFailsInsteadOfStayingUnprovable() throws Exception {
        ScriptedLibsuShell shell = new ScriptedLibsuShell(null, null).dieDuringProvenanceProbe();
        LibsuRootShell transport =
                new LibsuRootShell(
                        shell, new DestroyRecordingProcess(), ManagedStateTestSupport.locations(),
                        1_000
                );

        try {
            transport.determineExitStatusProvenance(ScriptedLibsuShell.OWNER_PROCESS_ID);
            fail("a dead shell must not be reported as a merely unprovable relationship");
        } catch (IOException expected) {
            assertTrue(
                    "the failed command that revealed the dead shell must surface unchanged",
                    expected.getMessage().contains("The root shell command failed")
            );
        }

        assertFalse(transport.exitStatusBelongsToLaunchedProcess());
        assertFalse("the probe must have observed the dead shell", shell.isAlive());
    }

    @Test
    public void provenanceProbeThatInvalidatesTheTransportFailsInsteadOfStayingSilent()
            throws Exception {
        CountDownLatch provenanceGate = new CountDownLatch(1);
        ScriptedLibsuShell shell = new ScriptedLibsuShell(
                ScriptedLibsuShell.ATTACHED_STAT_LINE, null, provenanceGate, true
        );
        DestroyRecordingProcess process = new DestroyRecordingProcess();
        LibsuRootShell transport = new LibsuRootShell(
                shell, process, ManagedStateTestSupport.locations(), 50
        );

        try {
            transport.determineExitStatusProvenance(ScriptedLibsuShell.OWNER_PROCESS_ID);
            fail("a probe that tore the transport down must not report an unprovable answer");
        } catch (IOException expected) {
            assertTrue(
                    "the timeout that invalidated the transport must surface unchanged",
                    expected.getMessage().contains("timed out")
            );
        } finally {
            provenanceGate.countDown();
        }

        assertFalse(transport.exitStatusBelongsToLaunchedProcess());
        assertEquals("the invalidated transport must be closed", 1, shell.closeAttempts());
        assertEquals(
                "a failed libsu close must destroy the transport process",
                1,
                process.destroyCount()
        );
    }

    @Test
    public void provenanceIsRecordedOnceWhileTheClientIsStillAlive() throws Exception {
        ScriptedLibsuShell shell = new ScriptedLibsuShell(
                ScriptedLibsuShell.DETACHED_STAT_LINE, null
        );
        LibsuRootShell transport = new LibsuRootShell(
                shell, new DestroyRecordingProcess(), ManagedStateTestSupport.locations(), 1_000
        );

        transport.determineExitStatusProvenance(ScriptedLibsuShell.OWNER_PROCESS_ID);
        transport.determineExitStatusProvenance(ScriptedLibsuShell.OWNER_PROCESS_ID);

        assertFalse(transport.exitStatusBelongsToLaunchedProcess());
        assertEquals("the shell is asked exactly once", 1, shell.statReads());
    }
}
