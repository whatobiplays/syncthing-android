package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.runtime.LifecycleLaunchPermit;

import org.junit.Test;

public class StartingShutdownDeferralTest {

    @Test
    public void runConditionTrueDuringStartingShutdownDeferralStartsOnceAfterExit() {
        StartingShutdownDeferral shutdown = new StartingShutdownDeferral();
        ShutdownStartIntent startIntent = new ShutdownStartIntent();
        LifecycleLaunchPermit startupPermit = new LifecycleLaunchPermit();
        int[] serveLaunches = {0};

        assertFalse(shutdown.blocksStartup(false));
        startIntent.onRunConditionChanged(false, false);
        assertEquals(LifecycleLaunchPermit.State.REVOKED, startupPermit.revoke());
        shutdown.defer();

        assertTrue(shutdown.blocksStartup(false));
        startIntent.onRunConditionChanged(true, shutdown.blocksStartup(false));
        assertFalse(startIntent.consumeIfRequired(true, false, true, true, false));

        shutdown.transferToShutdown();
        assertFalse(shutdown.isPending());
        assertTrue(shutdown.blocksStartup(true));
        if (startIntent.consumeIfRequired(true, true, true, true, false)) {
            serveLaunches[0]++;
        }
        if (startIntent.consumeIfRequired(true, true, true, true, false)) {
            serveLaunches[0]++;
        }

        assertEquals(1, serveLaunches[0]);
    }

    @Test
    public void falseRunConditionAgainDuringStartingDeferralCancelsDeferredStart() {
        StartingShutdownDeferral shutdown = new StartingShutdownDeferral();
        ShutdownStartIntent startIntent = new ShutdownStartIntent();

        shutdown.defer();
        startIntent.onRunConditionChanged(true, shutdown.blocksStartup(false));
        startIntent.onRunConditionChanged(false, shutdown.blocksStartup(false));
        shutdown.transferToShutdown();

        assertFalse(startIntent.consumeIfRequired(false, true, true, true, false));
    }

    @Test
    public void explicitStopDuringStartingDeferralSuppressesDeferredStart() {
        StartingShutdownDeferral shutdown = new StartingShutdownDeferral();
        ShutdownStartIntent startIntent = new ShutdownStartIntent();

        shutdown.defer();
        startIntent.onRunConditionChanged(true, shutdown.blocksStartup(false));
        startIntent.clear();
        shutdown.transferToShutdown();

        assertFalse(startIntent.consumeIfRequired(true, true, true, true, false));
    }
}
