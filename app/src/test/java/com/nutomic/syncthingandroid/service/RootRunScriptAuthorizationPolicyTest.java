package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Tests the durable consent boundary independently of Android and root transport. */
public class RootRunScriptAuthorizationPolicyTest {
    private static final class Store implements RootRunScriptAuthorizationPolicy.Store {
        String value = "OFF";
        boolean writeSucceeds = true;
        boolean wrongType;
        int writes;
        Runnable onArmCommit;

        @Override public String read() {
            if (wrongType) throw new ClassCastException("Stored consent is not a String");
            return value;
        }
        @Override public boolean commit(String next) {
            writes++;
            if (!writeSucceeds) return false;
            value = next;
            wrongType = false;
            if ("ARMED".equals(next) && onArmCommit != null) onArmCommit.run();
            return true;
        }
    }

    @Test public void defaultOffAndFailedWriteNeverAdmitAnAttempt() {
        Store store = new Store();
        RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(store);
        assertEquals(RootRunScriptAuthorizationPolicy.State.OFF, policy.status().state());
        assertNull(policy.begin(7));
        store.writeSucceeds = false;
        assertFalse(policy.arm());
        assertNull(policy.begin(7));
        assertEquals("OFF", store.value);
        assertFalse("a failed first opt-in is not consent", policy.status().optedIn());
    }

