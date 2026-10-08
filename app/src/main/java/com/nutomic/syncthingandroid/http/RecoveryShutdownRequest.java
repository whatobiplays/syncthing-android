package com.nutomic.syncthingandroid.http;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.RequestQueue.RequestEvent;
import com.android.volley.RequestQueue.RequestEventListener;
import com.android.volley.toolbox.StringRequest;
import com.nutomic.syncthingandroid.runtime.OwnedExecutionShutdown;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the prepared one-attempt Volley request used to stop an exactly identified execution. */
final class RecoveryShutdownRequest implements OwnedExecutionShutdown.RestShutdownRequest {
    private final WeakReference<RequestQueue> queue;
    private final CountDownLatch terminal = new CountDownLatch(1);
    private final AtomicBoolean listenerRemoved = new AtomicBoolean();
    private final Object requestTag = new Object();
    private final RequestEventListener eventListener;

    // Keep the request strongly reachable only between preparation and queue admission. Once
    // queued, the transport owns it and this handle retains only weak references.
    private Request<?> preparedRequest;
    private WeakReference<Request<?>> request = new WeakReference<>(null);
    private boolean sendAttempted;
    private Runnable terminalListener;
    private boolean terminalListenerDelivered;

    private RecoveryShutdownRequest(RequestQueue requestQueue, Request<?> request) {
        this.queue = new WeakReference<>(requestQueue);
        this.preparedRequest = request;
        request.setRetryPolicy(new DefaultRetryPolicy(5000, 0,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        request.setShouldCache(false);
        request.setTag(requestTag);

        eventListener = new TerminalListener(requestQueue, this, requestTag, listenerRemoved);
    }

    /** Builds a request without adding it to Volley, leaving admission free to register its lease. */
    static RecoveryShutdownRequest create(Context context, URL url, String apiKey) {
        ApiRequest requestFactory = new ApiRequest(
                context, url, PostRequest.URI_SYSTEM_SHUTDOWN, apiKey
        ) { };
        Uri uri = requestFactory.buildUri(Collections.emptyMap());
        StringRequest request = requestFactory.createStringRequest(
                Request.Method.POST, uri, null, null, null
        );
        return new RecoveryShutdownRequest(requestFactory.getVolleyQueue(), request);
    }

    @Override
    public synchronized boolean send() {
        if (sendAttempted) throw new IllegalStateException("Shutdown request already sent");
        sendAttempted = true;
        Request<?> pending = preparedRequest;
        RequestQueue target = queue.get();
        if (pending == null || target == null) {
            preparedRequest = null;
            terminal.countDown();
            return false;
        }

        try {
            target.addRequestEventListener(eventListener);
        } catch (RuntimeException notDeliverable) {
            try {
                target.removeRequestEventListener(eventListener);
            } catch (RuntimeException ignored) {
                // No request was added to the queue, so a stray observer cannot deliver it.
            }
            preparedRequest = null;
            terminal.countDown();
            return false;
        }
        request = new WeakReference<>(pending);
        preparedRequest = null;
        try {
            target.add(pending);
            return true;
        } catch (RuntimeException uncertainDelivery) {
            // Queue admission can fail after Volley has recorded the request. Keep the blocker
            // until cancellation or a terminal event proves that no delivery remains possible.
            pending.cancel();
            throw uncertainDelivery;
        }
    }

    @Override
    public void setTerminalListener(Runnable listener) {
        Runnable notify = null;
        synchronized (this) {
            terminalListener = listener;
            if (terminal.getCount() == 0 && !terminalListenerDelivered) {
                terminalListenerDelivered = true;
                notify = terminalListener;
                terminalListener = null;
            }
        }
        if (notify != null) notify.run();
    }

    private void markTerminal() {
        terminal.countDown();
        Runnable notify = null;
        synchronized (this) {
            if (terminalListener != null && !terminalListenerDelivered) {
                terminalListenerDelivered = true;
                notify = terminalListener;
                terminalListener = null;
            }
        }
        if (notify != null) notify.run();
    }

    @Override
    public void cancel() {
        Request<?> observedRequest = request.get();
        if (observedRequest == null) observedRequest = preparedRequest;
        if (observedRequest != null) observedRequest.cancel();
    }

    @Override
    public boolean awaitTerminal(long timeoutMillis) throws InterruptedException {
        return terminal.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** Observes terminal delivery without retaining the request queue or request itself. */
    private static final class TerminalListener implements RequestEventListener {
        private final WeakReference<RequestQueue> queue;
        private final WeakReference<RecoveryShutdownRequest> owner;
        private final Object requestTag;
        private final AtomicBoolean removed;

        private TerminalListener(
                RequestQueue queue,
                RecoveryShutdownRequest owner,
                Object requestTag,
                AtomicBoolean removed
        ) {
            this.queue = new WeakReference<>(queue);
            this.owner = new WeakReference<>(owner);
            this.requestTag = requestTag;
            this.removed = removed;
        }

        @Override
        public void onRequestEvent(Request<?> finishedRequest, int event) {
            if (finishedRequest.getTag() != requestTag || event != RequestEvent.REQUEST_FINISHED) {
                return;
            }
            try {
                RequestQueue observedQueue = queue.get();
                if (observedQueue != null && removed.compareAndSet(false, true)) {
                    // Volley iterates its live listener list during request-event dispatch.
                    // Defer removal until that dispatch frame has returned, otherwise removing this
                    // listener from REQUEST_FINISHED invalidates the iterator.
                    new Handler(Looper.getMainLooper()).post(
                            () -> observedQueue.removeRequestEventListener(this)
                    );
                }
            } finally {
                RecoveryShutdownRequest observedOwner = owner.get();
                if (observedOwner != null) observedOwner.markTerminal();
            }
        }
    }
}
