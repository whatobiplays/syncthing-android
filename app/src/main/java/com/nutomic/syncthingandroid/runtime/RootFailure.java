package com.nutomic.syncthingandroid.runtime;

/**
 * Typed causes of failures raised by the superuser (root) execution backend.
 *
 * <p>Every value names a distinct, actionable cause so callers can fail closed without inspecting
 * exception messages, and so no libsu type ever crosses the root transport boundary. Callers must
 * never fall back to application-UID execution when a root-capable operation fails.</p>
 */
public enum RootFailure {
    /**
     * The device has a root transport, but the root request was not granted: the transport
     * terminated before the shell became usable, or it reported a non-root shell.
     */
    ROOT_DENIED,
    /** No root transport exists on the device, so no root request could be delivered. */
    ROOT_UNAVAILABLE,
    /** Root activation did not complete inside the bounded activation window. */
    ROOT_ACTIVATION_TIMEOUT,
    /** A root shell existed but failed while carrying out an operation. */
    ROOT_TRANSPORT_FAILED,
    /** A shell was acquired, but an explicit probe did not confirm that it runs as UID 0. */
    UID_VERIFICATION_FAILED,
    /**
     * An activation completed after its request had been superseded. The late-acquired shell was
     * closed and no runtime or lifecycle state was touched.
     */
    ROOT_ACTIVATION_OBSOLETE,
    /**
     * Previously granted root authorization is no longer available while durable evidence shows
     * that a recorded root execution may still be alive. The recorded process is left untouched and
     * no replacement launch is allowed.
     */
    ROOT_AUTHORIZATION_LOST,
    /**
     * A launched process could not be proven to be the exact execution this backend started, and
     * the process had not already exited. The same cause covers a recorded execution that is still
     * present after its transport ended, because its exit can then no longer be observed without
     * polling for root authorization. The unverified process is left untouched.
     */
    EXECUTION_VERIFICATION_FAILED,
    /**
     * The requested operation belongs to a later implementation slice of the superuser runtime and
     * therefore fails closed instead of substituting application-UID behavior.
     */
    PRIVILEGED_STATE_NOT_IMPLEMENTED
}
