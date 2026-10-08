package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * Covers the request-local cancellation fence and the atomic shell handoff of bounded root
 * activation.
 *
 * <p>Activation is asynchronous, so a request may still be completing when its own caller cancels
 * it. Cancellation is request-local: one caller cancelling its request never invalidates another
 * caller's request. Ownership of the shell an activation acquires is decided by the request's
 * atomic handoff, so a shell that arrives for a cancelled request is closed instead of being handed
 * out, and a cancellation that races the handoff can neither lose the shell nor leave it unowned.</p>
 *
 * <p>The tests are deterministic: the fake factory never touches real superuser transport, and the
 * cancellation points are driven by the test itself rather than by timing.</p>
 */
public class RootActivationTest {

    @Test
    public void cancelledRequestClosesLateShellAndReportsObsolete() {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        FakeRootTransport.Factory factory = new FakeRootTransport.Factory(device);
        RootActivation activation = new RootActivation(factory, 60_000);
        RootActivation.Request request = activation.begin();
        assertNull("cancelling before any shell exists takes nothing", request.cancelAndTake());

        RootTransportException failure = assertThrows(
                RootTransportException.class,
                () -> activation.acquire(request)
        );

        assertEquals(RootFailure.ROOT_ACTIVATION_OBSOLETE, failure.failure());
        assertEquals("the late shell must be closed", 1, device.shellCloses);
        assertNull("a cancelled request publishes no shell", request.take());
    }

    @Test
    public void requestCancelledWhileShellIsBeingAcquiredIsAlsoObsolete() {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        RootActivation[] activation = new RootActivation[1];
        RootActivation.Request[] request = new RootActivation.Request[1];
        RootActivation rootActivation = new RootActivation(timeoutMillis -> {
            // Models the owning caller cancelling while the shell is still being acquired.
            request[0].cancelAndTake();
            return new FakeRootTransport.Shell(device, 0);
        }, 60_000);
        activation[0] = rootActivation;
        request[0] = activation[0].begin();

        RootTransportException failure = assertThrows(
                RootTransportException.class,
                () -> activation[0].acquire(request[0])
        );

        assertEquals(RootFailure.ROOT_ACTIVATION_OBSOLETE, failure.failure());
        assertEquals("the late shell must be closed", 1, device.shellCloses);
    }

    @Test
    public void activeRequestPublishesLiveShellForItsCaller() {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        RootActivation activation = new RootActivation(
                new FakeRootTransport.Factory(device),
                60_000
        );
        RootActivation.Request request = activation.begin();

        activation.acquire(request);
        RootShell shell = request.take().shell();

        assertTrue(shell instanceof FakeRootTransport.Shell);
        assertEquals(0, device.shellCloses);
        shell.close();
        assertEquals(1, device.shellCloses);
    }

    @Test
    public void cancellingOneRequestDoesNotCancelAnother() {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        RootActivation activation = new RootActivation(
                new FakeRootTransport.Factory(device),
                60_000
        );
        RootActivation.Request cancelled = activation.begin();
        RootActivation.Request current = activation.begin();

        cancelled.cancelAndTake();
        activation.acquire(current);

        assertTrue(
                "the current request keeps its shell",
                current.take().shell() instanceof FakeRootTransport.Shell
        );
        assertEquals("another request's cancellation must not close it", 0, device.shellCloses);
        assertEquals(
                RootFailure.ROOT_ACTIVATION_OBSOLETE,
                assertThrows(
                        RootTransportException.class,
                        () -> activation.acquire(cancelled)
                ).failure()
        );
    }

    @Test
    public void twoConcurrentRequestsCompleteIndependently() throws Exception {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        CountDownLatch bothAcquiring = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        RootActivation activation = new RootActivation(timeoutMillis -> {
            bothAcquiring.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The test did not release the acquisition");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("The acquisition was interrupted", e);
            }
            return new FakeRootTransport.Shell(device, 0);
        }, 60_000);
        RootActivation.Request first = activation.begin();
        RootActivation.Request second = activation.begin();

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<RootShellSession> firstShell = workers.submit(() -> {
                activation.acquire(first);
                return first.take();
            });
            Future<RootShellSession> secondShell = workers.submit(() -> {
                activation.acquire(second);
                return second.take();
            });
            assertTrue(
                    "both legitimate requests reach the transport at the same time",
                    bothAcquiring.await(5, TimeUnit.SECONDS)
            );
            release.countDown();

