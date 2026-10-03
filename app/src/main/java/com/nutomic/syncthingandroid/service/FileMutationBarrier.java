package com.nutomic.syncthingandroid.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Holds a shutdown continuation through stopped-state file mutation and its startup handoff. */
final class FileMutationBarrier {
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean completed = new AtomicBoolean();
    private final boolean asyncOwner;
    private final Runnable onShutdownFailure;
    private final Runnable onContinuationConflict;
    private volatile boolean safeToMutate;
    private Runnable afterMutation;
    private Runnable asyncMutation;
    private boolean automaticStartupSuppressed;
    private boolean abandoned;

    FileMutationBarrier() {
        this(false, null, null);
    }

    private FileMutationBarrier(
            boolean asyncOwner,
            Runnable onShutdownFailure,
            Runnable onContinuationConflict
    ) {
        this.asyncOwner = asyncOwner;
        this.onShutdownFailure = onShutdownFailure;
        this.onContinuationConflict = onContinuationConflict;
    }

    /** Reserves the shared mutation slot for an asynchronous mutation before shutdown begins. */
    static FileMutationBarrier reserveAsyncOwner(
            FileMutationBarrier currentOwner,
            Runnable onRejected,
            Runnable onShutdownFailure,
            Runnable onContinuationConflict
    ) {
        if (currentOwner != null) {
            onRejected.run();
            return null;
        }
        return new FileMutationBarrier(true, onShutdownFailure, onContinuationConflict);
    }

    boolean isAsyncOwner() {
        return asyncOwner;
    }

    /** Prevents this mutation's completion from restarting Syncthing after an explicit STOP. */
    void suppressAutomaticStartup() {
        automaticStartupSuppressed = true;
    }

    boolean automaticStartupSuppressed() {
        return automaticStartupSuppressed;
    }

    /** Clears any later deferred start that would otherwise bypass this mutation's STOP latch. */
    boolean clearDeferredStartIfSuppressed(ShutdownStartIntent startIntent) {
        if (!automaticStartupSuppressed) return false;
        startIntent.clear();
        return true;
    }

    /** Returns whether current Run Conditions permit this mutation to restart Syncthing. */
    boolean shouldAutomaticallyStartAfterMutation(boolean shouldRunNow) {
        return !automaticStartupSuppressed && shouldRunNow;
    }

    /** Rejects a second mutation owner and invokes its failure callback exactly once. */
    static boolean rejectConcurrentMutation(
            FileMutationBarrier currentOwner,
            Runnable onRejected
    ) {
        if (currentOwner == null) return false;
        onRejected.run();
        return true;
    }

    /**
     * Rejects stopped-state mutation when a lifecycle handle remains unresolved without exact
     * execution identity or proven exit. A dead Java thread can still leave an unproven process.
     */
    static boolean rejectIfNoSafeShutdownPath(
            boolean lifecycleHandlePresent,
            boolean exactIdentityAvailable,
            boolean executionExitProven,
            Runnable onRejected
    ) {
        if (!lifecycleHandlePresent || exactIdentityAvailable || executionExitProven) return false;
        onRejected.run();
        return true;
    }

    /**
     * Rejects a new file mutation when it would join a shutdown that already owns a lifecycle
     * continuation. Continuations are opaque and may asynchronously admit a reset or restart, so
     * the mutation cannot safely determine when its own post-mutation lifecycle work may proceed.
     */
    static boolean rejectIfShutdownContinuationPending(
            boolean shutdownInProgress,
            boolean continuationPending,
            Runnable onRejected
    ) {
        if (!shutdownInProgress || !continuationPending) return false;
        onRejected.run();
        return true;
    }

    /** Fails a stopped-state mutation before its write callback when shutdown acquired work. */
    static boolean rejectMutationForDeferredCompletion(
            FileMutationBarrier barrier,
            Runnable deferredCompletion
    ) {
        if (deferredCompletion == null) return false;
        if (barrier.isAsyncOwner()) {
            barrier.stopFailedForContinuationConflict();
        } else {
            barrier.stopFailed();
        }
        return true;
    }

    /** Runs the file mutation before any completion that was already queued for shutdown. */
    static Runnable mutationBeforeDeferredCompletion(
            Runnable mutation,
            Runnable deferredCompletion
    ) {
        if (mutation == null) return deferredCompletion;
        if (deferredCompletion == null) return mutation;
        return () -> {
            mutation.run();
            deferredCompletion.run();
        };
    }

    synchronized void stopCompleted(Runnable continuation) {
        if (!completed.compareAndSet(false, true)) return;
        afterMutation = continuation;
        safeToMutate = true;
        stopped.countDown();
    }

    /** Stores the stopped-state write and restart handoff owned by an asynchronous mutation. */
    void setAsyncMutation(Runnable mutation) {
        if (!asyncOwner) throw new IllegalStateException("Mutation owner is not asynchronous");
        if (asyncMutation != null) throw new IllegalStateException("Async mutation is already set");
        asyncMutation = mutation;
    }

    /** Completes an asynchronous owner and returns its service-thread mutation continuation. */
    Runnable completeAsyncOwner() {
        if (!asyncOwner) throw new IllegalStateException("Mutation owner is not asynchronous");
        stopCompleted(asyncMutation);
        return takeAfterMutation();
    }

    void stopFailed() {
        stopFailed(onShutdownFailure);
    }

    void stopFailedForContinuationConflict() {
        stopFailed(onContinuationConflict);
    }

    private void stopFailed(Runnable failureCallback) {
        if (!completed.compareAndSet(false, true)) return;
        safeToMutate = false;
        stopped.countDown();
        if (failureCallback != null) failureCallback.run();
    }

    boolean awaitSafeToMutate() {
        if (Thread.currentThread().isInterrupted()) return false;
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return safeToMutate;
    }

    /** Records that an interrupted caller will not perform its file mutation. */
    void abandon() {
        abandoned = true;
    }

    boolean isAbandoned() {
        return abandoned;
    }

    synchronized Runnable takeAfterMutation() {
        Runnable continuation = afterMutation;
        afterMutation = null;
        return continuation;
    }
}
