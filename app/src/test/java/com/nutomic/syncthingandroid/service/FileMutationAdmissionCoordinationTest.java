package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/** Verifies stopped-state configuration writes cannot race a mode transition or lifecycle launch. */
public class FileMutationAdmissionCoordinationTest {

    @Test
    public void stoppedStateMutationOwnsAdmissionAgainstTransitionAndLaunch() {
        FileMutationBarrier.StoppedStateMutationReservation mutation =
                FileMutationBarrier.tryReserveStoppedStateMutation();
        assertNotNull(mutation);
        AtomicBoolean launched = new AtomicBoolean();

        try {
            assertNull(FileMutationBarrier.tryReserveExecutionModeTransition());
            assertFalse(FileMutationBarrier.commitLifecycleLaunchIfAvailable(
                    () -> launched.set(true)
            ));
            assertFalse(launched.get());
        } finally {
            assertTrue(mutation.release());
        }

        FileMutationBarrier.ExecutionModeTransitionReservation transition =
                FileMutationBarrier.tryReserveExecutionModeTransition();
        assertNotNull(transition);
        assertTrue(transition.release());
    }

    @Test
    public void transitionReservationRejectsNewStoppedStateMutationAndLaunch() {
        FileMutationBarrier.ExecutionModeTransitionReservation transition =
                FileMutationBarrier.tryReserveExecutionModeTransition();
        assertNotNull(transition);
        AtomicBoolean launched = new AtomicBoolean();

        try {
            assertNull(FileMutationBarrier.tryReserveStoppedStateMutation());
            assertFalse(FileMutationBarrier.commitLifecycleLaunchIfAvailable(
                    () -> launched.set(true)
            ));
            assertFalse(launched.get());
        } finally {
            assertTrue(transition.release());
        }

        FileMutationBarrier.StoppedStateMutationReservation mutation =
                FileMutationBarrier.tryReserveStoppedStateMutation();
        assertNotNull(mutation);
        assertTrue(mutation.release());
    }
}
