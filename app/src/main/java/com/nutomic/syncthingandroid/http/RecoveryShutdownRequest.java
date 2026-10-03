package com.nutomic.syncthingandroid.http;

import android.content.Context;
import android.net.Uri;

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

/** Owns the one-attempt Volley request used to shut down an exactly identified execution. */
final class RecoveryShutdownRequest implements OwnedExecutionShutdown.RestShutdownRequest {
    private final WeakReference<Request<?>> request;
    private final CountDownLatch terminal;

    private RecoveryShutdownRequest(Request<?> request, CountDownLatch terminal) {
        this.request = new WeakReference<>(request);
        this.terminal = terminal;
    }

    /** Builds and enqueues the dedicated one-attempt shutdown request. */
    static RecoveryShutdownRequest create(Context context, URL url, String apiKey) {
        ApiRequest requestFactory = new ApiRequest(
                context, url, PostRequest.URI_SYSTEM_SHUTDOWN, apiKey
        ) { };
        Uri uri = requestFactory.buildUri(Collections.emptyMap());
        StringRequest request = requestFactory.createStringRequest(
                Request.Method.POST, uri, null, null, null
        );
        return enqueue(requestFactory.getVolleyQueue(), request);
    }

    /** Enqueues a non-retrying request and returns a lightweight cancel/drain handle. */
    static RecoveryShutdownRequest enqueue(RequestQueue queue, Request<?> request) {
        request.setRetryPolicy(new DefaultRetryPolicy(5000, 0,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        request.setShouldCache(false);

        CountDownLatch terminal = new CountDownLatch(1);
        Object requestTag = new Object();
        request.setTag(requestTag);
        AtomicBoolean listenerRemoved = new AtomicBoolean();
        WeakReference<RequestQueue> queueReference = new WeakReference<>(queue);
        RequestEventListener listener = new RequestEventListener() {
            @Override
            public void onRequestEvent(Request<?> finishedRequest, int event) {
                if (finishedRequest.getTag() == requestTag && event == RequestEvent.REQUEST_FINISHED) {
                    try {
                        RequestQueue observedQueue = queueReference.get();
                        if (observedQueue != null && listenerRemoved.compareAndSet(false, true)) {
                            observedQueue.removeRequestEventListener(this);
                        }
                    } finally {
                        terminal.countDown();
                    }
                }
            }
        };

        queue.addRequestEventListener(listener);
        try {
            queue.add(request);
        } catch (RuntimeException e) {
            try {
                if (listenerRemoved.compareAndSet(false, true)) {
                    queue.removeRequestEventListener(listener);
                }
            } finally {
                terminal.countDown();
            }
            throw e;
        }
        return new RecoveryShutdownRequest(request, terminal);
    }

    @Override
    public void cancel() {
        Request<?> observedRequest = request.get();
        if (observedRequest != null) observedRequest.cancel();
    }

    @Override
    public boolean awaitTerminal(long timeoutMillis) throws InterruptedException {
        return terminal.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
