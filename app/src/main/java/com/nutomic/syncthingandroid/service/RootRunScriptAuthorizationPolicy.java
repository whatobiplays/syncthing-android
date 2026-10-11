package com.nutomic.syncthingandroid.service;

import java.util.Objects;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Device-local consent for authorization requested by automatic Superuser Mode Run Scripts.
 *
 * <p>This is an authorization policy, not an execution lifecycle. The service owns it and calls
 * its persistence methods on workers. A durable in-progress value is written before any `su`
 * acquisition; process interruption therefore requires explicit Resume instead of silently
 * repeating a potentially non-idempotent script event.</p>
 */
public final class RootRunScriptAuthorizationPolicy {
    /** The sole durable preference access used by this policy. */
    public interface Store {
        String read();
        boolean commit(String value);
        /** Stable identity shared by service instances that access the same preference file. */
        default Object identity() { return this; }
    }

    /**
     * One process-local barrier for an OFF write that may outlive its service. It never grants
     * consent and is not a second persisted store; a new process still reads the preference.
     */
    private static final class OptOutBarrier {
        static final int PENDING = 0;
        static final int OFF_CONFIRMED = 1;
        static final int FAILED = 2;
        volatile int outcome = PENDING;
    }

    private static final Map<Object, OptOutBarrier> OPT_OUT_BARRIERS = new WeakHashMap<>();

    /** Service-owned eligibility check evaluated while the policy serializes consent changes. */
    @FunctionalInterface
    public interface Eligibility {
        boolean mayArm();
    }

    public enum State { OFF, ARMED, ATTEMPT_IN_PROGRESS, SUSPENDED }
    public enum Reason { NONE, DENIED, LOST, TIMEOUT, UNAVAILABLE, INTERRUPTED, UNKNOWN, PERSISTENCE }
    public enum Result { SUCCESS, SCRIPT_FAILED, DENIED, LOST, TIMEOUT, UNAVAILABLE, CANCELLED }

    /** Immutable status suitable for rendering without attempting root authorization. */
    public static final class Status {
        private final State state;
        private final Reason reason;
        private final boolean optedIn;

        private Status(State state, Reason reason) {
            this(state, reason, state != State.OFF && reason != Reason.UNKNOWN);
        }

        private Status(State state, Reason reason, boolean optedIn) {
            this.state = state;
            this.reason = reason;
            this.optedIn = optedIn;
        }

        public State state() { return state; }
        public Reason reason() { return reason; }
        public boolean optedIn() { return optedIn; }
    }

    /** Identity of one event's authorization attempt, bound to its execution-mode generation. */
    public static final class Attempt {
        private final long id;
        private final long epoch;
        private final long modeGeneration;

        private Attempt(long id, long epoch, long modeGeneration) {
            this.id = id;
            this.epoch = epoch;
            this.modeGeneration = modeGeneration;
        }

        public long id() { return id; }
        public long modeGeneration() { return modeGeneration; }
    }

    private final Store store;
    private final Object storeIdentity;
    private final Object lock = new Object();
    private final AtomicLong epoch = new AtomicLong();
    private long nextId;
    private volatile boolean revoked;
    private volatile Status status;
    private volatile Attempt active;
    private volatile OptOutBarrier observedOptOut;
    private OptOutBarrier ownedOptOut;

    public RootRunScriptAuthorizationPolicy(Store store) {
        this.store = Objects.requireNonNull(store);
        storeIdentity = Objects.requireNonNull(store.identity());
        String persisted;
        try {
            persisted = store.read();
        } catch (RuntimeException unreadable) {
            // SharedPreferences throws for a value stored under the wrong type. Treat every
            // unreadable value as unknown consent until an explicit action writes a valid String.
            status = new Status(State.SUSPENDED, Reason.UNKNOWN);
            observeProcessOptOut();
            return;
        }
        // ARMED is a settled consent decision. An interrupted attempt is different: its event
        // outcome is unknown, so a new service must wait for explicit Resume.
        if ("OFF".equals(persisted) || persisted == null) {
            status = new Status(State.OFF, Reason.NONE);
        } else if ("ARMED".equals(persisted)) {
            status = new Status(State.ARMED, Reason.NONE);
        } else if ("ATTEMPT_IN_PROGRESS".equals(persisted)) {
            status = new Status(State.SUSPENDED, Reason.INTERRUPTED);
        } else if (persisted.startsWith("SUSPENDED:")) {
            try {
                status = new Status(State.SUSPENDED,
                        Reason.valueOf(persisted.substring("SUSPENDED:".length())));
            } catch (IllegalArgumentException invalid) {
                status = new Status(State.SUSPENDED, Reason.UNKNOWN);
            }
        } else {
            status = new Status(State.SUSPENDED, Reason.UNKNOWN);
        }
        observeProcessOptOut();
    }

