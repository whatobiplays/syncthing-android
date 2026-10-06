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
}
