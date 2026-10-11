package com.nutomic.syncthingandroid.runtime;

/**
 * Acquires one explicit root shell in a bounded activation attempt.
 *
 * <p>Implementations must never accept a non-root fallback, must not block longer than the
 * requested timeout, and on timeout or cancellation must destroy any root process they
 * created.</p>
 */
interface RootShellFactory {
    /**
     * @param timeoutMillis upper bound for the whole activation attempt
     * @throws RootTransportException with a typed {@link RootFailure} describing the cause
     */
    RootShell acquire(long timeoutMillis) throws RootTransportException;

    /** Reports authorization already confirmed in this process without requesting a shell. */
    default boolean isAuthorizationAvailableWithoutPrompt() {
        return false;
    }

    /**
     * Acquires a shell only when root authorization was already confirmed in this process.
     *
     * <p>The default refuses the request. Implementations must never initiate a new authorization
     * prompt from this entry point.</p>
     */
    default RootShell acquireIfAuthorized(long timeoutMillis) throws RootTransportException {
        return null;
    }
}