    private void observeProcessOptOut() {
        synchronized (OPT_OUT_BARRIERS) {
            observedOptOut = OPT_OUT_BARRIERS.get(storeIdentity);
        }
        OptOutBarrier barrier = observedOptOut;
        if (barrier != null) {
            revoked = true;
            status = barrier.outcome == OptOutBarrier.OFF_CONFIRMED
                    ? new Status(State.OFF, Reason.NONE)
                    : new Status(State.SUSPENDED, Reason.PERSISTENCE, true);
        }
    }

    public Status status() {
        // REST completion may inspect this before scheduling. A preference commit in progress
        // must never hold the completion event or its broadcast behind the policy monitor.
        OptOutBarrier barrier = observedOptOut;
        if (barrier != null) {
            return barrier.outcome == OptOutBarrier.OFF_CONFIRMED
                    ? new Status(State.OFF, Reason.NONE)
                    : new Status(State.SUSPENDED, Reason.PERSISTENCE, true);
        }
        return status;
    }

    /** Explicit opt-in or Resume. It never schedules an earlier event. */
    public boolean arm() {
        return armIf(() -> true);
    }

    /** Evaluates service-owned mode eligibility at the serialized consent boundary. */
    public boolean armIf(Eligibility eligible) {
        Objects.requireNonNull(eligible);
        synchronized (lock) {
            if (active != null) return false;
            OptOutBarrier barrier = observedOptOut;
            if (barrier != null && barrier.outcome == OptOutBarrier.PENDING) return false;
            long priorEpoch = epoch.get();
            if (!eligible.mayArm()) return false;
            if (!write("ARMED")) return false;
            revoked = false;
            if (!epoch.compareAndSet(priorEpoch, priorEpoch + 1)) {
                // Destruction or opt-out raced the durable write. An old worker cannot turn its
                // result into fresh consent, even if that write already reached storage.
                revoked = true;
                if (write("SUSPENDED:INTERRUPTED")) {
                    status = new Status(State.SUSPENDED, Reason.INTERRUPTED);
                }
                return false;
            }
            status = new Status(State.ARMED, Reason.NONE);
            if (barrier != null) {
                synchronized (OPT_OUT_BARRIERS) {
                    if (OPT_OUT_BARRIERS.get(storeIdentity) == barrier) {
                        OPT_OUT_BARRIERS.remove(storeIdentity);
                    }
                }
                observedOptOut = null;
            }
            return true;
        }
    }

    /** Prevents new prompts immediately, then durably clears device-local consent. */
    public boolean disable() {
        invalidate();
        OptOutBarrier barrier;
        synchronized (OPT_OUT_BARRIERS) {
            barrier = OPT_OUT_BARRIERS.get(storeIdentity);
            if (barrier != null && barrier.outcome == OptOutBarrier.PENDING
                    && barrier != ownedOptOut) return false;
            if (barrier == null || barrier.outcome != OptOutBarrier.PENDING) {
                barrier = new OptOutBarrier();
                OPT_OUT_BARRIERS.put(storeIdentity, barrier);
            }
            ownedOptOut = barrier;
            observedOptOut = barrier;
        }
        synchronized (lock) {
            boolean saved = write("OFF");
            if (saved) status = new Status(State.OFF, Reason.NONE);
            barrier.outcome = saved ? OptOutBarrier.OFF_CONFIRMED : OptOutBarrier.FAILED;
            return saved;
        }
    }

    /** Records that an explicit opt-out is pending durable confirmation on a worker. */
    public void markOptOutPending() {
        invalidate();
        synchronized (OPT_OUT_BARRIERS) {
            OptOutBarrier barrier = OPT_OUT_BARRIERS.get(storeIdentity);
            if (barrier == null || barrier.outcome != OptOutBarrier.PENDING) {
                barrier = new OptOutBarrier();
                OPT_OUT_BARRIERS.put(storeIdentity, barrier);
                ownedOptOut = barrier;
            }
            observedOptOut = barrier;
        }
        status = new Status(State.SUSPENDED, Reason.PERSISTENCE, status.optedIn());
    }

