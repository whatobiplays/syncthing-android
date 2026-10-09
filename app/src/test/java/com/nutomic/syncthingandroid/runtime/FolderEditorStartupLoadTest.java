package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Deterministic tests for the authoritative configuration read one folder editor opens with.
 *
 * <p>The read can wait for a bounded privileged helper activation on a device, so these tests hold
 * it on a latch and hand its answer to the owner explicitly: no assertion depends on a timing
 * guess, and a test can prove that nothing reached the editor while the read was still running.</p>
 */
public class FolderEditorStartupLoadTest {
    /** One loaded configuration, shaped like the editor's own snapshot. */
    private static final class Snapshot {
        private final List<String> folders;
        private final List<String> devices;

        private Snapshot(List<String> folders, List<String> devices) {
            this.folders = folders;
            this.devices = devices;
        }
    }

    /** Hand-offs the queue produced, in order, until the test runs them. */
    private final List<Runnable> handOffs = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> delivered = new ArrayList<>();
    private final List<String> deliveryThreads = new ArrayList<>();
    private final List<String> readThreads = Collections.synchronizedList(new ArrayList<>());
    private FolderWorkQueue queue;
    private FolderEditorStartupLoad<Snapshot> load;
    private String ownerThreadName;

    @Before
    public void setUp() {
        queue = new FolderWorkQueue(worker, handOffs::add);
        load = new FolderEditorStartupLoad<>(queue);
        ownerThreadName = Thread.currentThread().getName();
    }

    @After
    public void tearDown() {
        queue.close();
    }

    @Test
    public void aDelayedReadReachesTheOwnerOnlyAfterItAnswered() throws Exception {
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        load.start(
                () -> true,
                () -> {
                    readThreads.add(Thread.currentThread().getName());
                    readStarted.countDown();
                    await(releaseRead);
                    return new Snapshot(Arrays.asList("folder-a"), Arrays.asList("device-a"));
                },
                this::deliver
        );

        assertTrue("the read must run on the worker", readStarted.await(5, TimeUnit.SECONDS));
        runHandOffs();
        assertTrue(
                "nothing may reach the editor while the read is still running",
                delivered.isEmpty()
        );

        releaseRead.countDown();
        awaitHandOff();
        runHandOffs();

        assertEquals(Arrays.asList("folder-a"), delivered);
        assertNotEquals(
                "the read must not run on the thread that draws the editor",
                ownerThreadName,
                readThreads.get(0)
        );
        assertEquals(
                "the snapshot must reach the editor on the thread that asked for it",
                Arrays.asList(ownerThreadName),
                deliveryThreads
        );
    }

    @Test
    public void anAnswerForAnEditorThatIsGoneIsDropped() throws Exception {
        load.start(
                () -> false,
                () -> new Snapshot(Arrays.asList("folder-a"), Arrays.asList("device-a")),
                this::deliver
        );

        awaitHandOff();
        runHandOffs();

        assertTrue(
                "an answer whose editor is finishing or destroyed may not touch any view",
                delivered.isEmpty()
        );
    }

