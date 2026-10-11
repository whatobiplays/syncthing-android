package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.model.Folder;
import com.nutomic.syncthingandroid.runtime.ConfiguredFolderReference;
import com.nutomic.syncthingandroid.runtime.DefaultSyncthingRuntime;
import com.nutomic.syncthingandroid.runtime.FolderEvent;
import com.nutomic.syncthingandroid.runtime.OwnedExecutionShutdown;

import org.junit.Test;

import java.net.MalformedURLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the REST completion callback's real admission, broadcast, and shutdown ordering. */
public class RestApiRootRunScriptCompletionTest {
    private static final long WAIT_MILLIS = 5_000;

    @Test public void rootCompletionBroadcastsBeforeAuthorizationAndRejectsConcurrentPrompts()
            throws Exception {
        SyncthingServiceRootRunScriptTest.Fixture fixture =
                new SyncthingServiceRootRunScriptTest.Fixture();
        assertTrue(fixture.policy.arm());
        fixture.pauseBeforeAuthorization = true;
        AtomicInteger broadcasts = new AtomicInteger();
        RestApi api = probeApi(fixture, broadcasts);
        Folder folder = folder();

        Thread first = new Thread(() -> api.onFolderSyncCompleted(folder, "idle", "device-1"));
        first.start();
        first.join(WAIT_MILLIS);
        assertFalse("REST completion must not wait for root authorization", first.isAlive());
        assertEquals(1, broadcasts.get());
        assertTrue(fixture.beforeAuthorization.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));

        api.onFolderSyncCompleted(folder, "idle", "device-1");
        assertEquals(2, broadcasts.get());
        assertEquals(1, fixture.backendCalls.get());
        assertEquals(0, fixture.authorizationStarts.get());
        fixture.releaseAuthorization.countDown();
        fixture.backendRelease.countDown();
        assertTrue(api.closeFolderScriptAdmissionForModeTransition(WAIT_MILLIS));
        assertEquals(1, fixture.authorizationStarts.get());
    }

    @Test public void teardownSeesEventAdmittedBeforeServiceDispatch() throws Exception {
        PausingService service = new PausingService();
        SyncthingServiceRootRunScriptTest.Fixture fixture =
                new SyncthingServiceRootRunScriptTest.Fixture(service, true);
        assertTrue(fixture.policy.arm());
        AtomicInteger broadcasts = new AtomicInteger();
        RestApi api = probeApi(fixture, broadcasts);
        Thread completion = new Thread(() ->
                api.onFolderSyncCompleted(folder(), "idle", "device-1"));
        completion.start();
        assertTrue(service.dispatchEntered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));

        Thread shutdown = new Thread(api::prepareShutdown);
        shutdown.start();
        assertTrue(service.cancellationObserved.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertTrue("teardown still owns the admitted REST event", shutdown.isAlive());
        service.releaseDispatch.countDown();
        completion.join(WAIT_MILLIS);
        shutdown.join(WAIT_MILLIS);
        assertFalse(completion.isAlive());
        assertFalse(shutdown.isAlive());
        assertEquals(1, broadcasts.get());
        assertEquals(0, fixture.backendCalls.get());
        assertTrue(api.closeFolderScriptAdmissionForModeTransition(0));
    }

    @Test public void normalModeRunsScriptBeforeCompletionBroadcast() throws Exception {
        SyncthingServiceRootRunScriptTest.Fixture fixture =
                new SyncthingServiceRootRunScriptTest.Fixture(new SyncthingService(), false);
        AtomicInteger broadcasts = new AtomicInteger();
        RestApi api = new RestApi(fixture.service, fixture.runtime, seam()) {
            @Override public void sendBroadcastFolderSyncComplete(
                    String deviceId, Folder folder, String state) {
                assertEquals(1, fixture.appScriptCalls.get());
                broadcasts.incrementAndGet();
            }
        };
        api.onFolderSyncCompleted(folder(), "idle", "device-1");
        assertEquals(1, fixture.appScriptCalls.get());
        assertEquals(1, broadcasts.get());
    }

    private static RestApi probeApi(
            SyncthingServiceRootRunScriptTest.Fixture fixture, AtomicInteger broadcasts
    ) throws MalformedURLException {
        return new RestApi(fixture.service, fixture.runtime, seam()) {
            @Override public void sendBroadcastFolderSyncComplete(
                    String deviceId, Folder folder, String state) {
                broadcasts.incrementAndGet();
            }
        };
    }

    private static RestApi.CompletionTestSeam seam() {
        return new RestApi.CompletionTestSeam() {
            @Override public boolean runScriptEnabled(String folderId) { return true; }
            @Override public OwnedExecutionShutdown.RestShutdownRequest shutdownRequest() {
                return new OwnedExecutionShutdown.RestShutdownRequest() {
                    @Override public void setTerminalListener(Runnable listener) { }
                    @Override public boolean send() { return false; }
                    @Override public void cancel() { }
                    @Override public boolean awaitTerminal(long timeoutMillis) { return true; }
                };
            }
        };
    }

    private static Folder folder() {
        Folder folder = new Folder();
        folder.id = "folder-1";
        folder.path = "/configured/folder";
        return folder;
    }

    private static final class PausingService extends SyncthingService {
        final CountDownLatch dispatchEntered = new CountDownLatch(1);
        final CountDownLatch releaseDispatch = new CountDownLatch(1);
        final CountDownLatch cancellationObserved = new CountDownLatch(1);

        @Override public RootRunScriptDispatchDecision tryDispatchRootRunScript(
                DefaultSyncthingRuntime.FolderScriptReservation reservation,
                ConfiguredFolderReference folder, FolderEvent event, Runnable releaseAdmission) {
            dispatchEntered.countDown();
            try { releaseDispatch.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return super.tryDispatchRootRunScript(reservation, folder, event, releaseAdmission);
        }

        @Override public void cancelAdmittedRootRunScript() {
            super.cancelAdmittedRootRunScript();
            cancellationObserved.countDown();
        }
    }
}
