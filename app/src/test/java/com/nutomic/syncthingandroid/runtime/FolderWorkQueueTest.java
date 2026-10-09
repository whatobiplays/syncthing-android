package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Deterministic tests for the folder-work queue.
 *
 * <p>The queue is what keeps filesystem work, and privileged helper sessions, off the thread that
 * draws the user interface. The tests therefore drive the worker and the result hand-off
 * explicitly: the hand-off stays pending until the test runs it, so no assertion depends on a
 * timing guess.</p>
 */
public class FolderWorkQueueTest {
    /** Hand-offs the queue produced, in order, until the test runs them. */
    private final List<Runnable> handOffs = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FolderWorkQueue queue;

    @Before
    public void setUp() {
        queue = new FolderWorkQueue(worker, handOffs::add);
    }

    @After
    public void tearDown() {
        queue.close();
    }

    @Test
    public void workRunsOnTheWorkerAndItsResultIsDeliveredByTheHandOff() throws Exception {
        String callingThread = Thread.currentThread().getName();
        List<String> workThreads = Collections.synchronizedList(new ArrayList<>());
        List<String> delivered = new ArrayList<>();

        queue.submit(
                () -> {
                    workThreads.add(Thread.currentThread().getName());
                    return "verdict";
                },
                delivered::add
        );

        Runnable handOff = awaitHandOff();
        assertNotEquals("the work never ran on the calling thread", callingThread, workThreads.get(0));
        assertEquals(
                "a result is only delivered when the owner runs the hand-off",
                Collections.emptyList(),
                delivered
        );

        handOff.run();
        assertEquals(Collections.singletonList("verdict"), delivered);
    }

    @Test
    public void submissionReturnsWhileTheWorkIsStillRunning() throws Exception {
        CountDownLatch workStarted = new CountDownLatch(1);
        CountDownLatch releaseWork = new CountDownLatch(1);
        List<String> delivered = new ArrayList<>();

        queue.submit(
                () -> {
                    workStarted.countDown();
                    await(releaseWork);
                    return "verdict";
                },
                delivered::add
        );

        assertTrue("the request reached the worker", workStarted.await(5, TimeUnit.SECONDS));
        // The submit call already returned: only the worker is waiting for the release.
        assertEquals(Collections.emptyList(), handOffs);
        releaseWork.countDown();
        awaitHandOff().run();
        assertEquals(Collections.singletonList("verdict"), delivered);
    }

    @Test
    public void onlyTheNewestRequestIsDelivered() throws Exception {
        List<String> delivered = new ArrayList<>();

        queue.submit(() -> "first", delivered::add);
        queue.submit(() -> "second", delivered::add);

        Runnable firstHandOff = awaitHandOff();
        Runnable secondHandOff = awaitHandOff();
        firstHandOff.run();
        secondHandOff.run();

        assertEquals(
                "the verdict of a superseded request must never reach the owner",
                Collections.singletonList("second"),
                delivered
        );
    }

    @Test
    public void cancellingPendingRequestsDropsAResultThatWasAlreadyHandedBack() throws Exception {
        List<String> delivered = new ArrayList<>();

        queue.submit(() -> "verdict", delivered::add);
        Runnable handOff = awaitHandOff();

        queue.cancelPending();
        handOff.run();

        assertEquals(Collections.emptyList(), delivered);
    }

    @Test
    public void aClosedQueueStartsNoWorkAndDropsPendingResults() throws Exception {
        List<String> delivered = new ArrayList<>();

        queue.submit(() -> "verdict", delivered::add);
        Runnable handOff = awaitHandOff();
        queue.close();
        handOff.run();
        assertEquals(Collections.emptyList(), delivered);

        queue.submit(() -> "late", delivered::add);
        assertTrue("a closed queue never hands a result back", handOffs.isEmpty());
        assertEquals(Collections.emptyList(), delivered);
    }

    @Test
    public void anAsynchronousRequestDeliversItsLateAnswerThroughTheHandOff() throws Exception {
        List<String> delivered = new ArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<FolderWorkQueue.OwnerDelivery<String>> answer = new AtomicReference<>();

        queue.<String>submitAsync(
                delivery -> {
                    answer.set(delivery);
                    started.countDown();
                },
                delivered::add
        );

        await(started);
        answer.get().deliver("late verdict");
        awaitHandOff().run();

        assertEquals(Collections.singletonList("late verdict"), delivered);
    }

    @Test
    public void aWorkRequestDoesNotSupersedeAnAsynchronousAnswer() throws Exception {
        List<String> delivered = new ArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<FolderWorkQueue.OwnerDelivery<String>> answer = new AtomicReference<>();

        queue.<String>submitAsync(
                delivery -> {
                    answer.set(delivery);
                    started.countDown();
                },
                delivered::add
        );
        await(started);

        // A writeability check is queued while the privileged ignore-list read is still on its way,
        // exactly as a freshly opened folder editor behaves. The check belongs to the work lane, so
        // it must not drop the read's answer.
        answer.get().deliver("late verdict");
        Runnable handOff = awaitHandOff();
        queue.submit(() -> "verdict", delivered::add);
        handOff.run();

        assertEquals(
                "the answer of a read that no later read replaced reaches the owner",
                Collections.singletonList("late verdict"),
                delivered
        );
    }

    @Test
    public void anAsynchronousRequestReplacedByANewerAnswerDropsItsLateAnswer() throws Exception {
        List<String> delivered = new ArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicReference<FolderWorkQueue.OwnerDelivery<String>> answer = new AtomicReference<>();

        queue.<String>submitAsync(
                delivery -> {
                    answer.set(delivery);
                    firstStarted.countDown();
                },
                delivered::add
        );
        await(firstStarted);

        // The answer is produced while the request is current, and the hand-off it produces is only
        // run after a newer read replaced it, exactly as a slow privileged read behaves.
        answer.get().deliver("late verdict");
        Runnable handOff = awaitHandOff();
        queue.<String>submitAsync(delivery -> secondStarted.countDown(), delivered::add);
        await(secondStarted);
        handOff.run();

        assertEquals(
                "a result of a replaced read never reaches the owner",
                Collections.emptyList(),
                delivered
        );
    }

    @Test
    public void anAsynchronousRequestOfAClosedQueueDropsItsLateAnswer() throws Exception {
        List<String> delivered = new ArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<FolderWorkQueue.OwnerDelivery<String>> answer = new AtomicReference<>();

        queue.<String>submitAsync(
                delivery -> {
                    answer.set(delivery);
                    started.countDown();
                },
                delivered::add
        );
        await(started);
        answer.get().deliver("late verdict");
        Runnable handOff = awaitHandOff();

        queue.close();
        handOff.run();

        assertEquals(Collections.emptyList(), delivered);
    }

    @Test
    public void aClosedQueueNeverStartsAnAsynchronousRequest() {
        queue.close();
        List<String> delivered = new ArrayList<>();

        queue.<String>submitAsync(delivery -> delivery.deliver("ignored"), delivered::add);

        assertTrue("a closed queue starts no asynchronous work", handOffs.isEmpty());
        assertEquals(Collections.emptyList(), delivered);
    }
    /** Waits for one hand-off, so no assertion depends on a fixed delay. */
    private Runnable awaitHandOff() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            synchronized (handOffs) {
                if (!handOffs.isEmpty()) {
                    return handOffs.remove(0);
                }
            }
            Thread.sleep(2);
        }
        throw new AssertionError("The worker never handed a result back");
    }

    /** Waits for one latch the test controls; the work under test must not fail while it waits. */
    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
        }
    }
}
