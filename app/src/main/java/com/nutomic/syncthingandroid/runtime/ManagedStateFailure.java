package com.nutomic.syncthingandroid.runtime;

/**
 * Typed causes of Managed State failures, independent of the selected execution backend.
 *
 * <p>These causes describe the state operation itself, so the same value is reported whether the
 * operation ran with application-UID privileges or through the superuser transport. Failures of
 * the superuser transport itself - a denied request, a missing root transport, an unverified
 * shell, or a dead helper session - keep their own {@link RootFailure} vocabulary and are never
 * collapsed into a state cause.</p>
 *
 * <p>Every cause is fail-closed: a caller must never retry a failed state operation with a
 * different backend, never treat a failed read as an absent file, and never continue a compound
 * operation after one of its steps failed.</p>
 */
public enum ManagedStateFailure {
    /** Existing Managed State could not be read or written. */
    STATE_ACCESS_FAILED,
    /** State could not be transferred between Managed State and an operation-owned staging area. */
    STATE_TRANSFER_FAILED,
    /** Ownership, permission, or context repair of Managed State did not restore application access. */
    STATE_REPAIR_FAILED
}
