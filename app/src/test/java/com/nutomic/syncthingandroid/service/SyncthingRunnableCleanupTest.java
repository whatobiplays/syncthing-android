package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.runtime.ConfigStorage;
import com.nutomic.syncthingandroid.runtime.ConfiguredFolderReference;
import com.nutomic.syncthingandroid.runtime.DefaultSyncthingRuntime;
import com.nutomic.syncthingandroid.runtime.ExecutionAdmissionException;
import com.nutomic.syncthingandroid.runtime.ExecutableNotFoundException;
import com.nutomic.syncthingandroid.runtime.FolderEvent;
import com.nutomic.syncthingandroid.runtime.FolderIgnoreResult;
import com.nutomic.syncthingandroid.runtime.FolderWriteability;
import com.nutomic.syncthingandroid.runtime.PrivilegeBackend;
import com.nutomic.syncthingandroid.runtime.SyncthingCommand;
import com.nutomic.syncthingandroid.runtime.SyncthingEnvironment;
import com.nutomic.syncthingandroid.runtime.SyncthingExecution;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

public class SyncthingRunnableCleanupTest {

    @Test
    public void exitCodeThreeRestartAfterWorkerTerminationClearsHandlesBeforeReplacement()
            throws Exception {
        GatedExitCodeExecution exitingExecution = new GatedExitCodeExecution();
        ExecutionBackend backend = new ExecutionBackend(exitingExecution);
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        SyncthingEnvironment environment = normalModeEnvironment();
        SyncthingExecution active = runtime.startServiceLifecycle(
                SyncthingCommand.SERVE, environment
        );

        boolean[] executionExitProven = {false};
        boolean[] restartQueued = {false};
        int[] exitCode = {-1};
        Throwable[] workerFailure = {null};
        CountDownLatch outcomePublished = new CountDownLatch(1);
        CountDownLatch allowWorkerTermination = new CountDownLatch(1);
        Thread lifecycleThread = new Thread(() -> {
            try {
                exitCode[0] = active.await();
                executionExitProven[0] = true;
                if (exitCode[0] == 3) restartQueued[0] = true;
                outcomePublished.countDown();
                allowWorkerTermination.await();
            } catch (Throwable error) {
                workerFailure[0] = error;
                outcomePublished.countDown();
            }
        }, "test Syncthing lifecycle");
        Runnable lifecycleRunnable = () -> { };
        Thread[] workerHandle = {lifecycleThread};
        Runnable[] runnableHandle = {lifecycleRunnable};
        lifecycleThread.start();
        exitingExecution.exit(3);
        outcomePublished.await();

        assertNull(workerFailure[0]);
        assertEquals(3, exitCode[0]);
        assertTrue(executionExitProven[0]);
        assertTrue(restartQueued[0]);

        boolean[] recoveryStarted = {false};
        boolean readyWhileWorkerAlive = LifecycleShutdownBarrier.runWhenReady(
                executionExitProven[0],
                lifecycleThread,
                () -> {
                    workerHandle[0] = null;
                    runnableHandle[0] = null;
                },
                () -> recoveryStarted[0] = true
        );
        assertFalse(readyWhileWorkerAlive);
        assertSame(lifecycleThread, workerHandle[0]);
        assertSame(lifecycleRunnable, runnableHandle[0]);
        assertFalse(recoveryStarted[0]);

        allowWorkerTermination.countDown();
        lifecycleThread.join();
        assertFalse(lifecycleThread.isAlive());

        backend.execution = new ImmediateExecution();
        boolean restartAdmitted = LifecycleShutdownBarrier.runWhenReady(
                executionExitProven[0],
                lifecycleThread,
                () -> {
                    workerHandle[0] = null;
                    runnableHandle[0] = null;
                },
                () -> {
                    assertNull(workerHandle[0]);
                    assertNull(runnableHandle[0]);
                    try {
                        SyncthingExecution replacement = runtime.startServiceLifecycle(
                                SyncthingCommand.SERVE, environment
                        );
                        assertEquals(0, replacement.await());
                        recoveryStarted[0] = true;
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                }
        );

        assertTrue(restartAdmitted);
        assertTrue(recoveryStarted[0]);
    }

    @Test
    public void interruptedExecutionWaitKeepsAdmissionUntilExitIsObserved() throws Exception {
        RepeatedlyInterruptedExecution execution = new RepeatedlyInterruptedExecution(2);
        ExecutionBackend backend = new ExecutionBackend(execution);
        DefaultSyncthingRuntime runtime = new DefaultSyncthingRuntime(backend);
        SyncthingEnvironment environment = SyncthingEnvironment.builder()
                .home("/home")
                .syncthingHome("/state")
                .trace("")
                .monitored()
                .noUpgrade()
                .versionExtra("app")
                .sqliteTemporaryDirectory("/tmp")
                .gogc(100)
                .build();

        SyncthingExecution active = runtime.start(SyncthingCommand.SERVE, environment);
        active.destroy();
        for (int interruption = 1; interruption <= 2; interruption++) {
            assertThrows(InterruptedException.class, active::await);
            assertThrows(
                    ExecutionAdmissionException.class,
                    () -> runtime.start(SyncthingCommand.RESET_DATABASE, environment)
            );
            assertEquals(interruption, execution.interruptionsObserved);
        }

        execution.exit();
        assertEquals(0, active.await());

        backend.execution = new ImmediateExecution();
        SyncthingExecution reset = runtime.start(SyncthingCommand.RESET_DATABASE, environment);
        assertEquals(0, reset.await());
    }

    private static final class ExecutionBackend implements PrivilegeBackend {
        private PrivilegeBackend.Execution execution;

        private ExecutionBackend(PrivilegeBackend.Execution execution) {
            this.execution = execution;
        }

        @Override
        public Execution start(SyncthingCommand command, SyncthingEnvironment environment)
                throws java.io.IOException, ExecutableNotFoundException {
            return execution;
        }

        @Override
        public ConfigStorage configStorage() {
            return null;
        }

        @Override
        public FolderWriteability validateCandidateFolder(String path) {
            return null;
        }

        @Override
        public com.nutomic.syncthingandroid.runtime.ConflictDiscoveryResult discoverConflicts(
                ConfiguredFolderReference folder
        ) {
            return null;
        }

        @Override
        public FolderIgnoreResult loadFolderIgnoreList(ConfiguredFolderReference folder) {
            return null;
        }

        @Override
        public void saveFolderIgnoreList(ConfiguredFolderReference folder, String[] ignore) {
        }

        @Override
        public void runFolderScripts(ConfiguredFolderReference folder, FolderEvent event) {
        }
    }

    private static final class ImmediateExecution implements PrivilegeBackend.Execution {
        @Override
        public InputStream stdout() {
            return new ByteArrayInputStream(new byte[0]);
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

    private static final class RepeatedlyInterruptedExecution
            implements PrivilegeBackend.Execution {
        private int interruptionsRemaining;
        private int interruptionsObserved;
        private boolean exited;

        private RepeatedlyInterruptedExecution(int interruptionsRemaining) {
            this.interruptionsRemaining = interruptionsRemaining;
        }

        @Override
        public InputStream stdout() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream stderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int await() throws InterruptedException {
            if (interruptionsRemaining > 0) {
                interruptionsRemaining--;
                interruptionsObserved++;
                throw new InterruptedException("test interruption");
            }
            if (!exited) throw new AssertionError("test execution has not exited");
            return 0;
        }

        @Override
        public void destroy() {
        }

        private void exit() {
            exited = true;
        }
    }

    private static final class GatedExitCodeExecution implements PrivilegeBackend.Execution {
        private final CountDownLatch exitGate = new CountDownLatch(1);
        private volatile int exitCode;

        @Override
        public InputStream stdout() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream stderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int await() throws InterruptedException {
            exitGate.await();
            return exitCode;
        }

        @Override
        public void destroy() {
        }

        private void exit(int code) {
            exitCode = code;
            exitGate.countDown();
        }
    }

    private static SyncthingEnvironment normalModeEnvironment() {
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
}
