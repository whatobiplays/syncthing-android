package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/**
 * Deterministic tests for the sync-completion script admission gate.
 *
 * <p>The gate decides whether a dispatch may start at all and how long an orderly teardown waits
 * for the dispatch it admitted. Every test orders the interleaving with latches instead of relying
 * on thread timing.</p>
 */
public class FolderScriptAdmissionTest {

    @Test
    public void aDispatchThatWasAdmittedBeforeTheCloseIsWaitedFor() throws Exception {
        FolderScriptAdmission admission = new FolderScriptAdmission();
        assertTrue(admission.tryAdmit());
        AtomicBoolean waitedForTheDispatch = new AtomicBoolean();

        Thread teardown = new Thread(() -> {
            try {
                waitedForTheDispatch.set(admission.closeAndWait(5_000));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "folder-script-teardown");
        teardown.setDaemon(true);
        teardown.start();

        awaitClosed(admission);
        assertTrue(admission.isClosed());
        assertFalse("a closed gate admits nothing new while the dispatch is still running",
                admission.tryAdmit());

        admission.leave();
        teardown.join(5_000);
        assertFalse("the teardown finished once the dispatch left", teardown.isAlive());
        assertTrue("the admitted dispatch was waited for", waitedForTheDispatch.get());
        assertFalse("a closed gate stays closed", admission.tryAdmit());
    }

    @Test
    public void aDispatchThatStartsAfterTheCloseNeverRuns() throws Exception {
        FolderScriptAdmission admission = new FolderScriptAdmission();
        AtomicBoolean dispatchStarted = new AtomicBoolean();

        assertTrue(admission.closeAndWait(0));
        // This is the interleaving a teardown must prevent: the gate is already closed, so the
        // dispatch never starts and never has to be waited for.
        if (admission.tryAdmit()) {
            dispatchStarted.set(true);
        }

        assertFalse(dispatchStarted.get());
    }

    @Test
    public void anUnfinishedDispatchIsReportedAsATimeoutInsteadOfBlockingTheTeardown()
            throws Exception {
        FolderScriptAdmission admission = new FolderScriptAdmission();
        assertTrue(admission.tryAdmit());
        CountDownLatch teardownFinished = new CountDownLatch(1);
        AtomicBoolean waitedOut = new AtomicBoolean();
        long startedAt = System.nanoTime();

        Thread teardown = new Thread(() -> {
            try {
                waitedOut.set(!admission.closeAndWait(120));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                teardownFinished.countDown();
            }
        }, "folder-script-teardown");
        teardown.setDaemon(true);
        teardown.start();

        assertTrue("the teardown is bounded", teardownFinished.await(5, TimeUnit.SECONDS));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue("the teardown reported a timeout", waitedOut.get());
        assertTrue("the teardown did not wait out its bound", elapsedMillis >= 100);
        assertTrue(admission.isClosed());

        admission.leave();
    }

    @Test
    public void anUnbalancedLeaveDoesNotBlockTheNextDispatch() throws Exception {
        FolderScriptAdmission admission = new FolderScriptAdmission();

        assertTrue(admission.tryAdmit());
        admission.leave();
        admission.leave();

        long startedAt = System.nanoTime();
        assertTrue(admission.closeAndWait(5_000));
        assertTrue(
                "an unbalanced leave never makes the gate look busy",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 1_000
        );
        assertFalse(admission.tryAdmit());
    }

    @Test
    public void closingTwiceStaysClosed() throws Exception {
        FolderScriptAdmission admission = new FolderScriptAdmission();

        assertTrue(admission.closeAndWait(0));
        assertTrue(admission.closeAndWait(5_000));
        assertFalse(admission.tryAdmit());
    }

    /** Waits until the teardown closed the gate, so no test depends on thread timing. */
    private static void awaitClosed(FolderScriptAdmission admission) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (admission.isClosed()) {
                return;
            }
            Thread.sleep(2);
        }
        throw new AssertionError("The teardown never closed admission");
    }
}
