package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.runtime.HttpsCertificateState;
import com.nutomic.syncthingandroid.runtime.HttpsCertificateStorage;
import com.nutomic.syncthingandroid.runtime.ManagedStateException;
import com.nutomic.syncthingandroid.runtime.ManagedStateFailure;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies background certificate storage work and its rollback outcomes. */
public class CertificateStorageMutationTest {

    @Test
    public void productionWorkerQueuesRollbackSubmittedBeforeMutationCallbackReturns()
            throws Exception {
        ThreadPoolExecutor worker = CertificateStorageMutation.newWorkerExecutor();
        CountDownLatch restoreQueued = new CountDownLatch(1);
        CountDownLatch allowMutationCallbackToReturn = new CountDownLatch(1);
        CountDownLatch restoreCompleted = new CountDownLatch(1);
        AtomicBoolean restoreAccepted = new AtomicBoolean();
        AtomicReference<CertificateStorageMutation.Result> restoreResult =
                new AtomicReference<>();
        HttpsCertificateState previous = state(new byte[] { 0x01 }, new byte[] { 0x02 });
        FakeStorage storage = new FakeStorage(
                previous, state(new byte[] { 0x03 }, new byte[] { 0x04 })
        );

        try {
            assertTrue(CertificateStorageMutation.submit(
                    worker,
                    storage,
                    CertificateStorageMutation.replace(new byte[] { 0x03 }, new byte[] { 0x04 }),
                    new AtomicBoolean(),
                    ignored -> {
                        restoreAccepted.set(CertificateStorageMutation.submitRestore(
                                worker,
                                storage,
                                previous,
                                result -> {
                                    restoreResult.set(result);
                                    restoreCompleted.countDown();
                                }
                        ));
                        restoreQueued.countDown();
                        try {
                            allowMutationCallbackToReturn.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
            ));

            assertTrue(restoreQueued.await(2, TimeUnit.SECONDS));
            assertTrue("the production executor accepts one post-mutation rollback",
                    restoreAccepted.get());
            assertEquals(1, worker.getQueue().size());
            assertEquals("rollback waits until the first worker callback returns",
                    0, storage.restoreCount);

            allowMutationCallbackToReturn.countDown();
            assertTrue(restoreCompleted.await(2, TimeUnit.SECONDS));
            assertTrue(restoreResult.get().stateRestored());
            assertEquals(1, storage.restoreCount);
            assertSame(previous, storage.current);
        } finally {
            allowMutationCallbackToReturn.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void delayedStorageAcquisitionDoesNotBlockTheSubmittingThread() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch snapshotStarted = new CountDownLatch(1);
        CountDownLatch allowSnapshot = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        HttpsCertificateState previous = state(new byte[] { 0x01 }, new byte[] { 0x02 });
        FakeStorage storage = new FakeStorage(previous, state(new byte[] { 0x03 }, new byte[] { 0x04 }))
                .delaySnapshot(snapshotStarted, allowSnapshot);
        AtomicReference<CertificateStorageMutation.Result> result = new AtomicReference<>();

        try {
            assertTrue(CertificateStorageMutation.submit(
                    worker,
                    storage,
                    CertificateStorageMutation.replace(new byte[] { 0x03 }, new byte[] { 0x04 }),
                    new AtomicBoolean(),
                    value -> {
                        result.set(value);
                        completed.countDown();
                    }
            ));

            assertTrue(snapshotStarted.await(2, TimeUnit.SECONDS));
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS));
            assertEquals(0, storage.replaceCount);
            allowSnapshot.countDown();

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertTrue(result.get().succeeded());
            assertEquals(1, storage.replaceCount);
        } finally {
            allowSnapshot.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void cancellationDuringAcquisitionSkipsMutationAndReturnsAfterAcquisitionCompletes()
            throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch snapshotStarted = new CountDownLatch(1);
        CountDownLatch allowSnapshot = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        FakeStorage storage = new FakeStorage(
                state(new byte[] { 0x01 }, new byte[] { 0x02 }),
                state(new byte[] { 0x03 }, new byte[] { 0x04 })
        ).delaySnapshot(snapshotStarted, allowSnapshot);
        AtomicReference<CertificateStorageMutation.Result> result = new AtomicReference<>();

        try {
            assertTrue(CertificateStorageMutation.submit(
                    worker,
                    storage,
                    CertificateStorageMutation.replace(new byte[] { 0x03 }, new byte[] { 0x04 }),
                    cancelled,
                    value -> {
                        result.set(value);
                        completed.countDown();
                    }
            ));
            assertTrue(snapshotStarted.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            allowSnapshot.countDown();

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertTrue(result.get().cancelled());
            assertEquals(0, storage.replaceCount);
            assertEquals(0, storage.restoreCount);
        } finally {
            allowSnapshot.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void failedPartialMutationReportsFailedExactRollback() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch completed = new CountDownLatch(1);
        HttpsCertificateState previous = state(new byte[] { 0x01 }, new byte[] { 0x02 });
        HttpsCertificateState replacement = state(new byte[] { 0x03 }, new byte[] { 0x04 });
        FakeStorage storage = new FakeStorage(previous, replacement)
                .failReplacementAfterMutation()
                .failRestore();
        AtomicReference<CertificateStorageMutation.Result> result = new AtomicReference<>();

        try {
            assertTrue(CertificateStorageMutation.submit(
                    worker,
                    storage,
                    CertificateStorageMutation.replace(new byte[] { 0x03 }, new byte[] { 0x04 }),
                    new AtomicBoolean(),
                    value -> {
                        result.set(value);
                        completed.countDown();
                    }
            ));

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertFalse(result.get().succeeded());
            assertSame(replacement, storage.current);
            assertNotNull(result.get().failure());
            assertNotNull(result.get().rollbackFailure());
            assertFalse(result.get().stateRestored());
            assertEquals(1, storage.restoreCount);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void snapshotFailureDoesNotMutateOrAttemptRollback() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch completed = new CountDownLatch(1);
        FakeStorage storage = new FakeStorage(
                state(new byte[] { 0x01 }, new byte[] { 0x02 }),
                state(new byte[] { 0x03 }, new byte[] { 0x04 })
        ).failSnapshot();
        AtomicReference<CertificateStorageMutation.Result> result = new AtomicReference<>();

        try {
            assertTrue(CertificateStorageMutation.submit(
                    worker,
                    storage,
                    CertificateStorageMutation.replace(new byte[] { 0x03 }, new byte[] { 0x04 }),
                    new AtomicBoolean(),
                    value -> {
                        result.set(value);
                        completed.countDown();
                    }
            ));

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertFalse(result.get().succeeded());
            assertNotNull(result.get().failure());
            assertTrue(result.get().stateRestored());
            assertEquals(0, storage.replaceCount);
            assertEquals(0, storage.restoreCount);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void partialMutationFailureRestoresTheExactSnapshot() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch completed = new CountDownLatch(1);
        HttpsCertificateState previous = state(new byte[] { 0x01 }, new byte[] { 0x02 });
        FakeStorage storage = new FakeStorage(
                previous, state(new byte[] { 0x03 }, new byte[] { 0x04 })
        ).failReplacementAfterMutation();
        AtomicReference<CertificateStorageMutation.Result> result = new AtomicReference<>();

        try {
            assertTrue(CertificateStorageMutation.submit(
                    worker,
                    storage,
                    CertificateStorageMutation.replace(new byte[] { 0x03 }, new byte[] { 0x04 }),
                    new AtomicBoolean(),
                    value -> {
                        result.set(value);
                        completed.countDown();
                    }
            ));

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertFalse(result.get().succeeded());
            assertTrue(result.get().stateRestored());
            assertSame(previous, result.get().previous());
            assertSame(previous, storage.current);
            assertEquals(1, storage.restoreCount);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void mutationOwnershipRemainsHeldUntilWorkerCompletionBeforeRestart() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch snapshotStarted = new CountDownLatch(1);
        CountDownLatch allowSnapshot = new CountDownLatch(1);
        CountDownLatch serviceCallbackPosted = new CountDownLatch(1);
        AtomicBoolean ownsMutation = new AtomicBoolean(true);
        AtomicBoolean restarted = new AtomicBoolean();
        LinkedBlockingQueue<Runnable> serviceQueue = new LinkedBlockingQueue<>();
        FakeStorage storage = new FakeStorage(
                state(new byte[] { 0x01 }, new byte[] { 0x02 }),
                state(new byte[] { 0x03 }, new byte[] { 0x04 })
        ).delaySnapshot(snapshotStarted, allowSnapshot);

        try {
            assertTrue(CertificateStorageMutation.submit(
                    worker,
                    storage,
                    CertificateStorageMutation.replace(new byte[] { 0x03 }, new byte[] { 0x04 }),
                    new AtomicBoolean(),
                    result -> {
                        serviceQueue.add(() -> {
                            assertTrue(ownsMutation.get());
                            assertTrue(result.succeeded());
                            ownsMutation.set(false);
                            restarted.set(true);
                        });
                        serviceCallbackPosted.countDown();
                    }
            ));

            assertTrue(snapshotStarted.await(2, TimeUnit.SECONDS));
            assertTrue(ownsMutation.get());
            assertFalse(restarted.get());
            allowSnapshot.countDown();
            assertTrue(serviceCallbackPosted.await(2, TimeUnit.SECONDS));
            assertTrue(ownsMutation.get());
            assertFalse(restarted.get());

            Runnable completion = serviceQueue.poll(2, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
            assertFalse(ownsMutation.get());
            assertTrue(restarted.get());
        } finally {
            allowSnapshot.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void destructionRollbackCompletesOffThreadBeforeMutationOwnershipIsReleased()
            throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch restoreStarted = new CountDownLatch(1);
        CountDownLatch allowRestore = new CountDownLatch(1);
        CountDownLatch serviceCallbackPosted = new CountDownLatch(1);
        AtomicBoolean ownsMutation = new AtomicBoolean(true);
        LinkedBlockingQueue<Runnable> serviceQueue = new LinkedBlockingQueue<>();
        HttpsCertificateState previous = state(new byte[] { 0x01 }, new byte[] { 0x02 });
        HttpsCertificateState replacement = state(new byte[] { 0x03 }, new byte[] { 0x04 });
        FakeStorage storage = new FakeStorage(previous, replacement)
                .delayRestore(restoreStarted, allowRestore);
        storage.current = replacement;

        try {
            assertTrue(CertificateStorageMutation.submitRestore(
                    worker,
                    storage,
                    previous,
                    result -> {
                        serviceQueue.add(() -> {
                            assertTrue(result.stateRestored());
                            ownsMutation.set(false);
                        });
                        serviceCallbackPosted.countDown();
                    }
            ));

            assertTrue(restoreStarted.await(2, TimeUnit.SECONDS));
            assertSame(replacement, storage.current);
            assertTrue(ownsMutation.get());
            assertFalse(serviceCallbackPosted.await(100, TimeUnit.MILLISECONDS));
            allowRestore.countDown();
            assertTrue(serviceCallbackPosted.await(2, TimeUnit.SECONDS));
            assertTrue(ownsMutation.get());

            Runnable completion = serviceQueue.poll(2, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
            assertFalse(ownsMutation.get());
            assertSame(previous, storage.current);
        } finally {
            allowRestore.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void destructionWhileVerificationIsActiveWaitsForShutdownCompletion() throws Exception {
        HttpsCertificateState previous = state(new byte[] {0x01}, new byte[] {0x02});
        HttpsCertificateState replacement = state(new byte[] {0x03}, new byte[] {0x04});
        FakeStorage storage = new FakeStorage(previous, replacement);
        storage.current = replacement;
        CertificateVerificationState verification = new CertificateVerificationState();
        verification.beginVerification();
        LinkedBlockingQueue<Runnable> workerQueue = new LinkedBlockingQueue<>();
        AtomicReference<CertificateDestructionRestore.Outcome> outcome = new AtomicReference<>();
        AtomicInteger listenerCalls = new AtomicInteger();
        CertificateDestructionRestore restore =
                CertificateDestructionRestore.prepareForDestruction(
                        verification,
                        false,
                        workerQueue::add,
                        storage,
                        previous,
                        result -> {
                            outcome.set(result);
                            listenerCalls.incrementAndGet();
                        }
                );

        // The production destruction resolver closes verification and creates a restore gate.
        // The service's bounded shutdown completion is held until the exact exit proof arrives.
        assertFalse(verification.isWorkflowActive());
        assertNotNull(restore);
        assertTrue(FileMutationBarrier.certificateRestorePending());
        assertEquals(0, storage.restoreCount);
        assertTrue(workerQueue.isEmpty());
        assertNull(outcome.get());

        Runnable shutdownCompletion = () -> restore.onExecutionExitProven();
        shutdownCompletion.run();
        shutdownCompletion.run();
        assertEquals(1, workerQueue.size());
        assertEquals(0, storage.restoreCount);

        workerQueue.remove().run();
        assertTrue(outcome.get().executionExitProven());
        assertTrue(outcome.get().restoration().stateRestored());
        assertEquals(1, storage.restoreCount);
        assertEquals(1, listenerCalls.get());
        assertFalse(FileMutationBarrier.certificateRestorePending());
        assertFalse(restore.onExecutionExitUnproven(new IllegalStateException("late failure")));
        assertEquals(1, listenerCalls.get());
    }

    @Test
    public void destructionDuringPendingRestorationDoesNotQueueAnotherRestore() throws Exception {
        HttpsCertificateState previous = state(new byte[] {0x01}, new byte[] {0x02});
        HttpsCertificateState replacement = state(new byte[] {0x03}, new byte[] {0x04});
        FakeStorage storage = new FakeStorage(previous, replacement);
        storage.current = replacement;
        LinkedBlockingQueue<Runnable> workerQueue = new LinkedBlockingQueue<>();
        AtomicInteger existingCompletionCalls = new AtomicInteger();
        AtomicInteger destructionCompletionCalls = new AtomicInteger();

        assertTrue(CertificateStorageMutation.submitRestore(
                workerQueue::add, storage, previous,
                result -> existingCompletionCalls.incrementAndGet()
        ));
        assertEquals(1, workerQueue.size());

        CertificateVerificationState verification = new CertificateVerificationState();
        verification.beginVerification();
        CertificateDestructionRestore duplicate =
                CertificateDestructionRestore.prepareForDestruction(
                        verification,
                        true,
                        workerQueue::add,
                        storage,
                        previous,
                        result -> destructionCompletionCalls.incrementAndGet()
                );

        assertTrue(duplicate == null);
        assertFalse(verification.isWorkflowActive());
        assertEquals(1, workerQueue.size());
        assertEquals(0, storage.restoreCount);

        workerQueue.remove().run();
        assertEquals(1, storage.restoreCount);
        assertEquals(1, existingCompletionCalls.get());
        assertEquals(0, destructionCompletionCalls.get());
    }

    @Test
    public void unprovenExitAndExecutorRejectionFailClosedWithoutRestorationOrSuccess()
            throws Exception {
        HttpsCertificateState previous = state(new byte[] {0x01}, new byte[] {0x02});
        HttpsCertificateState replacement = state(new byte[] {0x03}, new byte[] {0x04});
        FakeStorage storage = new FakeStorage(previous, replacement);
        storage.current = replacement;
        AtomicBoolean ownsRollback = new AtomicBoolean(true);
        AtomicInteger listenerCalls = new AtomicInteger();
        AtomicReference<CertificateDestructionRestore.Outcome> outcome = new AtomicReference<>();
        CertificateVerificationState verification = new CertificateVerificationState();
        verification.beginVerification();
        CertificateDestructionRestore restore =
                CertificateDestructionRestore.prepareForDestruction(
                        verification,
                        false,
                        command -> {
                            throw new RejectedExecutionException("worker is shutting down");
                        },
                        storage,
                        previous,
                        result -> {
                            outcome.set(result);
                            ownsRollback.set(false);
                            listenerCalls.incrementAndGet();
                        }
        );

        assertNotNull(restore);
        assertTrue(restore.onExecutionExitUnproven(
                new IllegalStateException("could not verify exact execution exit")
        ));
        assertFalse(verification.automaticContinuationAllowed());
        assertEquals(CertificateVerificationState.Outcome.IGNORED,
                verification.onVerificationResult(false));
        assertFalse(restore.onExecutionExitProven());
        assertFalse(outcome.get().executionExitProven());
        assertEquals(0, storage.restoreCount);
        assertFalse(ownsRollback.get());
        assertEquals(1, listenerCalls.get());
        assertTrue(FileMutationBarrier.certificateRestorePending());
        clearFailedCertificateRestoreForNextCase();

        FakeStorage rejectedStorage = new FakeStorage(previous, replacement);
        AtomicReference<CertificateDestructionRestore.Outcome> rejectedOutcome =
                new AtomicReference<>();
        AtomicBoolean rejectedOwnership = new AtomicBoolean(true);
        AtomicInteger rejectedListeners = new AtomicInteger();
        CertificateVerificationState rejectedVerification = new CertificateVerificationState();
        rejectedVerification.beginVerification();
        CertificateDestructionRestore rejectedRestore =
                CertificateDestructionRestore.prepareForDestruction(
                        rejectedVerification,
                        false,
                        command -> {
                            throw new RejectedExecutionException("worker is shutting down");
                        },
                        rejectedStorage,
                        previous,
                        result -> {
                            rejectedOutcome.set(result);
                            rejectedOwnership.set(false);
                            rejectedListeners.incrementAndGet();
                        }
        );

        assertNotNull(rejectedRestore);
        assertTrue(rejectedRestore.onExecutionExitProven());
        assertFalse(rejectedOutcome.get().restoration().stateRestored());
        assertNotNull(rejectedOutcome.get().restoration().failure());
        assertEquals(0, rejectedStorage.restoreCount);
        assertFalse(rejectedOwnership.get());
        assertEquals(1, rejectedListeners.get());
        assertTrue(FileMutationBarrier.certificateRestorePending());
        clearFailedCertificateRestoreForNextCase();
        assertFalse(rejectedRestore.onExecutionExitUnproven(
                new IllegalStateException("late shutdown failure")
        ));
        assertEquals(1, rejectedListeners.get());
    }

    @Test
    public void failedDestructionRestoreReleasesOwnershipAndResolvesListenerOnce() throws Exception {
        HttpsCertificateState previous = state(new byte[] {0x01}, new byte[] {0x02});
        HttpsCertificateState replacement = state(new byte[] {0x03}, new byte[] {0x04});
        FakeStorage storage = new FakeStorage(previous, replacement).failRestore();
        storage.current = replacement;
        AtomicBoolean ownsRollback = new AtomicBoolean(true);
        AtomicInteger listenerCalls = new AtomicInteger();
        AtomicBoolean successAdvertised = new AtomicBoolean();
        AtomicReference<CertificateDestructionRestore.Outcome> outcome = new AtomicReference<>();
        CertificateDestructionRestore restore = new CertificateDestructionRestore(
                Runnable::run,
                storage,
                previous,
                result -> {
                    outcome.set(result);
                    ownsRollback.set(false);
                    successAdvertised.set(result.restoration().succeeded());
                    listenerCalls.incrementAndGet();
                }
        );

        assertTrue(restore.onExecutionExitProven());
        assertFalse(outcome.get().restoration().stateRestored());
        assertNotNull(outcome.get().restoration().failure());
        assertEquals(1, storage.restoreCount);
        assertFalse(ownsRollback.get());
        assertFalse(successAdvertised.get());
        assertEquals(1, listenerCalls.get());
        assertTrue(FileMutationBarrier.certificateRestorePending());
        clearFailedCertificateRestoreForNextCase();
        assertFalse(restore.onExecutionExitProven());
        assertFalse(restore.onExecutionExitUnproven(new IllegalStateException("late failure")));
        assertEquals(1, listenerCalls.get());
    }

    @Test
    public void executorRejectionReportsFailureWithoutAcquiringOrMutatingStorage() throws Exception {
        Executor rejected = command -> {
            throw new RejectedExecutionException("worker is shutting down");
        };
        FakeStorage storage = new FakeStorage(
                state(new byte[] { 0x01 }, new byte[] { 0x02 }),
                state(new byte[] { 0x03 }, new byte[] { 0x04 })
        );
        AtomicReference<CertificateStorageMutation.Result> result = new AtomicReference<>();

        boolean accepted = CertificateStorageMutation.submit(
                rejected,
                storage,
                CertificateStorageMutation.replace(new byte[] { 0x03 }, new byte[] { 0x04 }),
                new AtomicBoolean(),
                result::set
        );

        assertFalse(accepted);
        assertNotNull(result.get());
        assertFalse(result.get().succeeded());
        assertTrue(result.get().stateRestored());
        assertEquals(0, storage.snapshotCount);
        assertEquals(0, storage.replaceCount);
    }

    private static HttpsCertificateState state(byte[] certificate, byte[] key) throws Exception {
        Constructor<HttpsCertificateState> constructor = HttpsCertificateState.class
                .getDeclaredConstructor(byte[].class, byte[].class);
        constructor.setAccessible(true);
        return constructor.newInstance(certificate, key);
    }

    private static void clearFailedCertificateRestoreForNextCase() {
        FileMutationBarrier.CertificateRestoreReservation recovery =
                FileMutationBarrier.tryReserveCertificateRestore();
        assertNotNull(recovery);
        assertTrue(recovery.release(true));
    }

    private static final class FakeStorage implements HttpsCertificateStorage {
        private final HttpsCertificateState previous;
        private final HttpsCertificateState replacement;
        private HttpsCertificateState current;
        private CountDownLatch snapshotStarted;
        private CountDownLatch allowSnapshot;
        private CountDownLatch restoreStarted;
        private CountDownLatch allowRestore;
        private boolean failReplacement;
        private boolean failSnapshot;
        private boolean failRestore;
        private int snapshotCount;
        private int replaceCount;
        private int restoreCount;

        FakeStorage(HttpsCertificateState previous, HttpsCertificateState replacement) {
            this.previous = previous;
            this.replacement = replacement;
            this.current = previous;
        }

        FakeStorage delaySnapshot(CountDownLatch started, CountDownLatch allow) {
            snapshotStarted = started;
            allowSnapshot = allow;
            return this;
        }

        FakeStorage failReplacementAfterMutation() {
            failReplacement = true;
            return this;
        }

        FakeStorage failSnapshot() {
            failSnapshot = true;
            return this;
        }

        FakeStorage failRestore() {
            failRestore = true;
            return this;
        }

        FakeStorage delayRestore(CountDownLatch started, CountDownLatch allow) {
            restoreStarted = started;
            allowRestore = allow;
            return this;
        }

        @Override
        public HttpsCertificateState snapshot() throws ManagedStateException {
            snapshotCount++;
            if (failSnapshot) {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_ACCESS_FAILED,
                        "snapshot failed before certificate mutation"
                );
            }
            if (snapshotStarted != null) {
                snapshotStarted.countDown();
                try {
                    if (!allowSnapshot.await(2, TimeUnit.SECONDS)) {
                        throw new ManagedStateException(
                                ManagedStateFailure.STATE_ACCESS_FAILED,
                                "Timed out while simulating root acquisition"
                        );
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ManagedStateException(
                            ManagedStateFailure.STATE_ACCESS_FAILED,
                            "Interrupted while simulating root acquisition",
                            e
                    );
                }
            }
            return previous;
        }

        @Override
        public void replace(byte[] certificatePem, byte[] keyPem) throws ManagedStateException {
            replaceCount++;
            current = replacement;
            if (failReplacement) {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_ACCESS_FAILED,
                        "replacement failed after changing the certificate"
                );
            }
        }

        @Override
        public void reset() {
            replaceCount++;
            current = null;
        }

        @Override
        public void restore(HttpsCertificateState state) throws ManagedStateException {
            restoreCount++;
            if (restoreStarted != null) {
                restoreStarted.countDown();
                try {
                    if (!allowRestore.await(2, TimeUnit.SECONDS)) {
                        throw new ManagedStateException(
                                ManagedStateFailure.STATE_ACCESS_FAILED,
                                "Timed out while simulating asynchronous restore"
                        );
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ManagedStateException(
                            ManagedStateFailure.STATE_ACCESS_FAILED,
                            "Interrupted while simulating asynchronous restore",
                            e
                    );
                }
            }
            if (failRestore) {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_ACCESS_FAILED,
                        "rollback failed"
                );
            }
            current = state;
        }
    }
}
