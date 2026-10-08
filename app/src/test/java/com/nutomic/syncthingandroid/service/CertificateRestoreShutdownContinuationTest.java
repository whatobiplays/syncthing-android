package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies certificate recovery joins an active shutdown before running its continuation. */
public class CertificateRestoreShutdownContinuationTest {

    @Test
    public void restorationCompletionWaitsForAnOutstandingShutdown() {
        List<Runnable> shutdownCompletions = new ArrayList<>();
        List<Runnable> shutdownFailures = new ArrayList<>();
        AtomicInteger completionCalls = new AtomicInteger();
        AtomicInteger failureCalls = new AtomicInteger();

        CertificateRestoreShutdownContinuation.runAfterShutdown(
                true,
                completionCalls::incrementAndGet,
                failureCalls::incrementAndGet,
                (completion, failure) -> {
                    shutdownCompletions.add(completion);
                    shutdownFailures.add(failure);
                },
                Runnable::run
        );

        assertEquals(0, completionCalls.get());
        assertEquals(0, failureCalls.get());
        assertEquals(1, shutdownCompletions.size());
        assertEquals(1, shutdownFailures.size());
        shutdownCompletions.remove(0).run();
        assertEquals(1, completionCalls.get());
    }

    @Test
    public void restorationCompletionUsesTheExistingCompletionPathWhenNoShutdownIsActive() {
        AtomicInteger completionCalls = new AtomicInteger();
        AtomicInteger queuedCalls = new AtomicInteger();

        CertificateRestoreShutdownContinuation.runAfterShutdown(
                false,
                completionCalls::incrementAndGet,
                () -> { },
                (completion, failure) -> queuedCalls.incrementAndGet(),
                Runnable::run
        );

        assertEquals(1, completionCalls.get());
        assertEquals(0, queuedCalls.get());
    }

    @Test
    public void failedJoinedShutdownResolvesCertificateFailureOnlyOnce() {
        CertificateVerificationState verification = new CertificateVerificationState();
        verification.beginVerification();
        assertEquals(CertificateVerificationState.Outcome.FAILURE,
                verification.onVerificationResult(false));
        AtomicInteger successCalls = new AtomicInteger();
        AtomicInteger failureCalls = new AtomicInteger();
        AtomicInteger relaunchCalls = new AtomicInteger();
        AtomicReference<Runnable> shutdownCompletion = new AtomicReference<>();
        AtomicReference<Runnable> shutdownFailure = new AtomicReference<>();
        Runnable finishAfterRestore = verification.completeFailureRecovery(
                () -> { },
                () -> true,
                relaunchCalls::incrementAndGet,
                () -> { },
                successCalls::incrementAndGet
        );
        Runnable failAfterShutdown = () -> verification.failFailureRecoveryShutdown(
                failureCalls::incrementAndGet
        );

        CertificateRestoreShutdownContinuation.runAfterShutdown(
                true,
                finishAfterRestore,
                failAfterShutdown,
                (completion, failure) -> {
                    shutdownCompletion.set(completion);
                    shutdownFailure.set(failure);
                },
                Runnable::run
        );

        assertEquals(0, successCalls.get());
        assertEquals(0, failureCalls.get());
        shutdownFailure.get().run();
        shutdownFailure.get().run();
        shutdownCompletion.get().run();

        assertEquals(0, successCalls.get());
        assertEquals(1, failureCalls.get());
        assertEquals(0, relaunchCalls.get());
    }
}
