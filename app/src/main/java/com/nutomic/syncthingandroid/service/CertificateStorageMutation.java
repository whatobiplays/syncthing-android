package com.nutomic.syncthingandroid.service;

import com.nutomic.syncthingandroid.runtime.HttpsCertificateState;
import com.nutomic.syncthingandroid.runtime.HttpsCertificateStorage;
import com.nutomic.syncthingandroid.runtime.ManagedStateException;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs bounded certificate-storage work away from the service thread and reports immutable results. */
final class CertificateStorageMutation {
    @FunctionalInterface
    interface Action {
        void run(HttpsCertificateStorage storage) throws ManagedStateException;
    }

    @FunctionalInterface
    interface Completion {
        void onComplete(Result result);
    }

    private enum Status {
        APPLIED,
        FAILED,
        CANCELLED
    }

    /** Immutable outcome shared from the storage worker back to the service thread. */
    static final class Result {
        private final Status status;
        private final HttpsCertificateState previous;
        private final Throwable failure;
        private final Throwable rollbackFailure;
        private final boolean stateRestored;

        private Result(
                Status status,
                HttpsCertificateState previous,
                Throwable failure,
                Throwable rollbackFailure,
                boolean stateRestored
        ) {
            this.status = status;
            this.previous = previous;
            this.failure = failure;
            this.rollbackFailure = rollbackFailure;
            this.stateRestored = stateRestored;
        }

        boolean succeeded() {
            return status == Status.APPLIED;
        }

        boolean cancelled() {
            return status == Status.CANCELLED;
        }

        HttpsCertificateState previous() {
            return previous;
        }

        Throwable failure() {
            return failure;
        }

        Throwable rollbackFailure() {
            return rollbackFailure;
        }

        boolean stateRestored() {
            return stateRestored;
        }
    }

    private CertificateStorageMutation() {
    }

    /** Creates the single bounded worker used for certificate snapshots, writes, and restores. */
    static ThreadPoolExecutor newWorkerExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                runnable -> {
                    Thread worker = new Thread(runnable, "HTTPS certificate storage");
                    worker.setDaemon(true);
                    return worker;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /** Builds an immutable failed restore outcome without accessing certificate storage. */
    static Result failedRestore(HttpsCertificateState previous, Throwable failure) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(failure);
        return new Result(Status.FAILED, previous, failure, failure, false);
    }

    /** Copies replacement bytes immediately so callers cannot change a queued mutation. */
    static Action replace(byte[] certificatePem, byte[] keyPem) {
        byte[] certificate = Objects.requireNonNull(certificatePem).clone();
        byte[] key = Objects.requireNonNull(keyPem).clone();
        return storage -> storage.replace(certificate.clone(), key.clone());
    }

    /** Returns the operation that removes the HTTPS certificate pair. */
    static Action reset() {
        return HttpsCertificateStorage::reset;
    }

    /** Submits snapshot, mutation, and any required rollback to the supplied bounded executor. */
    static boolean submit(
            Executor executor,
            HttpsCertificateStorage storage,
            Action action,
            AtomicBoolean cancelled,
            Completion completion
    ) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(storage);
        Objects.requireNonNull(action);
        Objects.requireNonNull(cancelled);
        Objects.requireNonNull(completion);
        try {
            executor.execute(() -> completion.onComplete(execute(storage, action, cancelled)));
            return true;
        } catch (RejectedExecutionException rejected) {
            completion.onComplete(new Result(Status.FAILED, null, rejected, null, true));
            return false;
        }
    }

    /** Submits a rollback after verification failure or service destruction. */
    static boolean submitRestore(
            Executor executor,
            HttpsCertificateStorage storage,
            HttpsCertificateState previous,
            Completion completion
    ) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(storage);
        Objects.requireNonNull(previous);
        Objects.requireNonNull(completion);
        try {
            executor.execute(() -> completion.onComplete(restore(storage, previous)));
            return true;
        } catch (RejectedExecutionException rejected) {
            completion.onComplete(new Result(Status.FAILED, previous, rejected, rejected, false));
            return false;
        }
    }

    private static Result execute(
            HttpsCertificateStorage storage,
            Action action,
            AtomicBoolean cancelled
    ) {
        if (cancelled.get()) {
            return new Result(Status.CANCELLED, null, null, null, true);
        }

        final HttpsCertificateState previous;
        try {
            previous = storage.snapshot();
        } catch (ManagedStateException | RuntimeException failure) {
            return new Result(Status.FAILED, null, failure, null, true);
        }
        if (cancelled.get()) {
            return new Result(Status.CANCELLED, previous, null, null, true);
        }

        try {
            action.run(storage);
        } catch (ManagedStateException | RuntimeException failure) {
            Throwable rollbackFailure = restoreState(storage, previous);
            return new Result(
                    Status.FAILED,
                    previous,
                    failure,
                    rollbackFailure,
                    rollbackFailure == null
            );
        }

        if (cancelled.get()) {
            Throwable rollbackFailure = restoreState(storage, previous);
            return new Result(
                    Status.CANCELLED,
                    previous,
                    null,
                    rollbackFailure,
                    rollbackFailure == null
            );
        }
        return new Result(Status.APPLIED, previous, null, null, false);
    }

    private static Result restore(
            HttpsCertificateStorage storage,
            HttpsCertificateState previous
    ) {
        Throwable failure = restoreState(storage, previous);
        return new Result(
                failure == null ? Status.APPLIED : Status.FAILED,
                previous,
                failure,
                failure,
                failure == null
        );
    }

    /** Clears interruption only for rollback, then restores the worker's interrupt status. */
    private static Throwable restoreState(
            HttpsCertificateStorage storage,
            HttpsCertificateState previous
    ) {
        boolean interrupted = Thread.interrupted();
        try {
            storage.restore(previous);
            return null;
        } catch (ManagedStateException | RuntimeException failure) {
            return failure;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