    @Test
    public void anAnswerThatANewerReadReplacedIsDropped() throws Exception {
        CountDownLatch firstReadStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRead = new CountDownLatch(1);
        CountDownLatch secondReadStarted = new CountDownLatch(1);
        CountDownLatch releaseSecondRead = new CountDownLatch(1);
        load.start(
                () -> true,
                () -> {
                    firstReadStarted.countDown();
                    await(releaseFirstRead);
                    return new Snapshot(Arrays.asList("stale-folder"), Arrays.asList("stale-device"));
                },
                this::deliver
        );
        assertTrue(firstReadStarted.await(5, TimeUnit.SECONDS));

        load.start(
                () -> true,
                () -> {
                    secondReadStarted.countDown();
                    await(releaseSecondRead);
                    return new Snapshot(Arrays.asList("current-folder"), Arrays.asList("current-device"));
                },
                this::deliver
        );

        releaseFirstRead.countDown();
        awaitHandOff();
        runHandOffs();
        assertTrue(
                "an answer a newer read replaced may not reach the editor",
                delivered.isEmpty()
        );

        assertTrue("the newer read must run", secondReadStarted.await(5, TimeUnit.SECONDS));
        releaseSecondRead.countDown();
        awaitHandOff();
        runHandOffs();

        assertEquals(
                "only the newest read may replace the editor contents",
                Arrays.asList("current-folder"),
                delivered
        );
    }
    /**
     * The device list of an open editor is read again after a device was added. That refresh carries
     * no folder of its own, it runs on the queue's worker so a configuration that has to wait for a
     * privileged activation cannot block the thread that draws the editor, and the answer the
     * editor sees is the newest device list only: a refresh that a newer one replaced never
     * reaches it.
     */
    @Test
    public void aDeviceRefreshRunsAwayFromTheOwnerAndOnlyTheNewestListReachesIt() throws Exception {
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        CountDownLatch newerRefreshStarted = new CountDownLatch(1);
        List<String> refreshedDevices = new ArrayList<>();
        List<String> refreshThreads = Collections.synchronizedList(new ArrayList<>());

        load.start(
                () -> true,
                () -> {
                    refreshThreads.add(Thread.currentThread().getName());
                    refreshStarted.countDown();
                    await(releaseRefresh);
                    return new Snapshot(Collections.emptyList(), Arrays.asList("device-a"));
                },
                snapshot -> {
                    deliveryThreads.add(Thread.currentThread().getName());
                    refreshedDevices.addAll(snapshot.devices);
                }
        );
        assertTrue("the refresh must run on the worker", refreshStarted.await(5, TimeUnit.SECONDS));

        // The user adds another device before the first refresh answered.
        load.start(
                () -> true,
                () -> {
                    newerRefreshStarted.countDown();
                    return new Snapshot(
                            Collections.emptyList(),
                            Arrays.asList("device-a", "device-b")
                    );
                },
                snapshot -> {
                    deliveryThreads.add(Thread.currentThread().getName());
                    refreshedDevices.addAll(snapshot.devices);
                }
        );
        releaseRefresh.countDown();
        assertTrue(
                "the newer refresh must run too",
                newerRefreshStarted.await(5, TimeUnit.SECONDS)
        );

        awaitHandOff();
        awaitHandOff();
        runHandOffs();

        assertEquals(
                "only the newest device list may reach the editor",
                Arrays.asList("device-a", "device-b"),
                refreshedDevices
        );
        assertNotEquals(
                "the refresh must not run on the thread that draws the editor",
                ownerThreadName,
                refreshThreads.get(0)
        );
        assertEquals(
                "the refresh must reach the editor on the thread that asked for it",
                Arrays.asList(ownerThreadName),
                deliveryThreads
        );
    }


    @Test
    public void aQueueThatClosedNeverDelivers() throws Exception {
        queue.close();

        load.start(
                () -> true,
                () -> new Snapshot(Arrays.asList("folder-a"), Arrays.asList("device-a")),
                this::deliver
        );

        assertTrue("a closed queue accepts no requests", handOffs.isEmpty());
        assertTrue("a closed queue delivers nothing", delivered.isEmpty());
    }

    @Test
    public void theEditorFindsTheFolderItWasOpenedFor() {
        assertEquals(
                "folder-b",
                FolderEditorStartupLoad.select(
                        Arrays.asList("folder-a", "folder-b"),
                        "b",
                        entry -> entry.substring(entry.length() - 1)
                )
        );
    }

    @Test
    public void anEditorWhoseFolderIsGoneFindsNothing() {
        assertNull(
                "a deleted folder may not be replaced by another one",
                FolderEditorStartupLoad.select(
                        Arrays.asList("folder-a"),
                        "b",
                        entry -> entry.substring(entry.length() - 1)
                )
        );
        assertNull(
                "an editor without an opened folder selects nothing",
                FolderEditorStartupLoad.select(
                        Arrays.asList("folder-a"),
                        null,
                        entry -> entry.substring(entry.length() - 1)
                )
        );
    }

    private void deliver(Snapshot snapshot) {
        deliveryThreads.add(Thread.currentThread().getName());
        delivered.add(snapshot.folders.get(0));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("the test released the read", latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("the read was interrupted", interrupted);
        }
    }

    private void awaitHandOff() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (handOffs.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertFalse("the answer must reach the owner", handOffs.isEmpty());
    }

    private void runHandOffs() {
        List<Runnable> pending;
        synchronized (handOffs) {
            pending = new ArrayList<>(handOffs);
            handOffs.clear();
        }
        for (Runnable handOff : pending) {
            handOff.run();
        }
    }
}