    /** Allows a later explicit retry when the accepted OFF worker could not be started. */
    public void markOptOutSchedulingFailed() {
        OptOutBarrier barrier = observedOptOut;
        if (barrier != null && barrier == ownedOptOut) {
            barrier.outcome = OptOutBarrier.FAILED;
        }
    }

    /**
     * Reserves one automatic attempt after both lifecycle admissions are held. A null result
     * means no helper may be acquired; callers report {@link #status()} as a typed outcome.
     */
    public Attempt begin(long modeGeneration) {
        synchronized (lock) {
            if (observedOptOut != null || revoked || active != null
                    || status.state != State.ARMED) return null;
            long priorEpoch = epoch.get();
            if (!write("ATTEMPT_IN_PROGRESS")) return null;
            if (revoked || priorEpoch != epoch.get()) {
                status = new Status(State.SUSPENDED, Reason.INTERRUPTED);
                return null;
            }
            Attempt attempt = new Attempt(++nextId, epoch.get(), modeGeneration);
            active = attempt;
            status = new Status(State.ATTEMPT_IN_PROGRESS, Reason.NONE);
            return attempt;
        }
    }

    /** Reports whether an acquired helper is still allowed to start script commands. */
    public boolean isCurrent(Attempt attempt, long modeGeneration) {
        return attempt != null && active == attempt && !revoked
                && attempt.epoch == epoch.get()
                && attempt.modeGeneration == modeGeneration;
    }

    /** Clears an in-memory cancellation only when no authorization attempt was recorded. */
    public boolean restoreUnattemptedConsent() {
        synchronized (lock) {
            if (observedOptOut != null || active != null
                    || status.state != State.ARMED) return false;
            revoked = false;
            epoch.incrementAndGet();
            return true;
        }
    }

    /** Records a deadline without releasing the still-active helper's lifecycle admission. */
    public void suspendPending(Attempt attempt, Reason reason) {
        synchronized (lock) {
            if (attempt == null || active != attempt || revoked
                    || attempt.epoch != epoch.get()) return;
            if (write("SUSPENDED:" + reason.name())) {
                status = new Status(State.SUSPENDED, reason);
                revoked = true;
                epoch.incrementAndGet();
            }
        }
    }

    /**
     * Records a terminal outcome. Only an ordinary script exit or complete success rearms the
     * next event. A stale result cannot overwrite opt-out, suspension, or a new service owner.
     */
    public void complete(Attempt attempt, Result result) {
        synchronized (lock) {
            if (attempt == null || active != attempt) return;
            active = null;
            if (revoked || attempt.epoch != epoch.get()) {
                if (status.state == State.ATTEMPT_IN_PROGRESS
                        || status.state == State.ARMED) {
                    status = new Status(State.SUSPENDED, Reason.INTERRUPTED);
                }
                return;
            }
            Reason reason;
            switch (result) {
                case SUCCESS:
                case SCRIPT_FAILED:
                    if (write("ARMED")) {
                        if (revoked || attempt.epoch != epoch.get()) {
                            write("ATTEMPT_IN_PROGRESS");
                            status = new Status(State.SUSPENDED, Reason.INTERRUPTED);
                        } else {
                            status = new Status(State.ARMED, Reason.NONE);
                        }
                    }
                    return;
                case DENIED: reason = Reason.DENIED; break;
                case LOST: reason = Reason.LOST; break;
                case TIMEOUT: reason = Reason.TIMEOUT; break;
                case UNAVAILABLE: reason = Reason.UNAVAILABLE; break;
                case CANCELLED: reason = Reason.INTERRUPTED; break;
                default: throw new IllegalArgumentException("Unknown authorization result");
            }
            if (write("SUSPENDED:" + reason.name())) {
                status = new Status(State.SUSPENDED, reason);
            }
        }
    }

    /** Revokes old operations without waiting for a worker or blocking the service thread. */
    public void invalidate() {
        revoked = true;
        epoch.incrementAndGet();
    }

    /** Applies an already committed import that removed this device-local consent key. */
    public void observeImportedReset() {
        invalidate();
        synchronized (lock) {
            if ("OFF".equals(store.read())) {
                status = new Status(State.OFF, Reason.NONE);
            }
        }
    }

    private boolean write(String value) {
        try {
            if (store.commit(value) && value.equals(store.read())) return true;
        } catch (RuntimeException ignored) {
            // A failed write can leave an in-memory value that is not durable.
        }
        status = new Status(State.SUSPENDED, Reason.PERSISTENCE, status.optedIn());
        revoked = true;
        epoch.incrementAndGet();
        return false;
    }
}
