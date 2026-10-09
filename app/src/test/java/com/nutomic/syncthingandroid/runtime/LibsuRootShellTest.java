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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assume;
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

    /** Folder path every privileged folder-operation test operates on. */
    private static final String PRIVILEGED_FOLDER_ROOT = "/storage/emulated/0/notes";

    /** Event argument the sync-completion script dispatch always receives. */
    private static final String SYNC_COMPLETE_EVENT = "sync_complete";

    private static LibsuRootShell folderTransport(ScriptedLibsuShell shell) {
        return new LibsuRootShell(
                shell, new DestroyRecordingProcess(), ManagedStateTestSupport.locations(), 1_000
        );
    }

    private static ScriptedLibsuShell attachedShell() {
        return new ScriptedLibsuShell(ScriptedLibsuShell.ATTACHED_STAT_LINE, null);
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** One privileged folder operation a test can run without declaring a checked exception. */
    private interface PrivilegedFolderCall {
        void run() throws IOException;
    }

    /** Returns the typed failure one folder operation reported, and fails the test otherwise. */
    private static FolderOperationFailure failureOf(PrivilegedFolderCall call) {
        try {
            call.run();
        } catch (RootFolderOperationException expected) {
            return expected.failure();
        } catch (IOException unexpected) {
            throw new AssertionError("the operation failed for an unexpected reason", unexpected);
        }
        throw new AssertionError("the operation reported success instead of a typed failure");
    }

    /** Extracts the conflict-name pattern one privileged scan matches directory entries against. */
    private static String conflictNamePatternFrom(String script) {
        int start = script.indexOf("*.sync-conflict-");
        assertTrue("the scan matches conflict names", start > 0);
        int end = script.indexOf(") ;;", start);
        assertTrue("the conflict pattern is terminated", end > start);
        return script.substring(start, end);
    }

    @Test
    public void everyPrivilegedFolderCommandRefusesToRunWithoutRealUidZero() {
        LibsuRootShell transport = folderTransport(attachedShell());
        List<String> scripts = Arrays.asList(
                transport.folderWriteabilityScript(PRIVILEGED_FOLDER_ROOT),
                transport.conflictDiscoveryScript(PRIVILEGED_FOLDER_ROOT),
                transport.readFolderIgnoreListScript(PRIVILEGED_FOLDER_ROOT),
                transport.writeFolderIgnoreListScript(PRIVILEGED_FOLDER_ROOT, "bm90ZXM="),
                transport.folderScriptSetScript(PRIVILEGED_FOLDER_ROOT, SYNC_COMPLETE_EVENT),
                transport.ioPriorityScript(4321, 987654),
                transport.inotifyWatchLimitScript(InotifyWatchLimit.TARGET)
        );

        for (String script : scripts) {
            assertTrue(
                    "every privileged folder command refuses to run without real UID 0",
                    script.contains("\"$(id -u)\"") && script.contains("!= \"0\" ] && exit "
                            + LibsuRootShell.STATE_UID_GUARD_EXIT_CODE)
            );
        }
    }

    @Test
    public void folderOperationsRunInsideTheBoundedProcessGroupSupervisor() throws Exception {
        ScriptedLibsuShell shell = attachedShell().scriptJobResult(0, encoded("notes.txt"));

        folderTransport(shell).discoverConflictFiles(PRIVILEGED_FOLDER_ROOT);

        String command = shell.executedCommands().get(0);
        assertTrue("a folder command is capped before the transport deadline", command.contains("timeout -k "));
        assertTrue("the script travels over stdin rather than one shell argument",
                command.contains(" sh <<'STANDROID_TIMEOUT_"));
        assertFalse("the complete script must never occupy one sh -c argument",
                command.contains("sh -c "));
    }

    @Test
    public void writeabilityProbeMapsEveryVerdictToItsOwnStatus() throws Exception {
        assertEquals(
                FolderWriteability.WRITABLE,
                folderTransport(attachedShell().scriptJobResult(0))
                        .probeFolderWriteability(PRIVILEGED_FOLDER_ROOT)
        );
        assertEquals(
                FolderWriteability.READ_ONLY,
                folderTransport(attachedShell()
                        .scriptJobResult(LibsuRootShell.FOLDER_READ_ONLY_EXIT_CODE))
                        .probeFolderWriteability(PRIVILEGED_FOLDER_ROOT)
        );
        assertEquals(
                FolderWriteability.UNKNOWN,
                folderTransport(attachedShell()
                        .scriptJobResult(LibsuRootShell.FOLDER_UNKNOWN_EXIT_CODE))
                        .probeFolderWriteability(PRIVILEGED_FOLDER_ROOT)
        );
        assertEquals(
                "a candidate that could not be probed is never reported as usable",
                FolderOperationFailure.FOLDER_ACCESS_FAILED,
                failureOf(() -> folderTransport(attachedShell()
                        .scriptJobResult(LibsuRootShell.FOLDER_ACCESS_EXIT_CODE))
                        .probeFolderWriteability(PRIVILEGED_FOLDER_ROOT))
        );
    }

    @Test
    public void writeabilityProbeScriptCreatesNothingInsideTheCandidate() {
        String script = folderTransport(attachedShell())
                .folderWriteabilityScript(PRIVILEGED_FOLDER_ROOT);

        assertTrue("the verdict comes from the permissions the root identity observes",
                script.contains("[ ! -w \"$standroid_folder\" ]")
                        && script.contains("[ ! -x \"$standroid_folder\" ]"));
        for (String mutation : new String[]{".stwritetest", "touch ", "mkdir ", "mktemp"}) {
            assertFalse("a probe never leaves an artifact behind: " + mutation,
                    script.contains(mutation));
        }
    }

    @Test
    public void conflictDiscoveryReportsEveryMatchedPathRelativeToTheFolder() throws Exception {
        ScriptedLibsuShell shell = attachedShell().scriptJobResult(
                0,
                encoded("notes.sync-conflict-20260102-150405-ABCDEFG.txt"),
                encoded("nested/deep.sync-conflict-20260102-150405-z9y8x7w.md")
        );

        ConflictDiscoveryResult result =
                folderTransport(shell).discoverConflictFiles(PRIVILEGED_FOLDER_ROOT);

        assertEquals(
                Arrays.asList(
                        "notes.sync-conflict-20260102-150405-ABCDEFG.txt",
                        "nested/deep.sync-conflict-20260102-150405-z9y8x7w.md"
                ),
                result.relativePaths()
        );
    }

    @Test
    public void conflictScanReportsUnusualFileNamesByteForByte() throws Exception {
        String unusual =
                "we ird\"na'me" + (char) 10 + "second line.sync-conflict-20260102-150405-ABCDEFG.txt";
        ScriptedLibsuShell shell = attachedShell().scriptJobResult(0, encoded(unusual));

        ConflictDiscoveryResult result =
                folderTransport(shell).discoverConflictFiles(PRIVILEGED_FOLDER_ROOT);

        assertEquals(
                "a name with spaces, quotes, and a line break survives the transport unchanged",
                Arrays.asList(unusual),
                result.relativePaths()
        );
    }

    @Test
    public void conflictDiscoveryRejectsAnyPathThatLeavesTheConfiguredFolder() throws Exception {
        String[] escapes = {
                "../outside.sync-conflict-20260102-150405-ABCDEFG.txt",
                "/etc/passwd",
                "nested/../../outside.txt",
                "trailing/"
        };

        for (String escaped : escapes) {
            ScriptedLibsuShell shell = attachedShell().scriptJobResult(0, encoded(escaped));

            assertEquals(
                    "a reported path outside the folder fails the whole scan: " + escaped,
                    FolderOperationFailure.FOLDER_MEMBER_UNSAFE,
                    failureOf(() -> folderTransport(shell)
                            .discoverConflictFiles(PRIVILEGED_FOLDER_ROOT))
            );
        }
    }

    @Test
    public void conflictDiscoveryFailsInsteadOfTruncatingWhenItsOutputBudgetIsExceeded()
            throws Exception {
        ScriptedLibsuShell shell = attachedShell().scriptJobResult(
                0, "A".repeat(LibsuRootShell.FOLDER_SCAN_MAX_OUTPUT_CHARS + 1)
        );

        assertEquals(
                "an oversized scan result is a typed limit failure, never a shorter list",
                FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                failureOf(() -> folderTransport(shell)
                        .discoverConflictFiles(PRIVILEGED_FOLDER_ROOT))
        );
    }

    @Test
    public void conflictDiscoveryFailsInsteadOfTruncatingWhenItsMatchBudgetIsExceeded()
            throws Exception {
        String[] matches = new String[LibsuRootShell.FOLDER_SCAN_MAX_MATCHES + 1];
        for (int index = 0; index < matches.length; index++) {
            matches[index] = encoded("conflict-" + index + ".txt");
        }
        ScriptedLibsuShell shell = attachedShell().scriptJobResult(0, matches);

        assertEquals(
                "a scan that matched more entries than the budget allows reports no list at all",
                FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                failureOf(() -> folderTransport(shell)
                        .discoverConflictFiles(PRIVILEGED_FOLDER_ROOT))
        );
    }

    @Test
    public void conflictDiscoveryReportsATimeBudgetOverrunAsALimitFailure() throws Exception {
        ScriptedLibsuShell shell = attachedShell().scriptJobResult(124);

        assertEquals(
                FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                failureOf(() -> folderTransport(shell)
                        .discoverConflictFiles(PRIVILEGED_FOLDER_ROOT))
        );
    }

    @Test
    public void aFailedConflictScanNeverBecomesAnEmptySuccessfulResult() throws Exception {
        ScriptedLibsuShell shell = attachedShell()
                .scriptJobResult(LibsuRootShell.FOLDER_ACCESS_EXIT_CODE);

        assertEquals(
                FolderOperationFailure.FOLDER_ACCESS_FAILED,
                failureOf(() -> folderTransport(shell)
                        .discoverConflictFiles(PRIVILEGED_FOLDER_ROOT))
        );
    }

    @Test
    public void conflictScanScriptAvoidsFindAndHeadAndFollowsNoSymbolicLink() {
        String script = folderTransport(attachedShell())
                .conflictDiscoveryScript(PRIVILEGED_FOLDER_ROOT);

        assertFalse("the compatibility floor cannot rely on find", script.contains("find "));
        assertFalse("no scan may truncate its own result with head", script.contains("head "));
        assertTrue("the walk uses shell pathname expansion",
                script.contains("\"$standroid_listed\"/*")
                        && script.contains("\"$standroid_listed\"/..?*"));
        assertTrue("symbolic links are skipped instead of followed",
                script.contains("[ -L \"$standroid_entry\" ] && continue"));
        assertTrue("the versioning directory is never entered",
                script.contains(".stversions) continue"));
        assertTrue("paths travel base64-encoded so unusual names survive",
                script.contains("| base64"));
        assertTrue("the walk keeps an entry budget",
                script.contains("-le " + LibsuRootShell.FOLDER_SCAN_MAX_ENTRIES));
        assertTrue("the walk keeps a match budget",
                script.contains("-le " + LibsuRootShell.FOLDER_SCAN_MAX_MATCHES));
        assertTrue("the walk keeps a wall-clock budget",
                script.contains("$(date +%s) + "
                        + (LibsuRootShell.FOLDER_SCAN_BUDGET_MILLIS / 1_000)));
        assertTrue("a spent budget ends the whole scan as a typed limit failure",
                script.contains("exit " + LibsuRootShell.FOLDER_LIMIT_EXIT_CODE));
    }

    @Test
    public void conflictScanPatternMatchesTheNamesTheSyncthingCoreCreates() {
        String pattern = conflictNamePatternFrom(
                folderTransport(attachedShell()).conflictDiscoveryScript(PRIVILEGED_FOLDER_ROOT)
        );
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        assertTrue("a conflict on a plain file name is reported",
                matcher.matches(Paths.get("notes.sync-conflict-20260102-150405-ABCDEFG.txt")));
        assertTrue("a conflict whose marker follows the extension is still reported",
                matcher.matches(Paths.get("notes.txt.sync-conflict-20260102-150405-ABCDEFG")));
        assertTrue("a conflict on a name without an extension is reported",
                matcher.matches(Paths.get("notes.sync-conflict-20260102-150405-ABCDEFG")));
        assertFalse("an ordinary file is never reported",
                matcher.matches(Paths.get("notes.txt")));
        assertFalse("a name without the device identifier is never reported",
                matcher.matches(Paths.get("notes.sync-conflict-20260102-150405.txt")));
    }

    @Test
    public void ignoreListReadScriptSeparatesAnAbsentListFromAnUnreadableOne() {
        String script = folderTransport(attachedShell())
                .readFolderIgnoreListScript(PRIVILEGED_FOLDER_ROOT);

        assertTrue("an absent list reports its own status",
                script.contains("exit " + LibsuRootShell.FOLDER_MISSING_EXIT_CODE));
        assertTrue("the absence check covers links as well as entries",
                script.contains("[ ! -e \"$standroid_member\" ] && [ ! -L \"$standroid_member\" ]"));
        assertTrue("a symbolic link is refused instead of followed",
                script.contains("[ -L \"$standroid_member\" ] || [ ! -f \"$standroid_member\" ]"));
        assertTrue("an unreadable list is not an absent list",
                script.contains("[ ! -r \"$standroid_member\" ]"));
        assertTrue("the list is read through the opened descriptor, bounded by the checked size",
                script.contains("dd if=\"$standroid_fd_path\" 3<&3 bs="
                        + LibsuRootShell.FOLDER_IGNORE_LIST_READ_BLOCK_BYTES
                        + " count=\"$standroid_blocks\""));
        assertTrue("the descriptor is opened from the member entry and compared with that entry",
                script.contains("exec 3< \"$standroid_member\"")
                        && script.contains("standroid_member_inode=$(standroid_file_inode"
                        + " \"$standroid_member\")")
                        && script.contains("[ \"$standroid_member_inode\" = \"$standroid_opened_inode\" ]"));
        assertTrue("a wrapped encoder payload is unwrapped into one record",
                script.contains("| base64 | tr -d '\\n'"));
        assertTrue("the byte budget is enforced before the read",
                script.contains("-le " + LibsuRootShell.FOLDER_IGNORE_LIST_MAX_BYTES));
    }

    @Test
    public void ignoreListReadReportsAnAbsentListAsNoListAndAFailedReadAsATypedFailure()
            throws Exception {
        assertNull(
                "a folder without an ignore list reports no list at all",
                folderTransport(attachedShell()
                        .scriptJobResult(LibsuRootShell.FOLDER_MISSING_EXIT_CODE))
                        .readFolderIgnoreList(PRIVILEGED_FOLDER_ROOT)
                        .lines()
        );
        assertEquals(
                "an unreadable ignore list never looks absent",
                FolderOperationFailure.FOLDER_ACCESS_FAILED,
                failureOf(() -> folderTransport(attachedShell()
                        .scriptJobResult(LibsuRootShell.FOLDER_ACCESS_EXIT_CODE))
                        .readFolderIgnoreList(PRIVILEGED_FOLDER_ROOT))
        );
    }

    @Test
    public void ignoreListReadDecodesTheExactBytesTheFolderHolds() throws Exception {
        String content = "notes/" + (char) 10 + "\"keep me\".txt";
        ScriptedLibsuShell shell = attachedShell().scriptJobResult(
                0,
                Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))
        );

        String[] lines =
                folderTransport(shell).readFolderIgnoreList(PRIVILEGED_FOLDER_ROOT).lines();

        assertArrayEquals(
                "every line reaches the caller without the transport reshaping it",
                new String[]{"notes/", "\"keep me\".txt"},
                lines
        );
    }

    /**
     * The replacement is staged inside the folder the shell pinned, is created exclusively, and is
     * measured and labelled through the descriptor this operation opened for it. No later step of
     * the script resolves the staged name again except the rename, so replacing that name cannot
     * redirect the ownership, permissions and security context that are carried over from the
     * member, and the member is verified to hold the staged file after the rename. Every command
     * that reads the descriptor is handed it on its own command line, because the shell of the
     * compatibility floor does not pass a descriptor it opened on to the commands it starts.
     */
    @Test
    public void ignoreListWriteScriptKeepsOwnerModeAndContextAcrossTheAtomicReplace() {
        String script = folderTransport(attachedShell())
                .writeFolderIgnoreListScript(PRIVILEGED_FOLDER_ROOT, "bm90ZXM=");

        assertTrue("the replacement is staged directly inside the pinned folder",
                script.contains("standroid_staged=\"./"));
        assertTrue("the staged entry is created exclusively, so a planted entry is refused",
                script.contains("(umask 077 && set -C && base64 -d > \"$standroid_staged\""));
        assertTrue("the content is decoded, never interpolated into the command",
                script.contains("base64 -d > \"$standroid_staged\""));
        assertTrue("the staged entry is opened once and kept as a descriptor",
                script.contains("exec 3< \"$standroid_staged\"")
                        && script.contains("standroid_fd_path=\"/dev/fd/3\"")
                        && script.contains("[ -d /proc/self/fd ]"
                        + " && standroid_fd_path=\"/proc/self/fd/3\""));
        assertTrue("the staged entry must be a regular file",
                script.contains("case \"$standroid_staged_entry\" in *\" -\"*)"));
        assertTrue("the descriptor must hold the file the staged name resolved to",
                script.contains("standroid_staged_now=$(standroid_descriptor_inode"
                        + " \"$standroid_fd_path\")")
                        && script.contains("[ \"$standroid_staged_now\""
                        + " = \"$standroid_staged_inode\" ]"));
        assertTrue("the size of the staged file is measured through the descriptor",
                script.contains("wc -c 3<&3 < \"$standroid_fd_path\""));
        assertTrue("every attribute is applied through the descriptor, which each command is"
                + " handed explicitly",
                script.contains("chmod \"$standroid_member_mode\" \"$standroid_fd_path\" 3<&3")
                        && script.contains("chown \"$2:$3\" \"$standroid_fd_path\" 3<&3")
                        && script.contains("chcon \"$standroid_member_context\""
                        + " \"$standroid_fd_path\" 3<&3")
                        && script.contains("[ \"$standroid_staged_owner\""
                        + " = \"$standroid_member_owner\" ]"));
        assertTrue("the context of the staged file is read through the descriptor",
                script.contains("standroid_descriptor_context() {")
                        && script.contains("ls -ZdL \"$1\" 2>/dev/null 3<&3")
                        && script.contains("$(standroid_descriptor_context"
                        + " \"$standroid_fd_path\")"));
        assertTrue("an uncorrectable context fails instead of relabelling the list",
                script.contains("[ \"$standroid_member_context\""
                        + " = \"$standroid_staged_context\" ]"));
        assertTrue("the member is measured before the replacement is built",
                script.contains("standroid_member_mode=$(standroid_mode_octal \"$2\")"));
        assertTrue("the member is re-checked through its name before the rename",
                script.contains("[ \"$(standroid_identity \"$standroid_member\")\""
                        + " = \"$standroid_member_identity\" ]"));
        assertTrue("the staged entry is identified once more before the rename",
                script.contains("standroid_staged_entry=$(standroid_identity"
                        + " \"$standroid_staged\")")
                        && script.contains("[ \"$1\" = \"$standroid_staged_inode\" ]"));
        assertTrue("the replacement is atomic",
                script.contains("mv -f \"$standroid_staged\" \"$standroid_member\""));
        assertTrue("the member is verified to hold the staged file after the rename",
                script.contains("[ \"$standroid_member_now\" = \"$standroid_staged_identity\" ]")
                        && script.contains("[ \"$standroid_member_size\""
                        + " -eq \"$standroid_staged_size\" ]"));
        assertTrue("every failure path removes the staged entry and closes the descriptor",
                script.contains("standroid_cleanup() {")
                        && script.contains("rm -f \"$standroid_staged\" 2>/dev/null")
                        && script.contains("standroid_descriptor_open=1")
                        && script.contains("exec 3<&-"));
        assertTrue("the folder is pinned once and no name below leaves it again",
                script.contains("cd \"$standroid_folder\"")
                        && script.contains("standroid_folder_here=$(standroid_directory_identity .)"));
        assertFalse("no staging step copies the member through a second name",
                script.contains("cp -p "));
        assertFalse("no longer any operation-private directory or temporary name",
                script.contains("standroid_work") || script.contains("standroid_temporary"));
        assertFalse("no path this operation uses walks out of the pinned folder",
                script.contains("standroid_member=\"../"));
    }

    @Test
    public void ignoreListReadReVerifiesTheMemberIdentityAroundTheRead() {
        String script = folderTransport(attachedShell())
                .readFolderIgnoreListScript(PRIVILEGED_FOLDER_ROOT);
        int read = script.indexOf(
                "dd if=\"$standroid_fd_path\" 3<&3 bs="
                        + LibsuRootShell.FOLDER_IGNORE_LIST_READ_BLOCK_BYTES
                        + " count=\"$standroid_blocks\""
        );

        assertTrue("the identity of the member is captured before the read",
                script.indexOf("standroid_identity_before=$(standroid_identity \"$standroid_member\")")
                        < read);
        assertTrue("the identity of the member is re-verified after the read",
                read < script.indexOf("standroid_identity_after=$(standroid_identity \"$standroid_member\")"));
        assertTrue("a member that changed while it was read fails closed",
                script.contains("[ \"$standroid_identity_before\" = \"$standroid_identity_after\" ]"
                        + " || exit " + LibsuRootShell.FOLDER_ACCESS_EXIT_CODE));
        assertTrue("a member that is a symbolic link at read time fails as unsafe",
                script.contains("case \"$standroid_identity_before\" in *\" l\"*) exit "
                        + LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE));
        assertFalse("no byte of the list is read through the member name again",
                script.contains("dd if=\"$standroid_member\""));
    }

    @Test
    public void ignoreListReadReportsTheMemberContentAndRefusesASymbolicLink() throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-read-");
        Path outside = Files.createTempDirectory("standroid-ignore-read-outside-");
        File tools = Files.createTempDirectory("standroid-ignore-read-tools-").toFile();
        writeRootId(tools);
        writeToyboxBase64(tools);
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Path outsideMember = outside.resolve("root-only.txt");
        Files.write(member, "keep\n".getBytes(StandardCharsets.UTF_8));
        Files.write(outsideMember, "secret\n".getBytes(StandardCharsets.UTF_8));
        String script = folderTransport(attachedShell())
                .readFolderIgnoreListScript(root.toString());

        ScriptRun stored = runFolderScript(script, tools, root.toFile());

        assertEquals("the read reports success", 0, stored.exitStatus);
        assertEquals(
                "the member content travels back as its own bytes",
                "keep\n",
                new String(
                        Base64.getDecoder().decode(String.join("", stored.lines)),
                        StandardCharsets.UTF_8
                )
        );

        Files.delete(member);
        Files.createSymbolicLink(member, outsideMember);

        ScriptRun linked = runFolderScript(script, tools, root.toFile());

        assertEquals(
                "a member that is a symbolic link fails instead of reading outside the folder",
                LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE,
                linked.exitStatus
        );
    }

    @Test
    public void ignoreListReadKeepsOneRecordWhenBase64WrapsItsOutput() throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-wrap-");
        File tools = Files.createTempDirectory("standroid-ignore-wrap-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        String content = "a-conflict-line-that-is-longer-than-the-wrap-column/"
                + "x".repeat(120) + "\n";
        Files.write(
                root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME),
                content.getBytes(StandardCharsets.UTF_8)
        );
        String script = folderTransport(attachedShell())
                .readFolderIgnoreListScript(root.toString());

        ScriptRun run = runFolderScript(script, tools, root.toFile());

        assertEquals("the read reports success", 0, run.exitStatus);
        assertEquals("a wrapped payload is unwrapped into exactly one record", 1, run.lines.size());
        assertEquals(
                "the member content travels back own bytes",
                content,
                new String(Base64.getDecoder().decode(run.lines.get(0)), StandardCharsets.UTF_8)
        );
    }

    @Test
    public void ignoreListReadRefusesAMemberThatChangedSizeWhileItWasRead() throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-reread-");
        File tools = Files.createTempDirectory("standroid-ignore-reread-tools-").toFile();
        writeRootId(tools);
        writeToyboxBase64(tools);
        String content = "keep\n".repeat(16);
        Files.write(
                root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME),
                content.getBytes(StandardCharsets.UTF_8)
        );
        String script = folderTransport(attachedShell())
                .readFolderIgnoreListScript(root.toString());

        writeGrowingDd(tools);
        ScriptRun grew = runFolderScript(script, tools, root.toFile());

        assertEquals(
                "a member that grew while it was read fails closed",
                LibsuRootShell.FOLDER_ACCESS_EXIT_CODE,
                grew.exitStatus
        );
        assertTrue("a failed growth check reports no content", grew.lines.isEmpty());

        writeTruncatingDd(tools);
        ScriptRun stoppedEarly = runFolderScript(script, tools, root.toFile());

        assertEquals(
                "a read that stopped before the checked size fails closed",
                LibsuRootShell.FOLDER_ACCESS_EXIT_CODE,
                stoppedEarly.exitStatus
        );
        assertTrue("a failed length check reports no content", stoppedEarly.lines.isEmpty());
    }

    @Test
    public void ignoreListWriteLeavesOnlyTheMemberInsideThePinnedFolder() throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-replace-");
        File tools = Files.createTempDirectory("standroid-ignore-replace-tools-").toFile();
        writeRootId(tools);
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Files.write(member, "old\n".getBytes(StandardCharsets.UTF_8));

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).writeFolderIgnoreListScript(
                        root.toString(),
                        Base64.getEncoder().encodeToString("new\n".getBytes(StandardCharsets.UTF_8))
                ),
                tools,
                root.toFile()
        );

        assertEquals("the replacement reports success", 0, run.exitStatus);
        assertEquals(
                "the member carries the new content",
                "new\n",
                new String(Files.readAllBytes(member), StandardCharsets.UTF_8)
        );
        String[] entries = root.toFile().list();
        assertNotNull(entries);
        assertEquals("the staged entry is removed again", 1, entries.length);
        assertEquals("only the member remains", LibsuRootShell.FOLDER_IGNORE_FILE_NAME, entries[0]);
    }

    @Test
    public void ignoreListWriteRefusesAnOversizedMemberBeforeItCopiesIt() throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-oversized-");
        File tools = Files.createTempDirectory("standroid-ignore-oversized-tools-").toFile();
        writeRootId(tools);
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Files.write(member, "old\n".repeat(20).getBytes(StandardCharsets.UTF_8));
        String script = folderTransport(attachedShell())
                .writeFolderIgnoreListScript(
                        root.toString(),
                        Base64.getEncoder().encodeToString("new\n".getBytes(StandardCharsets.UTF_8))
                )
                .replace(String.valueOf(LibsuRootShell.FOLDER_IGNORE_LIST_MAX_BYTES), "64");

        ScriptRun run = runFolderScript(script, tools, root.toFile());

        assertEquals(
                "a member larger than the ignore-list budget fails the write as a limit failure",
                LibsuRootShell.FOLDER_LIMIT_EXIT_CODE,
                run.exitStatus
        );
        assertEquals(
                "the oversized member keeps its original content",
                "old\n".repeat(20),
                new String(Files.readAllBytes(member), StandardCharsets.UTF_8)
        );
        String[] entries = root.toFile().list();
        assertNotNull(entries);
        assertEquals("the operation-private directory is removed again", 1, entries.length);
    }

    @Test
    public void ignoreListWriteFailsClosedWhenTheMemberIsSwappedWhileTheListIsBuilt() throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-swap-");
        Path outside = Files.createTempDirectory("standroid-ignore-swap-outside-");
        File tools = Files.createTempDirectory("standroid-ignore-swap-tools-").toFile();
        Path secret = outside.resolve("root-only.txt");
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Files.write(secret, "secret\n".getBytes(StandardCharsets.UTF_8));
        Files.write(member, "old\n".getBytes(StandardCharsets.UTF_8));
        writeRootId(tools);
        // The member is measured through its name before the replacement is decoded. This is what a
        // swap performed in that window looks like from the helper: the name the operation recorded
        // now resolves to a symbolic link out of the folder.
        writeExecutable(
                new File(tools, "base64"),
                "#!/bin/sh\n"
                        + "rm -f \"./" + LibsuRootShell.FOLDER_IGNORE_FILE_NAME + "\"\n"
                        + "ln -s \"" + secret + "\" \"./" + LibsuRootShell.FOLDER_IGNORE_FILE_NAME + "\"\n"
                        + "exec /usr/bin/base64 \"$@\"\n"
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).writeFolderIgnoreListScript(
                        root.toString(),
                        Base64.getEncoder().encodeToString("new\n".getBytes(StandardCharsets.UTF_8))
                ),
                tools,
                root.toFile()
        );

        assertEquals(
                "a member that changed while the list was built fails closed",
                LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE,
                run.exitStatus
        );
        assertTrue(
                "the replacement never follows the swapped member",
                Files.isSymbolicLink(member)
        );
        assertEquals(
                "the file the swapped member points at is never read or modified",
                "secret\n",
                new String(Files.readAllBytes(secret), StandardCharsets.UTF_8)
        );
        assertEquals(
                "nothing outside the configured folder is created",
                1,
                outside.toFile().list().length
        );
        assertEquals("the staged entry is removed again", 1, root.toFile().list().length);
    }

    @Test
    public void ignoreListWriteRefusesAStagedEntryReplacedWhileItIsMeasured()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-work-swap-");
        Path outside = Files.createTempDirectory("standroid-ignore-work-swap-outside-");
        File tools = Files.createTempDirectory("standroid-ignore-work-swap-tools-").toFile();
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Files.write(member, "old\n".getBytes(StandardCharsets.UTF_8));
        writeRootId(tools);
        // The member is measured before the staged entry exists, so this swap only takes effect on
        // the later call that measures the staged file through the descriptor the operation holds.
        writeExecutable(
                new File(tools, "wc"),
                "#!/bin/sh\n"
                        + "for standroid_entry in \"./" + LibsuRootShell.FOLDER_IGNORE_FILE_NAME
                        + ".standroid-\"*; do\n"
                        + "[ -e \"$standroid_entry\" ] || [ -L \"$standroid_entry\" ] || continue\n"
                        + "rm -f \"$standroid_entry\"\n"
                        + "ln -s \""+outside+"\" \"$standroid_entry\"\n"
                        + "done\n"
                        + "exec /usr/bin/wc \"$@\"\n"
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).writeFolderIgnoreListScript(
                        root.toString(),
                        Base64.getEncoder().encodeToString("new\n".getBytes(StandardCharsets.UTF_8))
                ),
                tools,
                root.toFile()
        );

        assertEquals(
                "a staged entry replaced by a symbolic link is refused",
                LibsuRootShell.FOLDER_ACCESS_EXIT_CODE,
                run.exitStatus
        );
        assertTrue("the refused operation reports no saved list", run.lines.isEmpty());
        assertEquals(
                "the member keeps the list it had",
                "old\n",
                new String(Files.readAllBytes(member), StandardCharsets.UTF_8)
        );
        assertEquals(
                "the directory the caller pointed the staged name at receives nothing",
                0,
                outside.toFile().list().length
        );
        assertEquals("the staged entry is removed again", 1, root.toFile().list().length);
    }

    /**
     * A caller that controls the folder replaces the staged entry with a file of its own that has the
     * same permissions and the same owner as the entry this operation created, so comparing the
     * attributes of the staged name cannot tell the two apart. The identity of the entry is bound to
     * the descriptor the operation opened, so the substituted file is still refused before the
     * rename: the member keeps the list it had, the file the caller planted is removed together with
     * the staged entry, and nothing outside the configured folder is created, read or removed.
     */
    @Test
    public void ignoreListWriteRefusesAStagedEntryThatCopiesTheAttributesOfTheStagedFile()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-forge-");
        Path outside = Files.createTempDirectory("standroid-ignore-forge-outside-");
        File tools = Files.createTempDirectory("standroid-ignore-forge-tools-").toFile();
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Files.write(member, "old\n".getBytes(StandardCharsets.UTF_8));
        writeRootId(tools);
        writeExecutable(
                new File(tools, "wc"),
                "#!/bin/sh\n"
                        + "for standroid_entry in \"./" + LibsuRootShell.FOLDER_IGNORE_FILE_NAME
                        + ".standroid-\"*; do\n"
                        + "[ -f \"$standroid_entry\" ] || continue\n"
                        + "rm -f \"$standroid_entry\"\n"
                        + "printf 'forged\\n' > \"$standroid_entry\"\n"
                        + "done\n"
                        + "exec /usr/bin/wc \"$@\"\n"
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).writeFolderIgnoreListScript(
                        root.toString(),
                        Base64.getEncoder().encodeToString("new\n".getBytes(StandardCharsets.UTF_8))
                ),
                tools,
                root.toFile()
        );

        assertEquals(
                "a staged entry whose attributes match but whose identity does not fails closed",
                LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE,
                run.exitStatus
        );
        assertTrue("the refused operation reports no saved list", run.lines.isEmpty());
        assertEquals(
                "the member keeps the list it had",
                "old\n",
                new String(Files.readAllBytes(member), StandardCharsets.UTF_8)
        );
        assertEquals(
                "nothing outside the configured folder is created",
                0,
                outside.toFile().list().length
        );
        assertEquals("the staged entry is removed again", 1, root.toFile().list().length);
    }

    @Test
    public void ignoreListWriteFailsClosedWhenTheMemberBecomesADirectoryBeforeTheReplace()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-target-");
        File tools = Files.createTempDirectory("standroid-ignore-target-tools-").toFile();
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Files.write(member, "old\n".getBytes(StandardCharsets.UTF_8));
        writeRootId(tools);
        // An identity that can create entries in the folder can replace the member with a
        // directory after the helper checked it. Toybox "mv" then moves the temporary file
        // inside that directory and still reports success, so the helper has to check what the
        // replace really did before it reports the list as saved.
        writeExecutable(
                new File(tools, "mv"),
                "#!/bin/sh\n"
                        + "shift\n"
                        + "rm -f \"$2\"\n"
                        + "mkdir \"$2\"\n"
                        + "/bin/mv -f \"$1\" \"$2\"\n"
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).writeFolderIgnoreListScript(
                        root.toString(),
                        Base64.getEncoder().encodeToString("new\n".getBytes(StandardCharsets.UTF_8))
                ),
                tools,
                root.toFile()
        );

        assertEquals(
                "a member that is no longer the file that was replaced fails closed",
                LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE,
                run.exitStatus
        );
        assertTrue(
                "the member stays the directory the other writer created",
                Files.isDirectory(member)
        );
        assertEquals(
                "no stray copy of the ignore list stays inside it",
                0,
                member.toFile().list().length
        );
        assertEquals("the operation-private directory is removed", 1, root.toFile().list().length);
    }

    @Test
    public void ignoreListWriteRefusesContentBeyondItsByteBudget() {
        assertEquals(
                FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                failureOf(() -> folderTransport(attachedShell()).writeFolderIgnoreList(
                        PRIVILEGED_FOLDER_ROOT,
                        new byte[LibsuRootShell.FOLDER_IGNORE_LIST_MAX_BYTES + 1]
                ))
        );
    }

    @Test
    public void folderScriptDispatchUsesStructuredArgumentsAndDiscardsScriptOutput() {
        String script = folderTransport(attachedShell())
                .folderScriptSetScript(PRIVILEGED_FOLDER_ROOT, SYNC_COMPLETE_EVENT);

        assertTrue("only the marker directory is searched",
                script.contains("/.stfolder") && script.contains("if [ ! -d \"$standroid_marker\" ]"));
        assertTrue("only shell scripts are approved",
                script.contains("*.sh|*.SH|*.sH|*.Sh) ;;"));
        assertTrue("the interpreter runs a constant command over the opened descriptor and is named "
                        + "after the script",
                script.contains("/system/bin/sh -c \". $standroid_fd_path\""
                        + " \"$standroid_script_path\" 'sync_complete' 3<&3"));
        assertTrue("the interpreter reads the opened descriptor instead of the pathname again",
                script.contains("exec 3< \"$standroid_script\"")
                        && script.contains("exec 3<&-")
                        && script.contains("standroid_opened_inode"));
        assertTrue("the descriptor is handed to every command that reads it",
                script.contains("ls -lniL \"$1\" 2>/dev/null 3<&3")
                        && script.contains("standroid_opened_file_inode"));
        assertTrue("the script runs with the folder as its working directory",
                script.contains("cd \"$standroid_folder\" || exit "));
        assertTrue("script output never reaches the transport result",
                script.contains(">/dev/null 2>&1"));
        assertTrue("every script reports its own exit status",
                script.contains("standroid_status=$?"));
        assertTrue("the size of the reports is counted while the dispatch runs",
                script.contains("standroid_output=0")
                        && script.contains("standroid_output=$(( standroid_output + "
                        + "${#standroid_encoded} + ${#standroid_status} + 2 ))"));
        assertTrue("a dispatch that outgrows its report budget stops with the limit status",
                script.contains("[ \"$standroid_output\" -le "
                        + LibsuRootShell.FOLDER_SCRIPT_MAX_OUTPUT_CHARS + " ] || exit "
                        + LibsuRootShell.FOLDER_LIMIT_EXIT_CODE));
        assertFalse("the interpreter command string never carries the script path or its text",
                script.contains("-c \"$standroid_script") || script.contains("-c \"$(cat"));
        assertFalse("the dispatch never scans for scripts with find or head",
                script.contains("find ") || script.contains("head "));
    }

    @Test
    public void scriptDispatchNamesTheInterpreterAfterTheScriptInsideTheConfiguredFolder()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-script-name-");
        File tools = Files.createTempDirectory("standroid-script-name-tools-").toFile();
        Path marker = root.resolve(LibsuRootShell.FOLDER_SCRIPT_DIRECTORY_NAME);
        Files.createDirectories(marker);
        writeRootId(tools);
        writeToyboxBase64(tools);
        Path script = marker.resolve("report-arguments.sh");
        writeExecutable(
                script.toFile(),
                "#!/bin/sh\nprintf '%s|%s' \"$0\" \"$1\" > \"$PWD/witness-arguments\"\n"
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell())
                        .folderScriptSetScript(root.toString(), SYNC_COMPLETE_EVENT)
                        .replace("/system/bin/sh", "/bin/sh"),
                tools,
                root.toFile()
        );

        assertEquals(0, run.exitStatus);
        assertEquals(
                "a script is named after its own path inside the configured folder and receives the "
                        + "event as its only argument",
                script + "|" + SYNC_COMPLETE_EVENT,
                new String(
                        Files.readAllBytes(root.resolve("witness-arguments")),
                        StandardCharsets.UTF_8
                )
        );
    }

    @Test
    public void scriptDispatchReportsTheExitStatusOfEveryApprovedScript() throws Exception {
        ScriptedLibsuShell shell = attachedShell().scriptJobResult(
                0,
                encoded("01-first.sh") + " 0",
                encoded("02-second.sh") + " 3"
        );

        List<FolderScriptOutcome> outcomes = folderTransport(shell)
                .runFolderScriptSet(PRIVILEGED_FOLDER_ROOT, SYNC_COMPLETE_EVENT);

        assertEquals(
                Arrays.asList(
                        FolderScriptOutcome.of("01-first.sh", 0),
                        FolderScriptOutcome.of("02-second.sh", 3)
                ),
                outcomes
        );
    }

    @Test
    public void scriptDispatchReportsItsDeadlineAsATypedDispatchFailure() throws Exception {
        ScriptedLibsuShell shell = attachedShell().scriptJobResult(124);

        assertEquals(
                FolderOperationFailure.SCRIPT_DISPATCH_FAILED,
                failureOf(() -> folderTransport(shell)
                        .runFolderScriptSet(PRIVILEGED_FOLDER_ROOT, SYNC_COMPLETE_EVENT))
        );
    }

    @Test
    public void scriptDispatchRejectsAnUnsafeReportedScriptName() {
        String[] names = {"../escape.sh", "nested/evil.sh", "/etc/passwd"};

        for (String name : names) {
            ScriptedLibsuShell shell = attachedShell().scriptJobResult(0, encoded(name) + " 0");

            assertEquals(
                    "a reported script name outside the marker directory fails the dispatch: "
                            + name,
                    FolderOperationFailure.SCRIPT_DISPATCH_FAILED,
                    failureOf(() -> folderTransport(shell)
                            .runFolderScriptSet(PRIVILEGED_FOLDER_ROOT, SYNC_COMPLETE_EVENT))
            );
        }
    }

    @Test
    public void scriptDispatchAcceptsALineBreakInsideAReportedScriptName() throws Exception {
        String name = "line" + (char) 10 + "break.sh";
        ScriptedLibsuShell shell = attachedShell().scriptJobResult(0, encoded(name) + " 0");

        List<FolderScriptOutcome> outcomes = folderTransport(shell)
                .runFolderScriptSet(PRIVILEGED_FOLDER_ROOT, SYNC_COMPLETE_EVENT);

        assertEquals(1, outcomes.size());
        assertEquals("a file name may contain a line break", name, outcomes.get(0).scriptName());
        assertEquals(0, outcomes.get(0).exitStatus());
    }

    @Test
    public void scriptDispatchFailsWhenItsReportExceedsTheOutputBudget() {
        ScriptedLibsuShell shell = attachedShell().scriptJobResult(
                0, "A".repeat(LibsuRootShell.FOLDER_SCRIPT_MAX_OUTPUT_CHARS + 1)
        );

        assertEquals(
                FolderOperationFailure.SCRIPT_DISPATCH_FAILED,
                failureOf(() -> folderTransport(shell)
                        .runFolderScriptSet(PRIVILEGED_FOLDER_ROOT, SYNC_COMPLETE_EVENT))
        );
    }

    @Test
    public void scriptDispatchKeepsItsOwnDeadlineIndependentOfTheHelperBound() {
        assertEquals(
                "a sync-completion script may run for five minutes",
                300_000L,
                LibsuRootShell.SCRIPT_DISPATCH_TIMEOUT_MILLIS
        );
        assertEquals(
                "every other privileged operation keeps the helper-operation bound",
                15_000L,
                LibsuRootShell.OPERATION_TIMEOUT_MILLIS
        );
        assertTrue(
                "the dispatch deadline is strictly longer than the helper-operation bound",
                LibsuRootShell.SCRIPT_DISPATCH_TIMEOUT_MILLIS
                        > LibsuRootShell.OPERATION_TIMEOUT_MILLIS
        );
    }

    @Test
    public void ioPriorityUsesTheSupportedIoniceFormForOneExactProcessIdentifier() {
        String script = folderTransport(attachedShell()).ioPriorityScript(4321, 987654);

        assertTrue("Android supports the best-effort class and level on one process",
                script.contains("ionice -c " + LibsuRootShell.IO_PRIORITY_CLASS
                        + " -n " + LibsuRootShell.IO_PRIORITY_LEVEL
                        + " -p \"$standroid_pid\""));
        assertTrue("a device without the command reports itself as not applicable",
                script.contains("command -v ionice"));
        assertTrue("a device without the command never reports success",
                script.contains("exit " + LibsuRootShell.TUNING_NOT_APPLICABLE_EXIT_CODE));
        assertTrue("the command reads the recorded process' own stat entry",
                script.contains("cat '/proc/4321/stat'"));
        assertTrue("the recorded start time is the evidence the command compares",
                script.contains("= '987654'"));
        assertTrue("a process that no longer matches the recorded start time is refused",
                script.contains("exit " + LibsuRootShell.TUNING_IDENTITY_MISMATCH_EXIT_CODE));
        int verification = script.indexOf("cat '/proc/4321/stat'");
        assertTrue("the start time is verified before the command targets the identifier",
                verification > 0 && verification < script.indexOf("ionice -c "));
        for (String forbidden : new String[]{"pgrep", "pkill", "pidof"}) {
            assertFalse("no process may be selected by anything but its identifier: " + forbidden,
                    script.contains(forbidden));
        }
    }

    @Test
    public void ioPriorityReportsNotApplicableWhenTheDeviceHasNoIonice() throws Exception {
        ExecutionIdentity execution = ownedExecution(4321, 987654);

        assertEquals(
                TuningOutcome.Status.NOT_APPLICABLE,
                folderTransport(attachedShell()
                        .scriptJobResult(LibsuRootShell.TUNING_NOT_APPLICABLE_EXIT_CODE))
                        .applyIoPriority(execution)
                        .status()
        );
        assertEquals(
                "a process that no longer matches the recorded execution is never reported as tuned",
                TuningOutcome.Status.NOT_APPLICABLE,
                folderTransport(attachedShell()
                        .scriptJobResult(LibsuRootShell.TUNING_IDENTITY_MISMATCH_EXIT_CODE))
                        .applyIoPriority(execution)
                        .status()
        );
        assertEquals(
                TuningOutcome.Status.APPLIED,
                folderTransport(attachedShell().scriptJobResult(0))
                        .applyIoPriority(execution)
                        .status()
        );
    }

    @Test
    public void ioPriorityVerifiesTheRecordedStartTimeBeforeItTunesTheProcess() throws Exception {
        Path root = Files.createTempDirectory("standroid-io-priority-");
        File tools = Files.createTempDirectory("standroid-io-priority-tools-").toFile();
        writeRootId(tools);
        File ran = new File(root.toFile(), "ionice-arguments.txt");
        writeExecutable(
                new File(tools, "ionice"),
                "#!/bin/sh\nprintf '%s\\n' \"$*\" > \"" + ran.getAbsolutePath() + "\"\n"
        );
        Path stat = root.resolve("stat");
        Files.write(stat, TUNING_STAT_LINE.getBytes(StandardCharsets.UTF_8));
        String script = folderTransport(attachedShell())
                .ioPriorityScript(4321, 987654)
                .replace("'/proc/4321/stat'", "'" + stat.toAbsolutePath() + "'");

        ScriptRun matched = runFolderScript(script, tools, root.toFile());

        assertEquals("a process that still matches the recorded start time is tuned",
                0, matched.exitStatus);
        assertEquals("the command targets the exact identifier it re-verified",
                "-c 2 -n 7 -p 4321",
                new String(Files.readAllBytes(ran.toPath()), StandardCharsets.UTF_8).trim());

        Files.delete(ran.toPath());
        String reusedIdentifier = script.replace("'987654'", "'987655'");
        ScriptRun reused = runFolderScript(reusedIdentifier, tools, root.toFile());

        assertEquals("a reused identifier is refused instead of tuned",
                LibsuRootShell.TUNING_IDENTITY_MISMATCH_EXIT_CODE,
                reused.exitStatus);
        assertFalse("a refused identifier never reaches the tuner", ran.exists());

        String unreadable = script.replace(
                "'" + stat.toAbsolutePath() + "'",
                "'" + root.resolve("absent").toAbsolutePath() + "'"
        );
        ScriptRun missing = runFolderScript(unreadable, tools, root.toFile());

        assertEquals("an unreadable stat entry is refused instead of tuned",
                LibsuRootShell.TUNING_IDENTITY_MISMATCH_EXIT_CODE,
                missing.exitStatus);
        assertFalse("an unreadable entry never reaches the tuner", ran.exists());
    }

    @Test
    public void inotifyWatchLimitScriptRaisesTheLimitAndReadsItBack() {
        String script = folderTransport(attachedShell())
                .inotifyWatchLimitScript(InotifyWatchLimit.TARGET);

        assertTrue("the command targets one fixed kernel file",
                script.contains("/proc/sys/fs/inotify/max_user_watches"));
        assertTrue("a limit that is already sufficient succeeds without a write",
                script.contains("-ge " + InotifyWatchLimit.TARGET));
        assertTrue("the limit is raised to the approved target",
                script.contains("echo " + InotifyWatchLimit.TARGET + " >"));
        assertTrue("a write the kernel silently rejected never reports success",
                script.contains("standroid_applied="));
        assertTrue("a device without the limit reports itself as not applicable",
                script.contains("exit " + LibsuRootShell.TUNING_NOT_APPLICABLE_EXIT_CODE));
        int sufficientCheck = script.indexOf("-ge " + InotifyWatchLimit.TARGET);
        int writabilityProbe = script.indexOf("-w ");
        assertTrue("an already sufficient limit is decided before the writability probe",
                sufficientCheck >= 0 && writabilityProbe > sufficientCheck);
    }

    @Test
    public void inotifyTuningReportsNotApplicableAndAppliedAsItsOwnOutcomes() throws Exception {
        assertEquals(
                TuningOutcome.Status.NOT_APPLICABLE,
                folderTransport(attachedShell()
                        .scriptJobResult(LibsuRootShell.TUNING_NOT_APPLICABLE_EXIT_CODE))
                        .applyInotifyWatchLimit(InotifyWatchLimit.TARGET)
                        .status()
        );
        assertEquals(
                TuningOutcome.Status.APPLIED,
                folderTransport(attachedShell().scriptJobResult(0))
                        .applyInotifyWatchLimit(InotifyWatchLimit.TARGET)
                        .status()
        );
    }

    @Test
    public void inotifyWatchLimitScriptAcceptsAnAlreadySufficientLimitWithoutWriteAccess()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-inotify-");
        File tools = Files.createTempDirectory("standroid-inotify-tools-").toFile();
        writeRootId(tools);
        File limit = writeWatchLimit(root.toFile(), "999999");
        assertTrue(limit.setReadOnly());
        Assume.assumeFalse(
                "this case needs a test user the file mode can block",
                limit.canWrite()
        );

        ScriptRun run = runFolderScript(inotifyWatchLimitScriptFor(limit), tools, root.toFile());

        assertEquals("an already sufficient limit is success", 0, run.exitStatus);
        assertEquals("the sufficient limit is left alone", "999999", readWatchLimit(limit));
    }

    @Test
    public void inotifyWatchLimitScriptReportsNotApplicableWhenAnInsufficientLimitIsLocked()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-inotify-");
        File tools = Files.createTempDirectory("standroid-inotify-tools-").toFile();
        writeRootId(tools);
        File limit = writeWatchLimit(root.toFile(), "7");
        assertTrue(limit.setReadOnly());
        Assume.assumeFalse(
                "this case needs a test user the file mode can block",
                limit.canWrite()
        );

        ScriptRun run = runFolderScript(inotifyWatchLimitScriptFor(limit), tools, root.toFile());

        assertEquals(
                "a limit the kernel refuses to raise reports itself as not applicable",
                LibsuRootShell.TUNING_NOT_APPLICABLE_EXIT_CODE,
                run.exitStatus
        );
        assertEquals("the locked limit is left alone", "7", readWatchLimit(limit));
    }

    @Test
    public void inotifyWatchLimitScriptRaisesAnInsufficientLimitAndReadsItBack() throws Exception {
        Path root = Files.createTempDirectory("standroid-inotify-");
        File tools = Files.createTempDirectory("standroid-inotify-tools-").toFile();
        writeRootId(tools);
        File limit = writeWatchLimit(root.toFile(), "7");

        ScriptRun run = runFolderScript(inotifyWatchLimitScriptFor(limit), tools, root.toFile());

        assertEquals("the raised limit reports success", 0, run.exitStatus);
        assertEquals(
                "the script read the new value back",
                String.valueOf(InotifyWatchLimit.TARGET),
                readWatchLimit(limit)
        );
    }

    @Test
    public void inotifyWatchLimitScriptRefusesAValueItCannotReasonAbout() throws Exception {
        Path root = Files.createTempDirectory("standroid-inotify-");
        File tools = Files.createTempDirectory("standroid-inotify-tools-").toFile();
        writeRootId(tools);
        File limit = writeWatchLimit(root.toFile(), "not a number");

        ScriptRun run = runFolderScript(inotifyWatchLimitScriptFor(limit), tools, root.toFile());

        assertEquals(
                "a value that cannot be compared is never overwritten",
                LibsuRootShell.FOLDER_ACCESS_EXIT_CODE,
                run.exitStatus
        );
        assertEquals("the value is left alone", "not a number", readWatchLimit(limit));
    }

    @Test
    public void tuningRefusesIdentifiersThatCannotNameATarget() throws Exception {
        LibsuRootShell transport = folderTransport(attachedShell());

        try {
            transport.applyIoPriority(null);
            fail("a tuning request without an owned execution cannot name a target");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("owned execution"));
        }
        try {
            new ExecutionIdentity(0, 1, "boot-id", "/data/app/libsyncthing.so", "run-token");
            fail("a non-positive process identifier cannot name an owned execution");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("positive"));
        }
        for (int invalid : new int[]{0, -1}) {
            try {
                transport.applyInotifyWatchLimit(invalid);
                fail("a non-positive watch limit cannot be applied");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("positive"));
            }
        }
    }

    @Test
    public void conflictScanKeepsOneRecordPerNameWhenBase64WrapsItsOutput() throws Exception {
        Path root = Files.createTempDirectory("standroid-conflict-transport-");
        File tools = Files.createTempDirectory("standroid-conflict-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        List<String> names = Arrays.asList(
                "a-conflict-name-that-is-longer-than-the-wrap-column"
                        + ".sync-conflict-20260102-150405-abcDEFG.txt",
                "nested/deeper/still/deep.sync-conflict-20260102-150405-abcDEFG.md",
                "a name with  spaces.sync-conflict-20260102-150405-abcDEFG.txt",
                "unicode-" + (char) 0x00fc + (char) 0x4f60 + (char) 0x597d
                        + ".sync-conflict-20260102-150405-abcDEFG.txt",
                "quoted" + (char) 34 + "and" + (char) 39
                        + "single.sync-conflict-20260102-150405-abcDEFG.txt"
        );
        for (String name : names) {
            Path target = root.resolve(name);
            Files.createDirectories(target.getParent());
            Files.write(target, new byte[] {1});
        }
        String withLineBreak = "line" + (char) 10 + "break"
                + ".sync-conflict-20260102-150405-abcDEFG.txt";
        Files.write(root.resolve(withLineBreak), new byte[] {1});

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).conflictDiscoveryScript(root.toString()),
                tools,
                root.toFile()
        );

        List<String> expected = new ArrayList<>(names);
        expected.add(withLineBreak);
        assertEquals("the scan reports success", 0, run.exitStatus);
        assertEquals(
                "every name travels as exactly one record, even when the encoder wraps its output",
                sorted(expected),
                sorted(decodeRecords(run))
        );
    }

    @Test
    public void scriptReportKeepsOneRecordPerScriptWhenBase64WrapsItsOutput() throws Exception {
        Path root = Files.createTempDirectory("standroid-script-transport-");
        File tools = Files.createTempDirectory("standroid-script-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        Path marker = Files.createDirectories(
                root.resolve(LibsuRootShell.FOLDER_SCRIPT_DIRECTORY_NAME)
        );
        String longName = "a-script-name-that-is-longer-than-the-wrap-column.sh";
        Files.write(marker.resolve(longName), "exit 3".getBytes(StandardCharsets.UTF_8));
        Files.write(marker.resolve("second name.sh"), "exit 0".getBytes(StandardCharsets.UTF_8));

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell())
                        .folderScriptSetScript(root.toString(), SYNC_COMPLETE_EVENT),
                tools,
                root.toFile()
        );

        List<String> reported = new ArrayList<>();
        for (String line : run.lines) {
            int separator = line.indexOf(' ');
            assertTrue("every record carries an exit status", separator > 0);
            reported.add(new String(
                    Base64.getDecoder().decode(line.substring(0, separator)),
                    StandardCharsets.UTF_8
            ));
        }
        assertEquals("the dispatch reports success", 0, run.exitStatus);
        assertEquals(
                Arrays.asList(longName, "second name.sh"),
                sorted(reported)
        );
    }

    @Test
    public void conflictScanEnforcesItsEntryBudgetWhileItWalks() throws Exception {
        Path root = Files.createTempDirectory("standroid-conflict-entries-");
        File tools = Files.createTempDirectory("standroid-conflict-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        for (int index = 0; index < 250; index++) {
            Files.write(
                    root.resolve(
                            "entry-" + index + ".sync-conflict-20260102-150405-abcDEFG.txt"
                    ),
                    new byte[] {1}
            );
        }
        // Creating two hundred thousand files is not a test the suite can afford, so the same
        // enforcement expression runs with a smaller budget against one large directory.
        String script = folderTransport(attachedShell())
                .conflictDiscoveryScript(root.toString())
                .replace(String.valueOf(LibsuRootShell.FOLDER_SCAN_MAX_ENTRIES), "200");

        ScriptRun run = runFolderScript(script, tools, root.toFile());

        assertEquals(
                "the walk stops with the limit status instead of reporting a shorter list",
                LibsuRootShell.FOLDER_LIMIT_EXIT_CODE,
                run.exitStatus
        );
    }

    @Test
    public void conflictScanRefusesAnOversizedDirectoryBeforeItExpandsAnyEntry()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-conflict-preexpand-");
        File tools = Files.createTempDirectory("standroid-conflict-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        // One matching entry sorts first, so a walk that expanded this directory
        // would have reported it before its entry budget tripped.
        Files.write(
                root.resolve("aaa.sync-conflict-20260102-150405-abcDEFG.txt"),
                new byte[] {1}
        );
        for (int index = 0; index < 250; index++) {
            Files.write(root.resolve("entry-" + index + ".txt"), new byte[] {1});
        }
        String script = folderTransport(attachedShell())
                .conflictDiscoveryScript(root.toString())
                .replace(String.valueOf(LibsuRootShell.FOLDER_SCAN_MAX_ENTRIES), "200");

        ScriptRun run = runFolderScript(script, tools, root.toFile());

        assertEquals(
                "the oversized directory fails the walk as a limit failure",
                LibsuRootShell.FOLDER_LIMIT_EXIT_CODE,
                run.exitStatus
        );
        assertTrue(
                "the walk refuses the directory before it expands any entry",
                decodeRecords(run).isEmpty()
        );
    }

    @Test
    public void conflictScanDoesNotSpendItsEntryBudgetOnTheDotEntriesOfADirectory()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-conflict-dots-");
        File tools = Files.createTempDirectory("standroid-conflict-dots-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        Files.write(root.resolve("one.txt"), new byte[] {1});
        Files.write(root.resolve("two.txt"), new byte[] {1});
        Files.write(root.resolve("three.txt"), new byte[] {1});
        // The directory holds three entries while "ls -a" lists five lines for it, so a budget of
        // four has to survive a listing that also carries the two dot entries.
        String script = folderTransport(attachedShell())
                .conflictDiscoveryScript(root.toString())
                .replace(String.valueOf(LibsuRootShell.FOLDER_SCAN_MAX_ENTRIES), "4");

        ScriptRun run = runFolderScript(script, tools, root.toFile());

        assertEquals(
                "the two dot entries of the listing are not entries of the walk",
                0,
                run.exitStatus
        );
    }

    @Test
    public void conflictScanChecksItsTimeBudgetAgainAfterItListsADirectory() throws Exception {
        Path root = Files.createTempDirectory("standroid-conflict-listing-");
        File tools = Files.createTempDirectory("standroid-conflict-listing-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        writeBudgetExhaustingDate(tools, new File(tools, "date-readings"));
        Files.write(root.resolve("plain.txt"), new byte[] {1});

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).conflictDiscoveryScript(root.toString()),
                tools,
                root.toFile()
        );

        assertEquals(
                "a directory listing that outran the budget fails the walk as a limit failure",
                LibsuRootShell.FOLDER_LIMIT_EXIT_CODE,
                run.exitStatus
        );
        assertTrue(
                "the refused directory reports no record",
                decodeRecords(run).isEmpty()
        );
    }

    @Test
    public void conflictScanRefusesADirectoryReplacedByASymbolicLinkBeforeItDescends() throws Exception {
        Path root = Files.createTempDirectory("standroid-conflict-swap-");
        Path outside = Files.createTempDirectory("standroid-conflict-swap-outside-");
        File tools = Files.createTempDirectory("standroid-conflict-swap-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        Path swapped = root.resolve("swapped");
        Files.createDirectories(swapped);
        Files.write(
                outside.resolve("outside.sync-conflict-20260102-150405-abcDEFG.txt"),
                new byte[] {1}
        );
        // The walk records the identity of the entry it is about to enter and then checks that
        // entry again before it descends. The exchange lands between those two steps, so the entry
        // is a symbolic link by the time the walk would enter it.
        writeEntryExchangingLs(
                tools,
                "./swapped",
                "rmdir '" + swapped + "' && ln -s '" + outside + "' '" + swapped + "'"
        );
        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).conflictDiscoveryScript(root.toString()),
                tools,
                root.toFile()
        );
        assertEquals(
                "a directory exchanged for a symbolic link fails the walk as an unsafe member",
                LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE,
                run.exitStatus
        );
        assertTrue(
                "the exchange has to happen for this test to mean anything",
                Files.isSymbolicLink(swapped)
        );
        assertTrue(
                "no record may be read through the exchanged entry",
                decodeRecords(run).isEmpty()
        );
    }

    @Test
    public void conflictScanRefusesAConfiguredFolderReplacedByASymbolicLinkBeforeItEnters() throws Exception {
        Path parent = Files.createTempDirectory("standroid-conflict-folder-swap-");
        Path folder = parent.resolve("folder");
        Files.createDirectories(folder);
        Path outside = Files.createTempDirectory("standroid-conflict-folder-swap-outside-");
        File tools = Files.createTempDirectory("standroid-conflict-folder-swap-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        Files.write(
                outside.resolve("leak.sync-conflict-20260102-150405-abcDEFG.txt"),
                new byte[] {1}
        );
        // The exchange lands after the walk recorded the identity of the configured folder but
        // before it entered that folder, so the working directory it reaches belongs to the link
        // target instead of the recorded object.
        writeEntryExchangingLs(
                tools,
                folder.toString(),
                "mv '" + folder + "' '" + parent.resolve("moved") + "'"
                        + " && ln -s '" + outside + "' '" + folder + "'"
        );
        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).conflictDiscoveryScript(folder.toString()),
                tools,
                parent.toFile()
        );
        assertEquals(
                "a configured folder exchanged for a symbolic link fails the walk as unsafe",
                LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE,
                run.exitStatus
        );
        assertTrue(
                "the exchange has to happen for this test to mean anything",
                Files.isSymbolicLink(folder)
        );
        assertTrue("no record may come from the exchanged folder", decodeRecords(run).isEmpty());
    }

    @Test
    public void conflictScanRefusesAConfiguredFolderThatIsASymbolicLink() throws Exception {
        Path real = Files.createTempDirectory("standroid-conflict-folder-link-real-");
        Path parent = Files.createTempDirectory("standroid-conflict-folder-link-");
        Path link = parent.resolve("folder");
        Files.createSymbolicLink(link, real);
        File tools = Files.createTempDirectory("standroid-conflict-folder-link-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        Files.write(
                real.resolve("entry.sync-conflict-20260102-150405-abcDEFG.txt"),
                new byte[] {1}
        );
        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).conflictDiscoveryScript(link.toString()),
                tools,
                parent.toFile()
        );
        assertEquals(
                "a configured folder that is a symbolic link is refused instead of followed",
                LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE,
                run.exitStatus
        );
        assertTrue(
                "the walk may not report a member reached through the configured link",
                decodeRecords(run).isEmpty()
        );
    }

    @Test
    public void conflictScanDropsAMemberExchangedForASymbolicLinkWhileItIsEncoded() throws Exception {
        Path root = Files.createTempDirectory("standroid-conflict-member-swap-");
        Path outside = Files.createTempDirectory("standroid-conflict-member-swap-outside-");
        File tools = Files.createTempDirectory("standroid-conflict-member-swap-tools-").toFile();
        writeRootId(tools);
        String name = "member.sync-conflict-20260102-150405-abcDEFG.txt";
        Path member = root.resolve(name);
        Files.write(member, new byte[] {1});
        Path outsideMember = outside.resolve(name);
        Files.write(outsideMember, new byte[] {2});
        // The exchange lands while the walk encodes the member, which is the last step before its
        // record is written; the record is dropped instead of naming the link target.
        writeMemberExchangingBase64(tools, member.toFile(), outsideMember.toFile());
        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).conflictDiscoveryScript(root.toString()),
                tools,
                root.toFile()
        );
        assertEquals(0, run.exitStatus);
        assertTrue(
                "the exchange has to happen for this test to mean anything",
                Files.isSymbolicLink(member)
        );
        assertTrue(
                "a member exchanged for a symbolic link is dropped instead of reported",
                decodeRecords(run).isEmpty()
        );
    }

    @Test
    public void conflictScanDescendsDeeplyNestedDirectories() throws Exception {
        Path root = Files.createTempDirectory("standroid-conflict-deep-");
        File tools = Files.createTempDirectory("standroid-conflict-deep-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        StringBuilder relative = new StringBuilder();
        Path directory = root;
        String name = "deep.sync-conflict-20260102-150405-abcDEFG.txt";
        for (int level = 0; level < 24; level++) {
            directory = directory.resolve("level" + level);
            relative.append("level").append(level).append("/");
        }
        Files.createDirectories(directory);
        Files.write(directory.resolve(name), new byte[] {1});
        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).conflictDiscoveryScript(root.toString()),
                tools,
                root.toFile()
        );
        assertEquals(0, run.exitStatus);
        assertEquals(
                "every level is entered through the identity of the name inside its pinned parent",
                Collections.singletonList(relative + name),
                decodeRecords(run)
        );
    }

    @Test
    public void conflictScanReportsPathsRelativeToTheConfiguredFolder() throws Exception {
        Path root = Files.createTempDirectory("standroid-conflict-relative-");
        File tools = Files.createTempDirectory("standroid-conflict-relative-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        Files.createDirectories(root.resolve("sub"));
        String name = "entry.sync-conflict-20260102-150405-abcDEFG.txt";
        Files.write(root.resolve("sub").resolve(name), new byte[] {1});
        String script = folderTransport(attachedShell()).conflictDiscoveryScript(root.toString());
        ScriptRun run = runFolderScript(script, tools, root.toFile());
        assertEquals(0, run.exitStatus);
        assertEquals(
                "a record names the member relative to the folder, whatever that folder is",
                Collections.singletonList("sub/" + name),
                decodeRecords(run)
        );
        assertTrue(
                "the walk lists the pinned working directory, not a path built from the folder",
                script.contains("standroid_listed=.")
        );
        assertFalse(
                "no entry name may be built from the configured path",
                script.contains("\"$standroid_folder\"/*")
        );
    }

    @Test
    public void conflictScanSkipsOnlyTheVersioningDirectoryOfTheFolderRoot() throws Exception {
        Path root = Files.createTempDirectory("standroid-conflict-versioning-");
        File tools = Files.createTempDirectory("standroid-conflict-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        String conflict = ".sync-conflict-20260102-150405-abcDEFG.txt";
        // The versioning directory of the folder itself is not user content, so it is skipped.
        Path versioned = root.resolve(".stversions/versioned" + conflict);
        Files.createDirectories(versioned.getParent());
        Files.write(versioned, new byte[] {1});
        // An ordinary nested directory that merely carries the same name still holds user files,
        // so a conflict inside it must be reported like any other conflict.
        Path nested = root.resolve("nested/.stversions/nested" + conflict);
        Files.createDirectories(nested.getParent());
        Files.write(nested, new byte[] {1});

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).conflictDiscoveryScript(root.toString()),
                tools,
                root.toFile()
        );

        assertEquals("the scan reports success", 0, run.exitStatus);
        assertEquals(
                "only the versioning directory of the folder itself is skipped",
                sorted(Collections.singletonList("nested/.stversions/nested" + conflict)),
                sorted(decodeRecords(run))
        );
    }

    @Test
    public void conflictScanWalksTheFilesystemRootThroughASingleSlash() throws Exception {
        File tools = Files.createTempDirectory("standroid-conflict-slash-tools-").toFile();
        writeRootId(tools);
        writeBudgetExhaustingDate(tools, new File(tools, "date-readings"));
        String script = folderTransport(attachedShell()).conflictDiscoveryScript("/");
        // A folder configured as "/" cannot be walked by a test. The scan is stopped by its
        // wall-clock budget right after the first listing, so reaching that budget shows that
        // the walk accepted and pinned the filesystem root and descends nothing from it.
        ScriptRun run = runFolderScript(script, tools, new File("/"));
        assertEquals(
                "the filesystem root is pinned and then bounded by the wall-clock budget",
                LibsuRootShell.FOLDER_LIMIT_EXIT_CODE,
                run.exitStatus
        );
        assertTrue(
                "a scan that stopped on its budget reports no record",
                decodeRecords(run).isEmpty()
        );
        assertTrue(
                "the records are relative to the pinned working directory",
                script.contains("${standroid_entry#./}")
        );
        assertFalse(
                "the walk must not glob the configured path itself",
                script.contains("\"$standroid_folder\"/*")
        );
    }
    @Test
    public void conflictScanChecksItsTimeBudgetInsideTheEntryLoop() throws Exception {
        Path root = Files.createTempDirectory("standroid-conflict-stride-");
        File tools = Files.createTempDirectory("standroid-conflict-stride-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        File readings = new File(tools, "date-readings");
        writeExecutable(
                new File(tools, "date"),
                "#!/bin/sh\n"
                        + "standroid_reading=$(cat \"" + readings.getAbsolutePath()
                        + "\" 2>/dev/null || echo 0)\n"
                        + "standroid_reading=$(( standroid_reading + 1 ))\n"
                        + "echo \"$standroid_reading\" > \"" + readings.getAbsolutePath() + "\"\n"
                        + "if [ \"$standroid_reading\" -le 3 ]; then echo 0; else echo 9999999; fi\n"
        );
        for (int index = 0; index < 6; index++) {
            Files.write(root.resolve("entry-" + index + ".txt"), new byte[] {1});
        }
        // One wide directory cannot afford one date call per entry, so the same enforcement
        // expression runs with a smaller stride.
        String script = folderTransport(attachedShell())
                .conflictDiscoveryScript(root.toString())
                .replace(
                        String.valueOf(LibsuRootShell.FOLDER_SCAN_TIME_CHECK_STRIDE),
                        "2"
                );

        ScriptRun run = runFolderScript(script, tools, root.toFile());

        assertEquals(
                "a directory that outruns the deadline fails the walk as a limit failure",
                LibsuRootShell.FOLDER_LIMIT_EXIT_CODE,
                run.exitStatus
        );
        assertEquals(
                "the deadline was read once per directory and once more inside the entry loop",
                "4",
                new String(Files.readAllBytes(readings.toPath()), StandardCharsets.UTF_8).trim()
        );
    }

    @Test
    public void conflictScanEnforcesItsOutputBudgetWhileItWalks() throws Exception {
        Path root = Files.createTempDirectory("standroid-conflict-output-");
        File tools = Files.createTempDirectory("standroid-conflict-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        for (int index = 0; index < 6; index++) {
            Files.write(
                    root.resolve(
                            "entry-" + index + "-padding-padding-padding-padding"
                                    + ".sync-conflict-20260102-150405-abcDEFG.txt"
                    ),
                    new byte[] {1}
            );
        }
        String script = folderTransport(attachedShell())
                .conflictDiscoveryScript(root.toString())
                .replace(String.valueOf(LibsuRootShell.FOLDER_SCAN_MAX_OUTPUT_CHARS), "80");

        ScriptRun run = runFolderScript(script, tools, root.toFile());

        assertEquals(
                "the walk stops with the limit status instead of returning a partial list",
                LibsuRootShell.FOLDER_LIMIT_EXIT_CODE,
                run.exitStatus
        );
        int reportedCharacters = 0;
        for (String line : run.lines) {
            reportedCharacters += line.length();
        }
        assertTrue(
                "the transport never holds more output than the budget allows; it held "
                        + reportedCharacters + " characters",
                reportedCharacters <= 80
        );
    }

    @Test
    public void scriptDispatchEnforcesItsOutputBudgetWhileItReports() throws Exception {
        Path root = Files.createTempDirectory("standroid-script-budget-");
        File tools = Files.createTempDirectory("standroid-script-budget-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        Path marker = Files.createDirectories(
                root.resolve(LibsuRootShell.FOLDER_SCRIPT_DIRECTORY_NAME)
        );
        for (int index = 0; index < 40; index++) {
            Files.write(
                    marker.resolve("delivered-script-" + index + ".sh"),
                    "exit 0".getBytes(StandardCharsets.UTF_8)
            );
        }
        // A folder holding the thousands of scripts the budget allows is not a test the suite can
        // afford, so the same enforcement expression runs with a smaller budget.
        String script = folderTransport(attachedShell())
                .folderScriptSetScript(root.toString(), SYNC_COMPLETE_EVENT)
                .replace(String.valueOf(LibsuRootShell.FOLDER_SCRIPT_MAX_OUTPUT_CHARS), "160");

        ScriptRun run = runFolderScript(script, tools, root.toFile());

        assertEquals(
                "the dispatch stops with the limit status instead of reporting a partial result",
                LibsuRootShell.FOLDER_LIMIT_EXIT_CODE,
                run.exitStatus
        );
        int reportedCharacters = 0;
        for (String line : run.lines) {
            reportedCharacters += line.length();
        }
        assertTrue(
                "the transport never holds more reports than the budget allows; it held "
                        + reportedCharacters + " characters",
                reportedCharacters <= 160
        );
    }

    @Test
    public void conflictScanEnforcesItsTimeBudgetWhileItWalks() throws Exception {
        Path root = Files.createTempDirectory("standroid-conflict-time-");
        File tools = Files.createTempDirectory("standroid-conflict-time-tools-").toFile();
        writeRootId(tools);
        writeWrappingBase64(tools);
        File counter = new File(tools, "date-count");
        writeExecutable(
                new File(tools, "date"),
                "#!/bin/sh\n"
                        + "count=$(cat '" + counter.getAbsolutePath() + "' 2>/dev/null || echo 0)\n"
                        + "count=$(( count + 1 ))\n"
                        + "echo $count > '" + counter.getAbsolutePath() + "'\n"
                        + "[ $count -eq 1 ] && echo 0 || echo 1000\n"
        );
        Files.write(
                root.resolve("entry.sync-conflict-20260102-150405-abcDEFG.txt"),
                new byte[] {1}
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).conflictDiscoveryScript(root.toString()),
                tools,
                root.toFile()
        );

        assertEquals(
                "the walk reports the limit status once its deadline has passed",
                LibsuRootShell.FOLDER_LIMIT_EXIT_CODE,
                run.exitStatus
        );
    }

    @Test
    public void scriptDispatchRefusesAMarkerDirectoryReplacedBeforeTheNextScriptRuns()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-marker-swap-");
        Path outside = Files.createTempDirectory("standroid-marker-swap-outside-");
        File tools = Files.createTempDirectory("standroid-marker-swap-tools-").toFile();
        Path marker = root.resolve(LibsuRootShell.FOLDER_SCRIPT_DIRECTORY_NAME);
        Files.createDirectories(marker);
        writeRootId(tools);
        writeExecutable(
                new File(marker.toFile(), "01-first.sh"),
                "#!/bin/sh\nprintf 'ran' > \"$PWD/witness-first\"\n"
        );
        writeExecutable(
                new File(marker.toFile(), "02-second.sh"),
                "#!/bin/sh\nprintf 'ran' > \"$PWD/witness-second\"\n"
        );
        writeExecutable(
                new File(outside.toFile(), "02-second.sh"),
                "#!/bin/sh\nprintf 'ran' > \"" + outside + "/witness-second\"\n"
        );
        // A caller that can write the folder can rename the marker directory and leave a symbolic
        // link to a directory of its own in that place, so that the next name the dispatch already
        // enumerated would resolve to a program outside the configured folder. This is what that
        // swap looks like from the helper, and it happens after the first script reported.
        writeExecutable(
                new File(tools, "base64"),
                "#!/bin/sh\n"
                        + "rm -rf \"" + marker + "\"\n"
                        + "ln -s \"" + outside + "\" \"" + marker + "\"\n"
                        + "exec /usr/bin/base64 \"$@\"\n"
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell())
                        .folderScriptSetScript(root.toString(), SYNC_COMPLETE_EVENT)
                        .replace("/system/bin/sh", "/bin/sh"),
                tools,
                root.toFile()
        );

        assertEquals(
                "a marker directory that was replaced before the next script fails closed",
                LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE,
                run.exitStatus
        );
        assertTrue(
                "the script the marker held still ran",
                Files.exists(root.resolve("witness-first"))
        );
        assertFalse(
                "the script the marker held after the swap does not run",
                Files.exists(root.resolve("witness-second"))
        );
        assertFalse(
                "no script from outside the configured folder runs",
                Files.exists(outside.resolve("witness-second"))
        );
    }

    @Test
    public void scriptDispatchRunsNothingWhenTheMarkerDirectoryIsASymbolicLink() throws Exception {
        Path root = Files.createTempDirectory("standroid-marker-link-");
        Path outside = Files.createTempDirectory("standroid-marker-outside-");
        File tools = Files.createTempDirectory("standroid-marker-tools-").toFile();
        writeRootId(tools);
        Files.write(
                outside.resolve("outside.sh"),
                "echo ran > executed.txt".getBytes(StandardCharsets.UTF_8)
        );
        Files.createSymbolicLink(
                root.resolve(LibsuRootShell.FOLDER_SCRIPT_DIRECTORY_NAME),
                outside
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell())
                        .folderScriptSetScript(root.toString(), SYNC_COMPLETE_EVENT),
                tools,
                root.toFile()
        );

        assertEquals(
                "a marker directory that is a symbolic link is refused",
                LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE,
                run.exitStatus
        );
        assertFalse(
                "no script outside the configured folder may run",
                new File(root.toFile(), "executed.txt").exists()
        );
        assertFalse(
                "nothing runs next to the link target either",
                outside.resolve("executed.txt").toFile().exists()
        );
    }

    public void scriptDispatchReportsASymbolicLinkMarkerDirectoryAsUnsafe() {
        assertEquals(
                FolderOperationFailure.FOLDER_MEMBER_UNSAFE,
                failureOf(() -> folderTransport(
                        attachedShell().scriptJobResult(LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE)
                ).runFolderScriptSet(PRIVILEGED_FOLDER_ROOT, SYNC_COMPLETE_EVENT))
        );
    }

    /** One production folder script run: its exit status and its non-empty output lines. */
    private static final class ScriptRun {
        /** Exit status the script reported. */
        final int exitStatus;
        /** Non-empty output lines the script produced. */
        final List<String> lines = new ArrayList<>();

        ScriptRun(int exitStatus, String output) {
            this.exitStatus = exitStatus;
            for (String line : output.split("\n")) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
    }

    /** Runs one production folder script through the host shell and its stub tools. */
    /**
     * A caller that controls the folder removes the staged entry the operation created and leaves a
     * symbolic link to a file outside the folder at that name while the replacement is renamed over
     * the member. The rename works on names relative to the pinned folder, so the caller's link ends
     * up in the member slot; the operation then sees a member it did not stage, removes the entry it
     * staged, and reports a typed refusal instead of a saved list. The file outside the folder is
     * never read, written or removed, and nothing is created outside the configured folder.
     */
    @Test
    public void ignoreListWriteFailsClosedWhenTheStagedNameIsReplacedJustBeforeTheRename()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-pin-");
        Path outside = Files.createTempDirectory("standroid-ignore-pin-outside-");
        Path secret = outside.resolve("root-only.txt");
        File tools = Files.createTempDirectory("standroid-ignore-pin-tools-").toFile();
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Files.write(secret, "secret\n".getBytes(StandardCharsets.UTF_8));
        Files.write(member, "old\n".getBytes(StandardCharsets.UTF_8));
        writeRootId(tools);
        String script = folderTransport(attachedShell()).writeFolderIgnoreListScript(
                root.toString(),
                Base64.getEncoder().encodeToString("new\n".getBytes(StandardCharsets.UTF_8))
        );
        String stagedName = stagedNameOf(script);
        // The staged entry passes its last identity check, and the caller swaps the name for a
        // symbolic link out of the folder in the window between that check and the rename.
        writeExecutable(
                new File(tools, "mv"),
                "#!/bin/sh\n"
                        + "set -- \"$@\"\n"
                        + "while [ $# -gt 0 ]; do case \"$1\" in -*) shift ;; *) break ;; esac; done\n"
                        + "/bin/mv \"$1\" ./caller-kept\n"
                        + "ln -s \"" + secret + "\" \"./" + stagedName + "\"\n"
                        + "exec /bin/mv \"$@\"\n"
        );

        ScriptRun run = runFolderScript(script, tools, root.toFile());

        assertEquals(
                "a staged entry that was replaced before the rename fails closed",
                LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE,
                run.exitStatus
        );
        assertTrue("the refused operation reports no saved list", run.lines.isEmpty());
        assertTrue(
                "the caller left its own entry in the member slot",
                Files.isSymbolicLink(member)
        );
        assertEquals(
                "the file the caller pointed at is never read or modified",
                "secret\n",
                new String(Files.readAllBytes(secret), StandardCharsets.UTF_8)
        );
        assertEquals(
                "nothing is created outside the configured folder",
                1,
                outside.toFile().list().length
        );
    }

    /**
     * A caller that controls the folder replaces the configured folder entry itself with a fresh
     * directory of its own while the replacement is renamed over the member. The shell keeps
     * resolving through the directory it entered, so the requested list is still saved into the
     * configured folder, and the directory the caller put at the configured path receives nothing.
     */
    @Test
    public void ignoreListWriteFollowsThePinnedFolderWhenTheCallerReplacesItsEntry()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-swap-");
        Path attacker = Files.createTempDirectory("standroid-ignore-swap-attacker-");
        Path moved = attacker.resolve("moved");
        File tools = Files.createTempDirectory("standroid-ignore-swap-tools-").toFile();
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Files.write(member, "old\n".getBytes(StandardCharsets.UTF_8));
        writeRootId(tools);
        writeExecutable(
                new File(tools, "mv"),
                "#!/bin/sh\n"
                        + "if [ ! -e \"" + attacker + "/stamp\" ]; then\n"
                        + ": > \"" + attacker + "/stamp\"\n"
                        + "/bin/mv \"" + root + "\" \"" + moved + "\"\n"
                        + "/bin/mkdir \"" + root + "\"\n"
                        + "fi\n"
                        + "exec /bin/mv \"$@\"\n"
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).writeFolderIgnoreListScript(
                        root.toString(),
                        Base64.getEncoder().encodeToString("new\n".getBytes(StandardCharsets.UTF_8))
                ),
                tools,
                root.toFile()
        );

        assertEquals("the write completes against the pinned folder", 0, run.exitStatus);
        assertEquals(
                "the requested list is saved in the configured folder",
                "new\n",
                new String(
                        Files.readAllBytes(moved.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME)),
                        StandardCharsets.UTF_8
                )
        );
        String[] replaced = root.toFile().list();
        assertNotNull(replaced);
        assertEquals("the directory the caller put at the configured path stays untouched", 0, replaced.length);
    }

    /**
     * A caller that controls the folder swaps the member entry for a symbolic link to a file outside
     * the folder after the size of the member was read. The list is copied through the checked
     * member entry, so the link is refused and no content of the file outside the folder travels
     * into the folder.
     */
    @Test
    public void ignoreListWriteRefusesAMemberSwappedForASymbolicLinkAfterItsSizeWasRead()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-link-");
        Path outside = Files.createTempDirectory("standroid-ignore-link-outside-");
        File tools = Files.createTempDirectory("standroid-ignore-link-tools-").toFile();
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Path secret = outside.resolve("secret");
        Files.write(member, "old\n".getBytes(StandardCharsets.UTF_8));
        Files.write(secret, "outside\n".getBytes(StandardCharsets.UTF_8));
        writeRootId(tools);
        writeExecutable(
                new File(tools, "wc"),
                "#!/bin/sh\n"
                        + "rm -f \"" + member + "\"\n"
                        + "ln -s \"" + secret + "\" \"" + member + "\"\n"
                        + "exec /usr/bin/wc \"$@\"\n"
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).writeFolderIgnoreListScript(
                        root.toString(),
                        Base64.getEncoder().encodeToString("new\n".getBytes(StandardCharsets.UTF_8))
                ),
                tools,
                root.toFile()
        );

        assertEquals(
                "a member that became a symbolic link after its size was read is refused",
                LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE,
                run.exitStatus
        );
        assertTrue("the link the caller left is still the member entry", Files.isSymbolicLink(member));
        assertEquals(
                "the file outside the folder keeps its content",
                "outside\n",
                new String(Files.readAllBytes(secret), StandardCharsets.UTF_8)
        );
        String[] entries = root.toFile().list();
        assertNotNull(entries);
        assertEquals("nothing the caller could read is left behind in the folder", 1, entries.length);
    }

    /**
     * A caller that controls the folder moves the staged entry away and leaves a file of its own at
     * that name while the replacement is renamed over the member. The entry that ends up in the
     * member slot is not the file the operation staged, so the member fails the identity and size
     * checks that follow the rename and the write is reported as refused instead of saved. Nothing
     * outside the configured folder is created, read or removed: the entries the caller moves are
     * the ones inside the folder it already controls.
     */
    @Test
    public void ignoreListWriteRefusesASubstitutedStagedEntryRenamedOverTheMember() throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-substitute-");
        Path outside = Files.createTempDirectory("standroid-ignore-substitute-outside-");
        Path secret = outside.resolve("root-only.txt");
        File tools = Files.createTempDirectory("standroid-ignore-substitute-tools-").toFile();
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Files.write(secret, "secret\n".getBytes(StandardCharsets.UTF_8));
        Files.write(member, "old\n".getBytes(StandardCharsets.UTF_8));
        writeRootId(tools);
        String script = folderTransport(attachedShell()).writeFolderIgnoreListScript(
                root.toString(),
                Base64.getEncoder().encodeToString("new\n".getBytes(StandardCharsets.UTF_8))
        );
        String stagedName = stagedNameOf(script);
        writeExecutable(
                new File(tools, "mv"),
                "#!/bin/sh\n"
                        + "set -- \"$@\"\n"
                        + "while [ $# -gt 0 ]; do case \"$1\" in -*) shift ;; *) break ;; esac; done\n"
                        + "/bin/mv \"$1\" ./caller-kept\n"
                        + "printf 'substituted\\n' > \"./" + stagedName + "\"\n"
                        + "exec /bin/mv \"$@\"\n"
        );

        ScriptRun run = runFolderScript(script, tools, root.toFile());

        assertEquals(
                "a substituted staged entry is refused instead of reported as saved",
                LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE,
                run.exitStatus
        );
        assertTrue("the refused operation reports no saved list", run.lines.isEmpty());
        assertEquals(
                "the member carries the entry the caller substituted, never the requested list",
                "substituted\n",
                new String(Files.readAllBytes(member), StandardCharsets.UTF_8)
        );
        assertEquals(
                "nothing is created outside the configured folder",
                1,
                outside.toFile().list().length
        );
        assertEquals(
                "the file outside the folder keeps its content",
                "secret\n",
                new String(Files.readAllBytes(secret), StandardCharsets.UTF_8)
        );
    }

    /**
     * Replaces the ignore list of a folder that already has one under a host shell that behaves like
     * the shell of the compatibility floor: a descriptor such a shell opened itself is not handed to
     * the commands the shell starts, so a command that has to act on the opened file reaches it only
     * when its own command line names that descriptor.
     *
     * <p>The attribute commands of this test wrap the real commands of the host, record the entry they
     * were pointed at and whether the descriptor that entry names was reachable inside their own
     * process, and then behave like the command they wrap. The replacement therefore only completes
     * when every attribute command was handed the descriptor, and the trace shows the attributes are
     * applied through the descriptor rather than through the staged name that a caller controlling
     * the folder could repoint.</p>
     */
    @Test
    public void ignoreListWriteReplacesAnExistingMemberUnderAShellThatHoldsBackDescriptors()
            throws Exception {
        String shell = shellThatHoldsBackOpenedDescriptors();
        Assume.assumeNotNull(
                "this host has no shell that keeps the descriptors it opened from its commands",
                shell
        );

        Path root = Files.createTempDirectory("standroid-ignore-descriptor-");
        Path outside = Files.createTempDirectory("standroid-ignore-descriptor-outside-");
        Path trace = Files.createTempDirectory("standroid-ignore-descriptor-trace-")
                .resolve("attributes.trace");
        File tools = Files.createTempDirectory("standroid-ignore-descriptor-tools-").toFile();
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Files.write(member, "old\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(member, PosixFilePermissions.fromString("rw-r-----"));
        writeRootId(tools);
        writeAttributeRecorder(new File(tools, "chmod"), realCommandOf("chmod"), trace);
        writeAttributeRecorder(new File(tools, "chown"), realCommandOf("chown"), trace);
        String script = folderTransport(attachedShell()).writeFolderIgnoreListScript(
                root.toString(),
                Base64.getEncoder().encodeToString("new-list\n".getBytes(StandardCharsets.UTF_8))
        );

        ScriptRun run = runFolderScript(shell, script, tools, root.toFile());

        assertEquals("the replacement of an existing member is saved", 0, run.exitStatus);
        assertEquals(
                "the member carries the requested list",
                "new-list\n",
                new String(Files.readAllBytes(member), StandardCharsets.UTF_8)
        );
        assertEquals(
                "the member keeps the permissions it had",
                PosixFilePermissions.fromString("rw-r-----"),
                Files.getPosixFilePermissions(member)
        );
        String recorded = new String(Files.readAllBytes(trace), StandardCharsets.UTF_8);
        assertAttributeRunReachedTheDescriptor(recorded, "chmod");
        assertAttributeRunReachedTheDescriptor(recorded, "chown");
        assertFalse(
                "no attribute command is pointed at the staged name again: " + recorded,
                recorded.contains(stagedNameOf(script))
        );
        String[] entries = root.toFile().list();
        assertNotNull(entries);
        assertEquals("only the member is left inside the folder", 1, entries.length);
        assertEquals(
                "nothing is created outside the configured folder",
                0,
                outside.toFile().list().length
        );
    }

    /**
     * The attribute command of this host cannot reach the descriptor of the staged file at all, the
     * way a command does when the shell that started it kept the descriptor to itself. The
     * replacement has to fail closed: the member keeps the list and the permissions it had, the
     * staged entry this operation created is removed again, and nothing is reported as saved.
     */
    @Test
    public void ignoreListWriteFailsClosedWhenAnAttributeCommandCannotReachTheDescriptor()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-unreachable-");
        Path outside = Files.createTempDirectory("standroid-ignore-unreachable-outside-");
        Path trace = Files.createTempDirectory("standroid-ignore-unreachable-trace-")
                .resolve("attributes.trace");
        File tools = Files.createTempDirectory("standroid-ignore-unreachable-tools-").toFile();
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Files.write(member, "old\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(member, PosixFilePermissions.fromString("rw-r-----"));
        writeRootId(tools);
        writeExecutable(
                new File(tools, "chmod"),
                "#!/bin/sh\n"
                        + "exec 3<&-\n"
                        + "if [ -e \"$2\" ]; then standroid_reach=reachable;"
                        + " else standroid_reach=unreachable; fi\n"
                        + "printf '%s\\n' \"$standroid_reach\" >> \"" + trace + "\"\n"
                        + "exit 1\n"
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).writeFolderIgnoreListScript(
                        root.toString(),
                        Base64.getEncoder().encodeToString("new\n".getBytes(StandardCharsets.UTF_8))
                ),
                tools,
                root.toFile()
        );

        assertEquals(
                "a permission that cannot be applied through the descriptor fails the write",
                LibsuRootShell.FOLDER_ACCESS_EXIT_CODE,
                run.exitStatus
        );
        assertEquals(
                "the command this test used really could not reach the descriptor",
                "unreachable\n",
                new String(Files.readAllBytes(trace), StandardCharsets.UTF_8)
        );
        assertEquals(
                "the member keeps the list it had",
                "old\n",
                new String(Files.readAllBytes(member), StandardCharsets.UTF_8)
        );
        assertEquals(
                "the member keeps the permissions it had",
                PosixFilePermissions.fromString("rw-r-----"),
                Files.getPosixFilePermissions(member)
        );
        assertTrue("the refused operation reports no saved list", run.lines.isEmpty());
        String[] entries = root.toFile().list();
        assertNotNull(entries);
        assertEquals("the staged entry is removed again", 1, entries.length);
        assertEquals(
                "nothing is created outside the configured folder",
                0,
                outside.toFile().list().length
        );
    }

    /**
     * The ownership of the existing member cannot be carried over to the replacement, because the
     * ownership command of this host refuses. The replacement has to fail closed and leave the
     * member, its permissions included, exactly as it was.
     */
    @Test
    public void ignoreListWriteFailsClosedWhenOwnershipCannotBePreserved() throws Exception {
        Path root = Files.createTempDirectory("standroid-ignore-ownership-");
        Path outside = Files.createTempDirectory("standroid-ignore-ownership-outside-");
        Path trace = Files.createTempDirectory("standroid-ignore-ownership-trace-")
                .resolve("ownership.trace");
        File tools = Files.createTempDirectory("standroid-ignore-ownership-tools-").toFile();
        Path member = root.resolve(LibsuRootShell.FOLDER_IGNORE_FILE_NAME);
        Files.write(member, "old\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(member, PosixFilePermissions.fromString("rw-r-----"));
        writeRootId(tools);
        writeExecutable(
                new File(tools, "chown"),
                "#!/bin/sh\n"
                        + "printf '%s\\n' \"$*\" >> \"" + trace + "\"\n"
                        + "exit 1\n"
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell()).writeFolderIgnoreListScript(
                        root.toString(),
                        Base64.getEncoder().encodeToString("new\n".getBytes(StandardCharsets.UTF_8))
                ),
                tools,
                root.toFile()
        );

        assertEquals(
                "an ownership that cannot be carried over fails the write",
                LibsuRootShell.FOLDER_ACCESS_EXIT_CODE,
                run.exitStatus
        );
        String recorded = new String(Files.readAllBytes(trace), StandardCharsets.UTF_8);
        assertTrue(
                "the ownership was attempted through the descriptor: " + recorded,
                recorded.contains("/fd/3")
        );
        assertEquals(
                "the member keeps the list it had",
                "old\n",
                new String(Files.readAllBytes(member), StandardCharsets.UTF_8)
        );
        assertEquals(
                "the member keeps the permissions it had",
                PosixFilePermissions.fromString("rw-r-----"),
                Files.getPosixFilePermissions(member)
        );
        assertTrue("the refused operation reports no saved list", run.lines.isEmpty());
        String[] entries = root.toFile().list();
        assertNotNull(entries);
        assertEquals("the staged entry is removed again", 1, entries.length);
        assertEquals(
                "nothing is created outside the configured folder",
                0,
                outside.toFile().list().length
        );
    }

    /** The name a write script stages its replacement under inside the pinned folder. */
    private static String stagedNameOf(String script) {
        Matcher matcher = Pattern.compile("standroid_staged=\"\\./([^\"]+)\"").matcher(script);
        assertTrue("the write script stages its replacement inside the pinned folder", matcher.find());
        return matcher.group(1);
    }

    @Test
    public void scriptDispatchRefusesAScriptReplacedAfterItsIdentityWasRecorded()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-script-swap-");
        Path outside = Files.createTempDirectory("standroid-script-swap-outside-");
        File tools = Files.createTempDirectory("standroid-script-swap-tools-").toFile();
        Path marker = root.resolve(LibsuRootShell.FOLDER_SCRIPT_DIRECTORY_NAME);
        Files.createDirectories(marker);
        writeRootId(tools);
        writeExecutable(
                new File(marker.toFile(), "01-first.sh"),
                "#!/bin/sh\nprintf 'ran' > \"$PWD/witness-first\"\n"
        );
        writeExecutable(
                new File(outside.toFile(), "outside.sh"),
                "#!/bin/sh\nprintf 'ran' \"" + outside + "/witness-outside\"\n"
        );
        // The entry is replaced by a symbolic link to a program outside the configured folder
        // after the dispatch recorded the identity of the validated file and before the
        // interpreter reads it. The opened descriptor is what gets checked against that
        // identity, so the substitution is refused and neither program runs.
        Path stamp = Files.createTempDirectory("standroid-script-swap-stamp-").resolve("used");
        writeExecutable(
                new File(tools, "ls"),
                "#!/bin/sh\n"
                        + "for standroid_argument in \"$@\"; do\n"
                        + "case \"$standroid_argument\" in"
                        + " */01-first.sh) : ;; *) continue ;; esac\n"
                        + "if [ ! -e \"" + stamp + "\" ]; then\n"
                        + ": > \"" + stamp + "\"\n"
                        + "/bin/ls \"$@\"\n"
                        + "rm -f \"" + marker + "/01-first.sh\"\n"
                        + "ln -s \"" + outside + "/outside.sh\" \"" + marker + "/01-first.sh\"\n"
                        + "exit 0\n"
                        + "fi\n"
                        + "done\n"
                        + "exec /bin/ls \"$@\"\n"
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell())
                        .folderScriptSetScript(root.toString(), SYNC_COMPLETE_EVENT)
                        .replace("/system/bin/sh", "/bin/sh"),
                tools,
                root.toFile()
        );

        assertEquals(
                "the substituted program is refused " + run.lines,
                LibsuRootShell.FOLDER_UNSAFE_EXIT_CODE,
                run.exitStatus
        );
        assertFalse(
                "the script that was validated never runs",
                Files.exists(root.resolve("witness-first"))
        );
        assertFalse(
                "the program the entry was replaced with never runs",
                Files.exists(outside.resolve("witness-outside"))
        );
    }

    /**
     * Verifies that the dispatcher identifies the descriptor it opened rather than the descriptor
     * entry. On the Android compatibility floor a process file system entry keeps an inode of its
     * own, so a listing that does not follow such an entry reports that inode and never the inode of
     * the opened script; a dispatcher that compares the entry against the script therefore refuses
     * every approved script. The stand-in listing below reproduces that behaviour by answering with
     * a placeholder inode unless the invocation follows the descriptor.
     */
    @Test
    public void scriptDispatchIdentifiesTheOpenedDescriptorUnderProcessFileSystemSemantics()
            throws Exception {
        Path root = Files.createTempDirectory("standroid-script-follow-");
        File tools = Files.createTempDirectory("standroid-script-follow-tools-").toFile();
        Path marker = root.resolve(LibsuRootShell.FOLDER_SCRIPT_DIRECTORY_NAME);
        Files.createDirectories(marker);
        writeRootId(tools);
        writeExecutable(
                new File(marker.toFile(), "01-first.sh"),
                "#!/bin/sh\n"
                        + "printf 'ran' > \"$PWD/witness-first\"\n"
        );
        writeExecutable(
                new File(tools, "ls"),
                "#!/bin/sh\n"
                        + "for standroid_argument in \"$@\"; do\n"
                        + "case \"$standroid_argument\" in\n"
                        + "*/dev/fd/3|*/proc/self/fd/3)\n"
                        + "case \" $* \" in\n"
                        + "*L*)\n"
                        + "exec /bin/ls -lniL /dev/fd/3\n"
                        + ";;\n"
                        + "esac\n"
                        + "echo \"424242 lr-x------ 1 0 0 64 Jan  1 00:00 $standroid_argument\"\n"
                        + "exit 0\n"
                        + ";;\n"
                        + "esac\n"
                        + "done\n"
                        + "exec /bin/ls \"$@\"\n"
        );

        ScriptRun run = runFolderScript(
                folderTransport(attachedShell())
                        .folderScriptSetScript(root.toString(), SYNC_COMPLETE_EVENT)
                        .replace("/system/bin/sh", "/bin/sh"),
                tools,
                root.toFile()
        );

        assertEquals(run.lines.toString(), 0, run.exitStatus);
        assertEquals(List.of(encoded("01-first.sh") + " 0"), run.lines);
        assertTrue(
                "the approved script runs through the descriptor it was identified by",
                Files.exists(root.resolve("witness-first"))
        );
    }

    /** Runs one production folder script through the system shell and its stub tools. */
    private static ScriptRun runFolderScript(String script, File tools, File workingDirectory)
            throws Exception {
        return runFolderScript("/bin/sh", script, tools, workingDirectory);
    }

    /** Runs one production folder script through one host shell and its stub tools. */
    private static ScriptRun runFolderScript(
            String shell,
            String script,
            File tools,
            File workingDirectory
    ) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(shell, "-c", script)
                .redirectErrorStream(true);
        builder.environment().put(
                "PATH",
                tools.getAbsolutePath() + File.pathSeparator + System.getenv("PATH")
        );
        builder.directory(workingDirectory);
        Process process = builder.start();
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        assertTrue("the folder script must finish promptly", process.waitFor(10, TimeUnit.SECONDS));
        return new ScriptRun(process.exitValue(), output);
    }

    /** Shells of this host that can stand in for the shell of the compatibility floor, in order. */
    private static final String[] DESCRIPTOR_HOLDING_SHELLS = {
            "/system/bin/sh",
            "/bin/mksh",
            "/usr/bin/mksh",
            "/usr/local/bin/mksh",
            "/opt/homebrew/bin/mksh",
            "/bin/ksh",
            "/bin/ksh93",
            "/usr/bin/ksh",
            "/usr/bin/ksh93",
            "/bin/lksh",
            "/usr/bin/lksh",
    };

    /**
     * The first shell on this host that reproduces the descriptor behaviour of the compatibility
     * floor: a descriptor such a shell opened with {@code exec 3<} is not handed to the commands the
     * shell starts, while naming it on such a command ({@code 3<&3}) does hand it over. The shell of
     * Android and its mksh ancestors behave this way; a shell that hands its opened descriptors on
     * cannot show the defect this suite guards against.
     *
     * @return the path of such a shell, or {@code null} when this host has none
     */
    private static String shellThatHoldsBackOpenedDescriptors() throws Exception {
        Path probeDirectory = Files.createTempDirectory("standroid-holding-shell-");
        Path probeFile = probeDirectory.resolve("probe.txt");
        Files.write(probeFile, "probe\n".getBytes(StandardCharsets.UTF_8));
        String descriptorPath = Files.isDirectory(Paths.get("/proc/self/fd"))
                ? "/proc/self/fd/3"
                : "/dev/fd/3";
        String probe = "exec 3< \"$1\"\n"
                + "ls \"$2\" >/dev/null 2>&1 && echo inherited\n"
                + "ls \"$2\" 3<&3 >/dev/null 2>&1 && echo forwarded\n";
        for (String shell : DESCRIPTOR_HOLDING_SHELLS) {
            if (!new File(shell).canExecute()) {
                continue;
            }
            Process process = new ProcessBuilder(
                    shell, "-c", probe, shell, probeFile.toString(), descriptorPath
            ).redirectErrorStream(true).start();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                continue;
            }
            if (process.exitValue() == 0
                    && !output.contains("inherited")
                    && output.contains("forwarded")) {
                return shell;
            }
        }
        return null;
    }

    /**
     * Asserts that one attribute command recorded a run that was handed the descriptor of the staged
     * file and could reach that descriptor inside its own process.
     */
    private static void assertAttributeRunReachedTheDescriptor(String recorded, String command) {
        Pattern pattern = Pattern.compile(
                "^" + command + " .*/fd/3 reachable$",
                Pattern.MULTILINE
        );
        assertTrue(
                "the " + command + " must be handed the descriptor of the staged file and reach it: "
                        + recorded,
                pattern.matcher(recorded).find()
        );
    }

    /**
     * The absolute path of one command of this host, so a wrapper written by a test can run the
     * command it wraps without resolving its own name through the test's own search path again.
     *
     * @param name the file name of the command, for example {@code chown}
     * @return the path of the first command of that name found in the usual system directories
     */
    private static String realCommandOf(String name) {
        for (String directory : new String[]{"/bin", "/usr/bin", "/usr/sbin", "/sbin"}) {
            File candidate = new File(directory, name);
            if (candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        fail("this host has no " + name + " command for the test to wrap");
        return name;
    }

    /**
     * Writes one wrapper for a real attribute command. The wrapper records the entry it was pointed
     * at and whether the descriptor that entry names was reachable inside its own process, and then
     * runs the command it wraps.
     */
    private static void writeAttributeRecorder(File target, String realCommand, Path trace)
            throws IOException {
        writeExecutable(
                target,
                "#!/bin/sh\n"
                        + "if [ -e \"$2\" ]; then standroid_reach=reachable;"
                        + " else standroid_reach=unreachable; fi\n"
                        + "printf '%s %s %s\\n' \"" + target.getName() + "\" \"$2\""
                        + " \"$standroid_reach\" >> \"" + trace + "\"\n"
                        + "exec " + realCommand + " \"$@\"\n"
        );
    }

    /** Writes the stub of the real UID probe every privileged script starts with. */
    private static void writeRootId(File tools) throws IOException {
        writeExecutable(new File(tools, "id"), "#!/bin/sh\nprintf '0\n'\n");
    }

    /** One owned execution identity a privileged tuning request can name. */
    private static ExecutionIdentity ownedExecution(int pid, long startTimeTicks) {
        return new ExecutionIdentity(
                pid,
                startTimeTicks,
                "boot-id",
                "/data/app/libsyncthing.so",
                "run-token"
        );
    }

    /**
     * One {@code /proc/<pid>/stat} line whose process name contains a space and parentheses and
     * whose start time is the last field after that name.
     */
    private static final String TUNING_STAT_LINE =
            "4321 (my prog) (x) S 1 4321 4321 0 -1 4194560 0 0 0 0 1 2 0 0 20 0 9 0 987654"
                    + " 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n";

    /**
     * Writes the stand-in for the Android {@code base64} command, which encodes the file it is
     * named as its argument, or standard input when no file is named.
     */
    private static void writeToyboxBase64(File tools) throws IOException {
        writeExecutable(
                new File(tools, "base64"),
                "#!/usr/bin/env python3\n"
                        + "import base64, sys\n"
                        + "source = open(sys.argv[1], 'rb') if len(sys.argv) > 1 else sys.stdin.buffer\n"
                        + "sys.stdout.write(base64.b64encode(source.read()).decode('ascii'))\n"
        );
    }

    /**
     * Writes the stub of a Base64 encoder that wraps its output, which is what the encoder on an
     * Android device does by default.
     */
    private static void writeWrappingBase64(File tools) throws IOException {
        writeExecutable(
                new File(tools, "base64"),
                "#!/usr/bin/env python3\n"
                        + "import base64, sys\n"
                        + "sys.stdout.write(base64.encodebytes(sys.stdin.buffer.read())"
                        + ".decode('ascii'))\n"
        );
    }

    /**
     * Writes the stand-in for {@code dd} that reports more bytes than the size that was checked,
     * which is what a member that grew after its size was checked looks like to the reading helper.
     */
    private static void writeGrowingDd(File tools) throws IOException {
        writeExecutable(
                new File(tools, "dd"),
                standroidMemberReadingDd("cat \"$standroid_member\"; printf 'grown\\n'")
        );
    }

    /**
     * Writes the stand-in for {@code dd} that reads only part of the member it is pointed at, so a
     * read that stopped early is distinguishable from the content that was checked.
     */
    private static void writeTruncatingDd(File tools) throws IOException {
        writeExecutable(
                new File(tools, "dd"),
                standroidMemberReadingDd("head -c 3 \"$standroid_member\"")
        );
    }

    /** Writes one stand-in {@code dd} that runs a reading command over the member it is given. */
    private static String standroidMemberReadingDd(String reading) {
        return "#!/bin/sh\n"
                + "for standroid_argument in \"$@\"; do\n"
                + "case \"$standroid_argument\" in if=*) standroid_member=${standroid_argument#if=} ;; esac\n"
                + "done\n"
                + reading + "\n";
    }

    /**
     * Writes the stand-in for the Android {@code date} command that stays inside the scan budget
     * for its first two readings and passes it afterwards, so the check that follows one directory
     * listing is observable instead of being hidden by the check that precedes it.
     */
    private static void writeBudgetExhaustingDate(File tools, File readings) throws IOException {
        writeExecutable(
                new File(tools, "date"),
                "#!/bin/sh\n"
                        + "standroid_reading=$(cat \"" + readings.getAbsolutePath()
                        + "\" 2>/dev/null || echo 0)\n"
                        + "standroid_reading=$(( standroid_reading + 1 ))\n"
                        + "echo \"$standroid_reading\" > \"" + readings.getAbsolutePath() + "\"\n"
                        + "if [ \"$standroid_reading\" -le 2 ]; then echo 1000; else echo 9999999; fi\n"
        );
    }

    /**
     * Writes the stand-in for the Android {@code date} command that replaces one directory with a
     * symbolic link on a chosen reading, standing in for a concurrent writer that swaps an entry
     * after the walk accepted it.
     */
    private static void writeSwappingDate(
            File tools,
            File readings,
            File swappedDirectory,
            File linkTarget,
            int swapOnReading
    ) throws IOException {
        String swapped = swappedDirectory.getAbsolutePath();
        writeExecutable(
                new File(tools, "date"),
                "#!/bin/sh\n"
                        + "standroid_reading=$(cat \"" + readings.getAbsolutePath()
                        + "\" 2>/dev/null || echo 0)\n"
                        + "standroid_reading=$(( standroid_reading + 1 ))\n"
                        + "echo \"$standroid_reading\" > \"" + readings.getAbsolutePath() + "\"\n"
                        + "if [ \"$standroid_reading\" -eq " + swapOnReading + " ]; then\n"
                        + "rmdir \"" + swapped + "\" && ln -s \"" + linkTarget.getAbsolutePath()
                        + "\" \"" + swapped + "\"\n"
                        + "fi\n"
                        + "echo 1000\n"
        );
    }

    /** Decodes one base64 record per output line of one script run. */
    private static List<String> decodeRecords(ScriptRun run) {
        List<String> decoded = new ArrayList<>();
        for (String line : run.lines) {
            decoded.add(new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8));
        }
        return decoded;
    }

    /** Returns a sorted copy of one list, so a comparison ignores directory order. */
    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }

    /** Returns the watch-limit script pointed at a stand-in kernel file. */
    private static String inotifyWatchLimitScriptFor(File limit) {
        return folderTransport(attachedShell())
                .inotifyWatchLimitScript(InotifyWatchLimit.TARGET)
                .replace(
                        "'/proc/sys/fs/inotify/max_user_watches'",
                        "'" + limit.getAbsolutePath() + "'"
                );
    }

    /** Writes one stand-in kernel file that holds the given watch-limit value. */
    private static File writeWatchLimit(File directory, String value) throws IOException {
        File limit = new File(directory, "max_user_watches");
        Files.write(limit.toPath(), (value + "\n").getBytes(StandardCharsets.UTF_8));
        return limit;
    }

    /** Reads one stand-in kernel file back as its trimmed value. */
    private static String readWatchLimit(File limit) throws IOException {
        return new String(Files.readAllBytes(limit.toPath()), StandardCharsets.UTF_8).trim();
    }

    /**
     * Writes a stand-in for {@code ls} that exchanges a directory after it answered the identity
     * read for that directory.
     *
     * <p>The exchange therefore lands in the window between the identity read a privileged walk
     * uses to pin an object and the step that acts on that identity, which is the window a
     * concurrent writer that controls the folder would have to use to redirect the walk.</p>
     *
     * @param tools directory the stand-in is written into
     * @param identityArgument argument the identity read carries when the exchange has to happen
     * @param exchange shell command that performs the exchange
     */
    private static void writeEntryExchangingLs(File tools, String identityArgument, String exchange)
            throws IOException {
        writeExecutable(
                new File(tools, "ls"),
                "#!/bin/sh\n"
                + "case \"$*\" in\n"
                + "\"-lnid " + identityArgument + "\")\n"
                + "/bin/ls \"$@\"\n"
                + "standroid_ls_status=$?\n"
                + exchange + "\n"
                + "exit $standroid_ls_status\n"
                + ";;\n"
                + "esac\n"
                + "exec /bin/ls \"$@\"\n"
        );
    }

    /**
     * Writes a stand-in for {@code base64} that replaces one member with a symbolic link to another
     * file before it encodes its input, so the exchange lands between the kind check a walk performs
     * and the record it writes for that member.
     *
     * @param tools directory the stand-in is written into
     * @param member member the stand-in exchanges
     * @param linkTarget file the member is replaced by a symbolic link to
     */
    private static void writeMemberExchangingBase64(File tools, File member, File linkTarget)
            throws IOException {
        writeExecutable(
                new File(tools, "base64"),
                "#!/usr/bin/env python3\n"
                + "import base64, os, sys\n"
                + "member = '" + member.getAbsolutePath() + "'\n"
                + "target = '" + linkTarget.getAbsolutePath() + "'\n"
                + "if os.path.exists(member) and not os.path.islink(member):\n"
                + "    os.remove(member)\n"
                + "    os.symlink(target, member)\n"
                + "sys.stdout.write(base64.b64encode(sys.stdin.buffer.read()).decode('ascii'))\n"
        );
    }
}
