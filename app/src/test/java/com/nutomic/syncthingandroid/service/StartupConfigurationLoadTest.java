package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.runtime.RootFailure;
import com.nutomic.syncthingandroid.runtime.RootTransportException;

import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/** Verifies slow or failed configuration work completes off the lifecycle caller thread. */
public class StartupConfigurationLoadTest {

    @Test
    public void slowConfigurationReadDoesNotBlockCallerAndReturnsImmutableValues() throws Exception {
        CountDownLatch sourceStarted = new CountDownLatch(1);
        CountDownLatch releaseSource = new CountDownLatch(1);
        CountDownLatch callbackCompleted = new CountDownLatch(1);
        AtomicReference<Thread> sourceThread = new AtomicReference<>();
        AtomicReference<StartupConfigurationLoad.Result> result = new AtomicReference<>();
        Thread caller = Thread.currentThread();

        StartupConfigurationLoad.start(() -> {
            sourceThread.set(Thread.currentThread());
            sourceStarted.countDown();
            if (!releaseSource.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test configuration read was not released");
            }
            return new StartupConfiguration(
                    new URL("https://127.0.0.1:8384/"), "api-key", 8384
            );
        }, completed -> {
            result.set(completed);
            callbackCompleted.countDown();
        });

        assertTrue(sourceStarted.await(1, TimeUnit.SECONDS));
        assertNotSame(caller, sourceThread.get());
        releaseSource.countDown();
        assertTrue(callbackCompleted.await(1, TimeUnit.SECONDS));
        assertTrue(result.get().succeeded());
        assertEquals("api-key", result.get().configuration().apiKey());
    }

    @Test
    public void configurationReadPreservesTypedRootFailure() throws Exception {
        CountDownLatch callbackCompleted = new CountDownLatch(1);
        AtomicReference<StartupConfigurationLoad.Result> result = new AtomicReference<>();

        StartupConfigurationLoad.start(() -> {
            throw new IllegalStateException(
                    "configuration read failed",
                    new RootTransportException(RootFailure.ROOT_DENIED, "denied")
            );
        }, completed -> {
            result.set(completed);
            callbackCompleted.countDown();
        });

        assertTrue(callbackCompleted.await(1, TimeUnit.SECONDS));
        assertEquals(RootFailure.ROOT_DENIED, result.get().rootFailure());
        assertEquals("configuration read failed", result.get().detail());
    }
}
