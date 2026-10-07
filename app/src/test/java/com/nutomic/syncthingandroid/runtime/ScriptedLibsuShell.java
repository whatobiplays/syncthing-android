package com.nutomic.syncthingandroid.runtime;

import com.topjohnwu.superuser.Shell;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A libsu shell double that answers the two probes a shell verification performs.
 *
 * <p>The double replies to {@code id -u} with user id 0 and to the parent-process probe of
 * {@link LibsuRootShell} with a scripted {@code /proc/<pid>/stat} line, so the exit status provenance
 * rule can be exercised without a device. The parent-process answer can be scripted to fail, and
 * every parent-process read is counted, so a test can prove that an unreadable answer stays
 * unprovable and that the relationship is recorded exactly once. The parent-process probe can
 * also be held on a latch the test releases, and the shell's close can be scripted to fail, so a
 * stalled provenance probe and its teardown can be exercised deterministically.</p>
 */
final class ScriptedLibsuShell extends Shell {
    /** Application process identifier the scripted identity lines are written for. */
    static final int OWNER_PROCESS_ID = 4242;
    /** Stat line of a shell that is a direct child of {@link #OWNER_PROCESS_ID}. */
    static final String ATTACHED_STAT_LINE =
            "77 (sh) S 4242 77 77 0 -1 4194624 100 0 0 0 1 2 0 0 20 0 3 0 9001 12345";
    /** Stat line of a shell that outlived the client process that started it. */
    static final String DETACHED_STAT_LINE =
            "77 (sh) S 99 77 77 0 -1 4194624 100 0 0 0 1 2 0 0 20 0 3 0 9001 12345";

    private final String statLine;
    private final RuntimeException statFailure;
    private final CountDownLatch provenanceGate;
    private final boolean closeFails;
    private final AtomicInteger statReads = new AtomicInteger();
    private final AtomicInteger closeAttempts = new AtomicInteger();
    private final CountDownLatch provenanceProbeStarted = new CountDownLatch(1);

    /**
     * Creates a verified root shell double.
     *
     * @param statLine    parent-process answer, or {@code null} for an empty answer
     * @param statFailure failure the parent-process probe reports instead of answering
     */
    ScriptedLibsuShell(String statLine, RuntimeException statFailure) {
        this(statLine, statFailure, null, false);
    }

    /**
     * Creates a verified root shell double whose parent-process probe can be held and whose close
     * can fail.
     *
     * @param statLine       parent-process answer, or {@code null} for an empty answer
     * @param statFailure    failure the parent-process probe reports instead of answering
     * @param provenanceGate latch that holds the parent-process probe until the test releases it,
     *                       or {@code null} for an immediate answer
     * @param closeFails     whether closing the shell reports an I/O failure
     */
    ScriptedLibsuShell(
            String statLine,
            RuntimeException statFailure,
            CountDownLatch provenanceGate,
            boolean closeFails
    ) {
        this.statLine = statLine;
        this.statFailure = statFailure;
        this.provenanceGate = provenanceGate;
        this.closeFails = closeFails;
    }

    /** Blocks until the parent-process probe has started, and reports whether it did. */
    boolean awaitProvenanceProbeStarted() throws InterruptedException {
        return provenanceProbeStarted.await(5, TimeUnit.SECONDS);
    }

    /** Number of close calls this shell received. */
    int closeAttempts() {
        return closeAttempts.get();
    }

    /** Number of parent-process probes this shell answered or refused. */
    int statReads() {
        return statReads.get();
    }

    @Override
    public boolean isAlive() {
        return true;
    }

    @Override
    public int getStatus() {
        return Shell.ROOT_SHELL;
    }

    @Override
    public void execTask(Task task) {
        throw new AssertionError("Verification never runs raw terminal tasks");
    }

    @Override
    public void submitTask(Task task) {
        throw new AssertionError("Verification never submits raw tasks");
    }

    @Override
    public Job newJob() {
        return new ProbeJob();
    }

    @Override
    public boolean waitAndClose(long timeout, TimeUnit unit) {
        return true;
    }

    @Override
    public void close() throws IOException {
        closeAttempts.incrementAndGet();
        if (closeFails) {
            throw new IOException("libsu cannot close the shell");
        }
    }

    /** Runs one helper command synchronously and answers it from the scripted state. */
    private final class ProbeJob extends Job {
        private List<String> stdout;
        private String command;

        @Override
        public Job to(List<String> output) {
            stdout = output;
            return this;
        }

        @Override
        public Job to(List<String> output, List<String> error) {
            stdout = output;
            return this;
        }

        @Override
        public Job add(String... commands) {
            command = commands.length == 0 ? "" : commands[0];
            return this;
        }

        @Override
        public Job add(InputStream in) {
            throw new AssertionError("Helper probes never pipe a stream");
        }

        @Override
        public Result exec() {
            if (command != null && command.contains("/proc/")) {
                statReads.incrementAndGet();
                provenanceProbeStarted.countDown();
                awaitProvenanceGate();
                if (statFailure != null) {
                    throw statFailure;
                }
                if (statLine != null) {
                    stdout.add(statLine);
                }
                return successfulResult();
            }
            stdout.add("0");
            return successfulResult();
        }

        /** Holds the probe until the test releases the gate; a cancelled probe stays blocked. */
        private void awaitProvenanceGate() {
            if (provenanceGate == null) {
                return;
            }
            boolean released = false;
            while (!released) {
                try {
                    provenanceGate.await();
                    released = true;
                } catch (InterruptedException cancellation) {
                    // The operation deadline cancels the job thread, but a stalled transport
                    // cannot be interrupted, so the probe blocks until the test releases it.
                }
            }
        }

        @Override
        public void submit(Executor executor, ResultCallback callback) {
            throw new AssertionError("Helper probes run synchronously");
        }

        @Override
        public Future<Result> enqueue() {
            throw new AssertionError("Helper probes are never enqueued");
        }

        private Result successfulResult() {
            return new Result() {
                @Override
                public List<String> getOut() {
                    return Collections.emptyList();
                }

                @Override
                public List<String> getErr() {
                    return Collections.emptyList();
                }

                @Override
                public int getCode() {
                    return 0;
                }
            };
        }
    }
}
