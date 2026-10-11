package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/** Verifies backend operations drain before a selected backend can change. */
public class RuntimeOperationAdmissionTest {

    @Test
    public void closeWaitsForAdmittedOperationsAndRejectsNewOnes() throws Exception {
        RuntimeOperationAdmission admission = new RuntimeOperationAdmission();
        RuntimeOperationAdmission.Permit active = admission.tryEnter();

        assertNotNull(active);
        assertFalse(admission.closeAndWait(0));
        assertNull(admission.tryEnter());
        assertFalse(admission.isClosedAndDrained());

        active.close();
        active.close();
        assertTrue(admission.isClosedAndDrained());
        assertNull(admission.tryEnter());
        admission.open();
        RuntimeOperationAdmission.Permit reopened = admission.tryEnter();
        assertNotNull(reopened);
        reopened.close();
    }

    @Test
    public void failedPreCommitCloseReopensAfterTimedOutWorkLeaves() throws Exception {
        RuntimeOperationAdmission admission = new RuntimeOperationAdmission();
        RuntimeOperationAdmission.Permit active = admission.tryEnter();

        assertFalse(admission.closeAndWait(0));
        admission.reopenWhenDrained();
        assertNull(admission.tryEnter());
        active.close();

        RuntimeOperationAdmission.Permit reopened = admission.tryEnter();
        assertNotNull(reopened);
        reopened.close();
    }

    @Test
    public void openingAlreadyOpenAdmissionDoesNotRejectOrdinaryActiveWork() {
        RuntimeOperationAdmission admission = new RuntimeOperationAdmission();
        RuntimeOperationAdmission.Permit active = admission.tryEnter();

        admission.open();

        assertNotNull(active);
        assertFalse(admission.isClosed());
        RuntimeOperationAdmission.Permit second = admission.tryEnter();
        assertNotNull(second);
        second.close();
        active.close();
    }

    @Test
    public void openingTransitionClosedAdmissionStillRequiresDrain() throws Exception {
        RuntimeOperationAdmission admission = new RuntimeOperationAdmission();
        RuntimeOperationAdmission.Permit active = admission.tryEnter();

        assertFalse(admission.closeAndWait(0));
        try {
            admission.open();
            throw new AssertionError("A transition-owned gate must not reopen before drain");
        } catch (IllegalStateException expected) {
            assertTrue(admission.isClosed());
        }
        assertNull(admission.tryEnter());

        active.close();
        assertTrue(admission.isClosedAndDrained());
        admission.open();
        assertFalse(admission.isClosed());
    }

    @Test
    public void closedAdmissionRunsDrainContinuationAfterTheLastOrdinaryPermitLeaves()
            throws Exception {
        RuntimeOperationAdmission admission = new RuntimeOperationAdmission();
        RuntimeOperationAdmission.Permit active = admission.tryEnter();
        AtomicBoolean continued = new AtomicBoolean();

        assertFalse(admission.closeAndWait(0));
        admission.whenClosedAndDrained(() -> continued.set(true));
        assertFalse(continued.get());
        assertNull(admission.tryEnter());

        active.close();

        assertTrue(continued.get());
        assertTrue(admission.isClosedAndDrained());
    }
}