    @Test public void attemptIsDurableBeforeAcquisitionAndDenialNeedsResume() {
        Store store = new Store();
        RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(store);
        assertTrue(policy.arm());
        RootRunScriptAuthorizationPolicy.Attempt attempt = policy.begin(7);
        assertNotNull(attempt);
        assertEquals("ATTEMPT_IN_PROGRESS", store.value);
        assertNull(policy.begin(7));
        policy.complete(attempt, RootRunScriptAuthorizationPolicy.Result.DENIED);
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.DENIED,
                policy.status().reason());
        assertNull(policy.begin(7));
        assertTrue(policy.arm());
        assertNotNull(policy.begin(8));
    }

    @Test public void ordinaryScriptExitDoesNotSuspendFutureEvents() {
        Store store = new Store();
        RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(store);
        assertTrue(policy.arm());
        policy.complete(policy.begin(7), RootRunScriptAuthorizationPolicy.Result.SCRIPT_FAILED);
        assertEquals(RootRunScriptAuthorizationPolicy.State.ARMED, policy.status().state());
        assertNotNull(policy.begin(7));
    }

    @Test public void lateCompletionCannotRearmAfterDisableOrDestruction() {
        Store store = new Store();
        RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(store);
        assertTrue(policy.arm());
        RootRunScriptAuthorizationPolicy.Attempt attempt = policy.begin(7);
        policy.invalidate();
        policy.complete(attempt, RootRunScriptAuthorizationPolicy.Result.SUCCESS);
        assertEquals("ATTEMPT_IN_PROGRESS", store.value);
        assertTrue(policy.disable());
        assertEquals("OFF", store.value);
        assertNull(policy.begin(7));
    }

    @Test public void interruptedOrUnknownDurableValueRequiresExplicitResume() {
        Store store = new Store();
        store.value = "ATTEMPT_IN_PROGRESS";
        RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(store);
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.INTERRUPTED,
                policy.status().reason());
        assertNull(policy.begin(1));
        assertTrue(policy.arm());
        assertNotNull(policy.begin(2));

        store.value = "unexpected";
        RootRunScriptAuthorizationPolicy unknown = new RootRunScriptAuthorizationPolicy(store);
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.UNKNOWN, unknown.status().reason());
        assertNull(unknown.begin(3));
        assertTrue(unknown.arm());
        assertNotNull(unknown.begin(4));

        store.value = "SUSPENDED:NOT_A_REASON";
        RootRunScriptAuthorizationPolicy malformedSuspension =
                new RootRunScriptAuthorizationPolicy(store);
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.UNKNOWN,
                malformedSuspension.status().reason());
        assertNull(malformedSuspension.begin(5));
    }

    @Test public void wrongTypeDurableValueFailsClosedAndExplicitConsentRepairsIt() {
        Store store = new Store();
        store.wrongType = true;
        RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(store);
        assertEquals(RootRunScriptAuthorizationPolicy.State.SUSPENDED, policy.status().state());
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.UNKNOWN, policy.status().reason());
        assertNull(policy.begin(1));
        assertTrue(policy.arm());
        assertEquals("ARMED", store.value);
        assertNotNull(policy.begin(2));
    }

    @Test public void recreatedPolicyBlocksWhileOldServiceOptOutIsUnsettled() {
        Store store = new Store();
        RootRunScriptAuthorizationPolicy old = new RootRunScriptAuthorizationPolicy(store);
        assertTrue(old.arm());
        old.markOptOutPending();
        RootRunScriptAuthorizationPolicy recreated = new RootRunScriptAuthorizationPolicy(store);
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.PERSISTENCE,
                recreated.status().reason());
        assertNull(recreated.begin(9));
        assertFalse("Resume cannot overtake the accepted OFF write", recreated.arm());
        assertTrue(old.disable());
        assertEquals(RootRunScriptAuthorizationPolicy.State.OFF,
                recreated.status().state());
        assertNull(recreated.begin(10));
    }

    @Test public void failedOffWriteKeepsRecreatedPolicyClosedUntilExplicitAction() {
        Store store = new Store();
        RootRunScriptAuthorizationPolicy old = new RootRunScriptAuthorizationPolicy(store);
        assertTrue(old.arm());
        old.markOptOutPending();
        store.writeSucceeds = false;
        assertFalse(old.disable());
        RootRunScriptAuthorizationPolicy recreated = new RootRunScriptAuthorizationPolicy(store);
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.PERSISTENCE,
                recreated.status().reason());
        assertNull(recreated.begin(11));
        store.writeSucceeds = true;
        assertTrue(recreated.arm());
        assertNotNull(recreated.begin(12));
    }

    @Test public void settledDurableConsentRemainsArmedAcrossServiceRecreation() {
        Store store = new Store();
        RootRunScriptAuthorizationPolicy first = new RootRunScriptAuthorizationPolicy(store);
        assertTrue(first.arm());
        RootRunScriptAuthorizationPolicy recreated = new RootRunScriptAuthorizationPolicy(store);
        assertEquals(RootRunScriptAuthorizationPolicy.State.ARMED,
                recreated.status().state());
        assertNotNull(recreated.begin(9));
    }

    @Test public void failedAttemptCommitNeverStartsAuthorization() {
        Store store = new Store();
        RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(store);
        assertTrue(policy.arm());
        store.writeSucceeds = false;
        assertNull(policy.begin(1));
        assertEquals(RootRunScriptAuthorizationPolicy.Reason.PERSISTENCE,
                policy.status().reason());
        assertEquals("ARMED", store.value);
        assertTrue(policy.status().optedIn());
    }

    @Test public void modeEligibilityIsCheckedAtTheSerializedArmBoundary() {
        Store store = new Store();
        RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(store);
        assertFalse(policy.armIf(() -> false));
        assertEquals(0, store.writes);
        assertEquals(RootRunScriptAuthorizationPolicy.State.OFF, policy.status().state());
    }

    @Test public void destructionDuringArmCommitCannotRearmConsent() {
        Store store = new Store();
        RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(store);
        store.onArmCommit = policy::invalidate;
        assertFalse(policy.arm());
        assertNull(policy.begin(1));
        assertEquals(RootRunScriptAuthorizationPolicy.State.SUSPENDED, policy.status().state());
        assertEquals("SUSPENDED:INTERRUPTED", store.value);
    }

    @Test public void denialLossAndTimeoutRequireExplicitResume() {
        for (RootRunScriptAuthorizationPolicy.Result failure : new RootRunScriptAuthorizationPolicy.Result[]{
                RootRunScriptAuthorizationPolicy.Result.DENIED,
                RootRunScriptAuthorizationPolicy.Result.LOST,
                RootRunScriptAuthorizationPolicy.Result.TIMEOUT
        }) {
            Store store = new Store();
            RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(store);
            assertTrue(policy.arm());
            policy.complete(policy.begin(4), failure);
            assertEquals(RootRunScriptAuthorizationPolicy.State.SUSPENDED, policy.status().state());
            assertNull(policy.begin(4));
            assertTrue(policy.arm());
            assertNotNull(policy.begin(5));
        }
    }

    @Test public void timeoutSuspendsWhileOriginalAttemptStillOwnsAdmission() {
        Store store = new Store();
        RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(store);
        assertTrue(policy.arm());
        RootRunScriptAuthorizationPolicy.Attempt attempt = policy.begin(1);
        policy.suspendPending(attempt, RootRunScriptAuthorizationPolicy.Reason.TIMEOUT);
        assertEquals("SUSPENDED:TIMEOUT", store.value);
        assertNull(policy.begin(1));
        assertFalse("Resume cannot overlap a still active helper", policy.arm());
        policy.complete(attempt, RootRunScriptAuthorizationPolicy.Result.TIMEOUT);
        assertTrue(policy.arm());
    }

    @Test public void statusReadDoesNotWaitForSlowDurableConsentCommit() throws Exception {
        Store store = new Store();
        RootRunScriptAuthorizationPolicy policy = new RootRunScriptAuthorizationPolicy(store);
        CountDownLatch commitEntered = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        store.onArmCommit = () -> {
            commitEntered.countDown();
            try { releaseCommit.await(); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        };
        Thread writer = new Thread(policy::arm, "slow-consent-commit");
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            writer.start();
            assertTrue(commitEntered.await(5, TimeUnit.SECONDS));
            Future<RootRunScriptAuthorizationPolicy.Status> observed = reader.submit(policy::status);
            assertEquals(RootRunScriptAuthorizationPolicy.State.OFF,
                    observed.get(500, TimeUnit.MILLISECONDS).state());
        } finally {
            releaseCommit.countDown();
            writer.join(5_000);
            reader.shutdownNow();
        }
    }
}
