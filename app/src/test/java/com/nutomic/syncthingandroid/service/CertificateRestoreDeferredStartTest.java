package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.runtime.SyncthingCommand;

import org.junit.Test;

/** Verifies command retention while certificate restoration blocks lifecycle startup. */
public class CertificateRestoreDeferredStartTest {

    @Test
    public void runConditionChangesAndServeRequestsPreserveAQueuedDeltaReset() {
        CertificateRestoreDeferredStart deferred = new CertificateRestoreDeferredStart();
        deferred.defer(SyncthingCommand.RESET_DELTAS);
        assertTrue("a queued reset must retain lifecycle admission", deferred.isPending());

        deferred.onRunConditionChanged(false);
        deferred.defer(SyncthingCommand.SERVE);
        deferred.onRunConditionChanged(true);

        assertTrue("Run Conditions cannot replace or release the queued reset", deferred.isPending());
        assertEquals("checking the continuation for admission must retain its ownership",
                SyncthingCommand.RESET_DELTAS, deferred.peek());
        assertTrue(deferred.isPending());
        assertEquals(SyncthingCommand.RESET_DELTAS, deferred.take());
        assertFalse("taking the continuation releases its local admission", deferred.isPending());
        assertNull(deferred.take());
    }

    @Test
    public void runConditionFalseCancelsOnlyAnAutomaticServeRequest() {
        CertificateRestoreDeferredStart deferred = new CertificateRestoreDeferredStart();
        deferred.defer(SyncthingCommand.SERVE);

        deferred.onRunConditionChanged(false);

        assertNull(deferred.take());
    }

    @Test
    public void explicitStopCancelsAQueuedDeltaReset() {
        CertificateRestoreDeferredStart deferred = new CertificateRestoreDeferredStart();
        deferred.defer(SyncthingCommand.RESET_DELTAS);

        assertEquals(SyncthingCommand.RESET_DELTAS, deferred.cancelForExplicitStop());
        assertNull(deferred.take());
    }

    @Test
    public void resetMayResumeWhenRunConditionsDoNotRequireServingButOnlyAfterSafeRelease() {
        assertTrue(CertificateRestoreDeferredStart.canResume(
                SyncthingCommand.RESET_DELTAS, true, false, true
        ));
        assertFalse(CertificateRestoreDeferredStart.canResume(
                SyncthingCommand.RESET_DELTAS, false, true, true
        ));
        assertFalse(CertificateRestoreDeferredStart.canResume(
                SyncthingCommand.RESET_DELTAS, true, true, false
        ));
        assertFalse(CertificateRestoreDeferredStart.canResume(
                SyncthingCommand.SERVE, true, false, true
        ));
    }
}
