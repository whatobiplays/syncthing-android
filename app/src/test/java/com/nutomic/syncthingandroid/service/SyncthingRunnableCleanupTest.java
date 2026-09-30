package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
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

import org.junit.Test;

public class SyncthingRunnableCleanupTest {

    @Test
    public void terminationWaitContinuesThroughInterveningInterruptions() {
        RepeatedlyInterruptedWaiter waiter = new RepeatedlyInterruptedWaiter(2);
        int[] interruptionCount = new int[1];
        boolean wasInterrupted = Thread.currentThread().isInterrupted();
        Thread.interrupted();

        try {
            TerminationWait.awaitTermination(
                    waiter::await,
                    () -> { },
                    () -> interruptionCount[0]++
            );

            assertEquals(3, waiter.awaitCount);
            assertEquals(2, interruptionCount[0]);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    public void repeatedTerminationInterruptionsKeepAdmissionUntilExit() throws Exception {
        boolean wasInterrupted = Thread.currentThread().isInterrupted();
        Thread.interrupted();

        try {
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

            SyncthingExecution first = runtime.start(SyncthingCommand.SERVE, environment);
            first.destroy();

            TerminationWait.awaitTermination(
                    () -> first.await(),
                    () -> { },
                    () -> {
                        if (execution.interruptionsObserved == 1) {
                            assertThrows(
                                    ExecutionAdmissionException.class,
                                    () -> runtime.start(SyncthingCommand.RESET_DATABASE, environment)
                            );
                        }
                        if (execution.interruptionsObserved == 2) {
                            execution.exit();
                        }
                    }
            );

            assertEquals(2, execution.interruptionsObserved);
            backend.execution = new ImmediateExecution();
            SyncthingExecution reset = runtime.start(SyncthingCommand.RESET_DATABASE, environment);
            assertEquals(0, reset.await());
        } finally {
            Thread.interrupted();
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class RepeatedlyInterruptedWaiter {
        private int interruptionsRemaining;
        private int awaitCount;

        private RepeatedlyInterruptedWaiter(int interruptionsRemaining) {
            this.interruptionsRemaining = interruptionsRemaining;
        }

        private void await() throws InterruptedException {
            awaitCount++;
            if (interruptionsRemaining > 0) {
                interruptionsRemaining--;
                throw new InterruptedException("test interruption");
            }
        }
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
        public void terminateBundledSyncthing() {
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
            if (!exited) {
                throw new AssertionError("test execution has not exited");
            }
            return 0;
        }

        @Override
        public void destroy() {
        }

        private void exit() {
            exited = true;
        }
    }
}