            RootShellSession firstResult = firstShell.get(5, TimeUnit.SECONDS);
            RootShellSession secondResult = secondShell.get(5, TimeUnit.SECONDS);
            assertNotSame(firstResult, secondResult);
            assertEquals(
                    "neither concurrent request may close the other's shell",
                    0,
                    device.shellCloses
            );
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void cancellationRacingTheHandoffClosesTheAcquiredShellExactlyOnce() throws Exception {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        CountDownLatch atHandoff = new CountDownLatch(1);
        CountDownLatch releaseHandoff = new CountDownLatch(1);
        RootActivation activation = new RootActivation(
                new FakeRootTransport.Factory(device),
                60_000,
                acquired -> {
                    // Pauses exactly at the handoff boundary: the transport produced a shell and
                    // ownership of it is still undecided.
                    atHandoff.countDown();
                    awaitQuietly(releaseHandoff);
                }
        );
        RootActivation.Request request = activation.begin();
        AtomicReference<RootShellSession> acquiredByCaller = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                activation.acquire(request);
                acquiredByCaller.set(request.take());
            } catch (RuntimeException obsolete) {
                acquiredByCaller.set(null);
            }
        }, "root-handoff-race");
        worker.start();

        assertTrue(
                "the activation must pause exactly at the acquired-shell handoff boundary",
                atHandoff.await(5, TimeUnit.SECONDS)
        );
        assertNull(
                "a cancelled request publishes no shell",
                request.cancelAndTake()
        );
        releaseHandoff.countDown();
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse("the activation must finish", worker.isAlive());
        assertNull(
                "cancellation keeps the shell out of the caller's hands",
                acquiredByCaller.get()
        );
        assertEquals("the shell is closed exactly once, never leaked", 1, device.shellCloses);
        assertEquals(
                "the acquisition and its close stay balanced",
                device.acquisitions,
                device.shellCloses
        );
    }

    @Test
    public void cancellationAfterTheHandoffTakesThePublishedShellInsteadOfLeakingIt() {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        RootActivation activation = new RootActivation(
                new FakeRootTransport.Factory(device),
                60_000
        );
        RootActivation.Request request = activation.begin();

        activation.acquire(request);
        RootShell stolen = request.cancelAndTake().shell();

        assertTrue(
                "cancellation owns the shell that was already published",
                stolen instanceof FakeRootTransport.Shell
        );
        assertNull("the caller must not also receive the shell", request.take());
        assertEquals("nothing is closed before cancellation takes the shell", 0, device.shellCloses);
        stolen.close();
        assertEquals("the shell is closed exactly once", 1, device.shellCloses);
    }

    @Test
    public void typedActivationFailurePassesThroughUnchanged() {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        device.activationFailure = RootFailure.ROOT_DENIED;
        RootActivation activation = new RootActivation(
                new FakeRootTransport.Factory(device),
                60_000
        );

        RootTransportException failure = assertThrows(
                RootTransportException.class,
                () -> activation.acquire(activation.begin())
        );

        assertEquals(RootFailure.ROOT_DENIED, failure.failure());
        assertEquals("no shell was acquired, so none may be closed", 0, device.shellCloses);
    }

    @Test
    public void nonPositiveDeadlineIsRejected() {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        assertThrows(
                IllegalArgumentException.class,
                () -> new RootActivation(new FakeRootTransport.Factory(device), 0)
        );
    }

    private static void awaitQuietly(CountDownLatch latch) {
        boolean released = false;
        while (!released) {
            try {
                released = latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                // The handoff boundary is not interruptible: cancellation decides ownership, so
                // this test gate keeps waiting the way a real transport acquisition does.
            }
        }
    }
}
