package com.nutomic.syncthingandroid.service;

import androidx.annotation.Nullable;

import com.nutomic.syncthingandroid.runtime.HttpsCertificateState;
import com.nutomic.syncthingandroid.runtime.HttpsCertificateStorage;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Gates one destruction rollback on the service proving its exact execution has exited. */
final class CertificateDestructionRestore {
    @FunctionalInterface
    interface Completion {
        void onComplete(Outcome outcome);
    }

    /** Terminal result for the exit proof and, when safe, the background restoration. */
    static final class Outcome {
        private final boolean executionExitProven;
        private final CertificateStorageMutation.Result restoration;
        private final Throwable shutdownFailure;

        private Outcome(
                boolean executionExitProven,
                CertificateStorageMutation.Result restoration,
                Throwable shutdownFailure
        ) {
            this.executionExitProven = executionExitProven;
            this.restoration = restoration;
            this.shutdownFailure = shutdownFailure;
        }

        boolean executionExitProven() {
            return executionExitProven;
        }

        CertificateStorageMutation.Result restoration() {
            return restoration;
        }

        Throwable shutdownFailure() {
            return shutdownFailure;
        }
    }

    private enum Phase {
        WAITING_FOR_EXIT,
        RESTORING,
        FINISHED
    }

    private final Executor executor;
    private final HttpsCertificateStorage storage;
    private final HttpsCertificateState previous;
    private final Completion completion;
    @Nullable private final FileMutationBarrier.CertificateRestoreReservation reservation;
    private Phase phase = Phase.WAITING_FOR_EXIT;

    /**
     * Resolves an active verification for service destruction and reserves one deferred restore.
     *
     * <p>An existing certificate mutation already owns storage and remains responsible for its
     * completion. In that case destruction closes verification without scheduling a second
     * restore. Otherwise, the returned gate can start restoration only after the caller proves
     * that the exact Syncthing execution has exited.</p>
     */
    static CertificateDestructionRestore prepareForDestruction(
            CertificateVerificationState verification,
            boolean mutationAlreadyPending,
            Executor executor,
            HttpsCertificateStorage storage,
            HttpsCertificateState previous,
            Completion completion
    ) {
        Objects.requireNonNull(verification);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(storage);
        Objects.requireNonNull(previous);
        Objects.requireNonNull(completion);
        CertificateDestructionRestore[] restore = new CertificateDestructionRestore[1];
        verification.resolveForDestruction(
                () -> {
                    if (!mutationAlreadyPending) {
                        FileMutationBarrier.CertificateRestoreReservation reservation =
                                FileMutationBarrier.tryReserveCertificateRestore();
                        restore[0] = new CertificateDestructionRestore(
                                executor, storage, previous, reservation, completion
                        );
                    }
                },
                () -> { }
        );
        return restore[0];
    }

    CertificateDestructionRestore(
            Executor executor,
            HttpsCertificateStorage storage,
            HttpsCertificateState previous,
            Completion completion
    ) {
        this(
                executor,
                storage,
                previous,
                FileMutationBarrier.tryReserveCertificateRestore(),
                completion
        );
    }

    CertificateDestructionRestore(
            Executor executor,
            HttpsCertificateStorage storage,
            HttpsCertificateState previous,
            @Nullable FileMutationBarrier.CertificateRestoreReservation reservation,
            Completion completion
    ) {
        this.executor = Objects.requireNonNull(executor);
        this.storage = Objects.requireNonNull(storage);
        this.previous = Objects.requireNonNull(previous);
        this.reservation = reservation;
        this.completion = Objects.requireNonNull(completion);
    }

    /** Starts restoration once the caller has proven the old execution exited. */
    boolean onExecutionExitProven() {
        synchronized (this) {
            if (phase != Phase.WAITING_FOR_EXIT) return false;
            phase = Phase.RESTORING;
        }
        if (reservation == null) {
            finishRestore(CertificateStorageMutation.failedRestore(
                    previous,
                    new RejectedExecutionException(
                            "Certificate storage is owned by another restore"
                    )
            ));
            return true;
        }
        CertificateStorageMutation.submitRestore(executor, storage, previous, this::finishRestore);
        return true;
    }

    /** Resolves without touching certificate files when shutdown cannot prove exact exit. */
    boolean onExecutionExitUnproven(Throwable failure) {
        Objects.requireNonNull(failure);
        synchronized (this) {
            if (phase != Phase.WAITING_FOR_EXIT) return false;
            phase = Phase.FINISHED;
        }
        releaseReservation(false);
        completion.onComplete(new Outcome(false, null, failure));
        return true;
    }

    private void finishRestore(CertificateStorageMutation.Result result) {
        synchronized (this) {
            if (phase != Phase.RESTORING) return;
            phase = Phase.FINISHED;
        }
        releaseReservation(result.stateRestored());
        completion.onComplete(new Outcome(true, result, null));
    }

    private void releaseReservation(boolean safeToStart) {
        if (reservation != null) reservation.release(safeToStart);
    }
}
