package com.nutomic.syncthingandroid.runtime;

/**
 * Supplies operation-scoped helper shells to root-capable inspection and signal transports.
 *
 * <p>Every call acquires a fresh, bounded root shell that the caller closes when the operation
 * finishes. Acquisition never happens while the provider is constructed, so passive inspection
 * paths stay free of root side effects.</p>
 */
@FunctionalInterface
interface RootShellProvider {
    RootShell acquireHelperShell() throws RootTransportException;
}
