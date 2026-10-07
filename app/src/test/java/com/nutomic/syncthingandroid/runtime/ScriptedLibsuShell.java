package com.nutomic.syncthingandroid.runtime;

import com.topjohnwu.superuser.Shell;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
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
 * unprovable and that the relationship is recorded exactly once.</p>
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
    private final AtomicInteger statReads = new AtomicInteger();

    /**
     * Creates a verified root shell double.
     *
     * @param statLine    parent-process answer, or {@code null} for an empty answer
     * @param statFailure failure the parent-process probe reports instead of answering
     */
    ScriptedLibsuShell(String statLine, RuntimeException statFailure) {
        this.statLine = statLine;
        this.statFailure = statFailure;
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
        // The double owns no transport process, so closing it always succeeds.
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
