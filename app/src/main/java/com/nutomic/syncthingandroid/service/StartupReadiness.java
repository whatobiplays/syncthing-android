package com.nutomic.syncthingandroid.service;

import java.util.Objects;

/**
 * Tracks the startup facts required before the service may enter ACTIVE.
 *
 * <p>The gate is owned by the service thread. Its scheduler makes the full startup deadline
 * deterministic in JVM tests; the timeout includes process ownership, endpoint readiness, and
 * completed REST configuration initialization.</p>
 */
final class StartupReadiness {
    static final long DEADLINE_MS = 60_000;

    enum State { PENDING, ACTIVE, TIMED_OUT, CANCELLED }

    interface ScheduledTask {
        void cancel();
    }

    interface Scheduler {
        ScheduledTask schedule(long delayMillis, Runnable task);
    }

    private final Runnable onTimeout;
    private final ScheduledTask deadlineTask;
    private State state = State.PENDING;
    private boolean ownershipVerified;
    private boolean endpointReady;
    private boolean configurationInitialized;

    StartupReadiness(Scheduler scheduler, Runnable onTimeout) {
        this.onTimeout = Objects.requireNonNull(onTimeout);
        this.deadlineTask = Objects.requireNonNull(scheduler).schedule(
                DEADLINE_MS,
                this::timeout
        );
    }

    boolean markOwnershipVerified() {
        if (state != State.PENDING) return false;
        ownershipVerified = true;
        return activateIfReady();
    }

    boolean markEndpointReady() {
        if (state != State.PENDING) return false;
        endpointReady = true;
        return activateIfReady();
    }

    boolean markConfigurationInitialized() {
        if (state != State.PENDING) return false;
        configurationInitialized = true;
        return activateIfReady();
    }

    void cancel() {
        if (state == State.PENDING) state = State.CANCELLED;
        deadlineTask.cancel();
    }

    boolean isReady() {
        return state == State.ACTIVE;
    }

    State state() {
        return state;
    }

    private boolean activateIfReady() {
        if (!ownershipVerified || !endpointReady || !configurationInitialized) return false;
        state = State.ACTIVE;
        deadlineTask.cancel();
        return true;
    }

    private void timeout() {
        if (state != State.PENDING) return;
        state = State.TIMED_OUT;
        onTimeout.run();
    }
}
