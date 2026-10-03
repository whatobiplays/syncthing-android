package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;

import com.nutomic.syncthingandroid.runtime.ExecutionIdentity;

import org.junit.Test;

public class SyncthingRunnableLifecycleIdentityTest {
    @Test
    public void lifecycleLaunchWithoutIdentityIsPublishedAsUnavailable() {
        SyncthingRunnable.LifecycleOutcome outcome =
                SyncthingRunnable.LifecycleOutcome.started(null);

        assertEquals(
                SyncthingRunnable.LifecycleOutcome.Type.IDENTITY_UNAVAILABLE,
                outcome.type()
        );
    }

    @Test
    public void lifecycleLaunchWithExactIdentityCanBePublishedAsStarted() {
        ExecutionIdentity identity = new ExecutionIdentity(
                41, 9001, "boot-a", "/data/app/lib/libsyncthingnative.so", "run-a"
        );

        SyncthingRunnable.LifecycleOutcome outcome =
                SyncthingRunnable.LifecycleOutcome.started(identity);

        assertEquals(
                SyncthingRunnable.LifecycleOutcome.Type.EXECUTION_STARTED,
                outcome.type()
        );
    }
}
