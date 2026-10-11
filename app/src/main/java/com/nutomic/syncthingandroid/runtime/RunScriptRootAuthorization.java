package com.nutomic.syncthingandroid.runtime;

/**
 * Narrow authorization decision carried by one semantic Run Script operation.
 *
 * <p>This does not expose a shell or grant arbitrary privileged commands. The root backend
 * checks it before helper acquisition and again before executing the approved script set.</p>
 */
public interface RunScriptRootAuthorization {
    RunScriptRootAuthorization DENY = () -> false;

    /** True only while this event still owns consent and the selected execution mode. */
    boolean isCurrent();

    /**
     * Atomically claims this event's single helper-acquisition boundary against lifecycle
     * cancellation. A false result forbids starting a new root authorization request.
     */
    default boolean tryBeginAuthorization() { return isCurrent(); }

    /**
     * Claims script execution before lifecycle cancellation may interrupt helper acquisition.
     * Once claimed, the service retains admission until the bounded script call returns.
     */
    default boolean tryBeginScriptExecution() { return isCurrent(); }

    /** Called at the authorization deadline while the acquisition worker may still be active. */
    default void onAuthorizationTimeout() {}
}
