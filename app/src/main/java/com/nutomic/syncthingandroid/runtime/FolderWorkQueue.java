package com.nutomic.syncthingandroid.runtime;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs folder work away from the thread that draws the user interface and delivers only current
 * results back to it.
 *
 * <p>Folder work is filesystem work, and in superuser mode it also has to acquire a bounded helper
 * session. Both can take long enough to be visible if they ran on the user-interface thread, so
 * every request is handed to one worker and its result travels back through a caller-supplied
 * poster.</p>
 *
 * <p>The queue also owns the rule that decides which result the owner still wants. Every request
 * belongs to a generation, and only the newest generation's result is delivered, so a verdict that
 * arrives after the user already changed the input it was computed from is dropped instead of
 * being applied to state that has moved on.</p>
 *
 * <p>The class deliberately knows nothing about Android. The owner supplies the worker and the
 * poster, which keeps the policy of this class exercisable in plain JVM tests.</p>
 * <p>Results travel in two independent lanes. An ordinary request and an asynchronous request
 * never supersede each other, so a check that is queued while an ignore-list read is still on its
 * way cannot drop that read's answer, while a newer request in the same lane still replaces the
 * older one.</p>
 */
public final class FolderWorkQueue implements AutoCloseable {
    /** Work that runs on the worker thread and produces one result. */
    @FunctionalInterface
    public interface Work<T> {
        T run();
    }

    /**
     * Hands one value to the owner's own thread.
     *
     * <p>The shape is declared here instead of using {@code java.util.function}, because that
     * package is not available on the oldest Android version this application supports.</p>
     */
    @FunctionalInterface
    public interface OwnerDelivery<T> {
        /** Delivers one value on the owner's thread. */
        void deliver(T value);
    }
    /**
     * Hands one asynchronous request the channel its later answer must use.
     *
     * <p>Some folder work finishes on a thread the queue does not own: for example a privileged
     * operation that acquires a helper session first and answers through a callback afterwards. An
     * implementation of this interface starts that work and keeps the delivery it is given, using
     * it when the answer finally arrives.</p>
     */
    @FunctionalInterface
    public interface AsyncWork<T> {
        /**
         * Starts one request on the worker thread.
         *
         * @param answer delivery that applies the request rule to the answer that arrives later
         */
        void start(OwnerDelivery<T> answer);
    }

    private final Object lock = new Object();
    private final ExecutorService worker;
    private final OwnerDelivery<Runnable> postToOwner;
    private int newestRequest;
    private int newestAnswerRequest;
    private boolean closed;

    /**
     * @param worker       worker every request runs on
     * @param postToOwner  hand-off that runs one task on the owner's own thread
     */
    public FolderWorkQueue(ExecutorService worker, OwnerDelivery<Runnable> postToOwner) {
        this.worker = Objects.requireNonNull(worker, "The worker is required");
        this.postToOwner = Objects.requireNonNull(postToOwner, "The poster is required");
    }

    /**
     * Starts one request and delivers its result only while no newer request replaced it.
     *
     * <p>The call returns as soon as the request is queued; the work never runs on the calling
     * thread. A request that supersedes an unfinished one wins, and the older result is dropped
     * even when its work finishes later.</p>
     *
     * @param work     work that runs on the worker
     * @param onResult handler the result is handed to on the owner's thread
     */
    public <T> void submit(Work<T> work, OwnerDelivery<T> onResult) {
        Objects.requireNonNull(work, "The work is required");
        Objects.requireNonNull(onResult, "The result handler is required");
        // An ordinary request supersedes older ordinary requests only. An asynchronous answer that
        // is still on its way belongs to another lane and stays deliverable.
        int request = nextRequest();
        if (request == 0) {
            return;
        }
        execute(() -> {
            T result = work.run();
            postToOwner.deliver(() -> {
                if (!isCurrent(request)) {
                    return;
                }
                onResult.deliver(result);
            });
        });
    }
    /**
     * Starts one request whose answer arrives later through a channel of its own.
     *
     * <p>The work starts on the worker, but nothing forces it to finish there: it may hand the
     * request to a privileged helper session and answer when that session reports back. The answer
     * then travels through the delivery the work was given, which applies the same rule as
     * {@link #submit(Work, OwnerDelivery)}: the owner sees it only while this request is still the
     * newest one and the queue is still open.</p>
     *
     * @param work     work that starts the request on the worker
     * @param onResult handler the guarded delivery hands the result to, on the owner thread
     */
    public <T> void submitAsync(AsyncWork<T> work, OwnerDelivery<T> onResult) {
        Objects.requireNonNull(work, "The work is required");
        Objects.requireNonNull(onResult, "The result handler is required");
        // The answer lane is independent of the work lane, so an ordinary request that is queued
        // while this answer is still on its way never drops it. Only a newer asynchronous request,
        // a cancellation, or the queue closing supersedes this request.
        int request = nextAnswerRequest();
        if (request == 0) {
            return;
        }
        OwnerDelivery<T> answer = value -> postToOwner.deliver(() -> {
            if (!isCurrentAnswer(request)) {
                return;
            }
            onResult.deliver(value);
        });
        execute(() -> work.start(answer));
    }

    /**
     * Starts one request that reports through its own channel.
     *
     * <p>This form exists for work that hands its result to an asynchronous interface of its own,
     * such as a request whose answer arrives on another thread later.</p>
     *
     * @param work work that runs on the worker
     */
    public void submit(Runnable work) {
        Objects.requireNonNull(work, "The work is required");
        int request = nextRequest();
        if (request == 0) {
            return;
        }
        execute(work);
    }

    /** Drops every result that has not been delivered yet, without stopping the queue. */
    public void cancelPending() {
        synchronized (lock) {
            newestRequest++;
            newestAnswerRequest++;
        }
    }

    /**
     * Stops accepting requests and drops every result that has not been delivered yet.
     *
     * <p>Work that already started is interrupted, so a caller that is tearing its own state down
     * does not leave a privileged operation running on its behalf.</p>
     */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            newestRequest++;
            newestAnswerRequest++;
        }
        worker.shutdownNow();
    }

    /** Reserves the newest generation, or returns {@code 0} when the queue is already closed. */
    private int nextRequest() {
        synchronized (lock) {
            if (closed) {
                return 0;
            }
            return ++newestRequest;
        }
    }

    /**
     * Reserves the newest generation of the answer lane, or returns {@code 0} when the queue is
     * already closed.
     */
    private int nextAnswerRequest() {
        synchronized (lock) {
            if (closed) {
                return 0;
            }
            return ++newestAnswerRequest;
        }
    }

    /** Reports whether one request is still the newest one and the queue is still open. */
    private boolean isCurrent(int request) {
        synchronized (lock) {
            return !closed && request == newestRequest;
        }
    }

    /** Reports whether one answer-lane request is still newest while the queue is open. */
    private boolean isCurrentAnswer(int request) {
        synchronized (lock) {
            return !closed && request == newestAnswerRequest;
        }
    }

    /** Runs one task on the worker, ignoring a submission the shutdown already refused. */
    private void execute(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException workerStopped) {
            // The owner is going away; nothing may touch its state again.
        }
    }
}
