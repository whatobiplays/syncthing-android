package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShutdownStartIntentTest {
    @Test
    public void runConditionTurningTrueDuringShutdownStartsOnceAfterRecovery() {
        ShutdownStartIntent intent = new ShutdownStartIntent();
        intent.onRunConditionChanged(false, false);
        intent.onRunConditionChanged(true, true);

        assertFalse(intent.consumeIfRequired(true, false, true, true, false));
        assertFalse(intent.consumeIfRequired(true, true, false, true, false));
        assertTrue(intent.consumeIfRequired(true, true, true, true, false));
        assertFalse(intent.consumeIfRequired(true, true, true, true, false));
    }

    @Test
    public void explicitStopClearsDeferredStartWhileRunConditionRemainsTrue() {
        ShutdownStartIntent intent = pendingIntent();

        intent.clear();

        assertFalse(intent.consumeIfRequired(true, true, true, true, false));
    }

    @Test
    public void crashedNativeStopClearsDeferredStartWhileRunConditionRemainsTrue() {
        ShutdownStartIntent intent = pendingIntent();

        intent.clear();

        assertFalse(intent.consumeIfRequired(true, true, true, true, false));
    }

    @Test
    public void laterFalseRunConditionCancelsDeferredStart() {
        ShutdownStartIntent intent = pendingIntent();
        intent.onRunConditionChanged(false, true);

        assertFalse(intent.consumeIfRequired(false, true, true, true, false));
    }

    @Test
    public void trueDecisionOutsideShutdownDoesNotCreateDeferredStart() {
        ShutdownStartIntent intent = new ShutdownStartIntent();
        intent.onRunConditionChanged(true, false);

        assertFalse(intent.consumeIfRequired(true, true, true, true, false));
    }

    @Test
    public void explicitContinuationThatAlreadyStartedServiceConsumesDeferredIntent() {
        ShutdownStartIntent intent = pendingIntent();

        assertFalse(intent.consumeIfRequired(true, true, true, false, false));
        assertFalse(intent.consumeIfRequired(true, true, true, true, false));
    }

    private static ShutdownStartIntent pendingIntent() {
        ShutdownStartIntent intent = new ShutdownStartIntent();
        intent.onRunConditionChanged(true, true);
        return intent;
    }
}
