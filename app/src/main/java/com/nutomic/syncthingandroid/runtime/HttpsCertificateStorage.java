package com.nutomic.syncthingandroid.runtime;

/**
 * Semantic, mode-neutral access to the optional user-supplied HTTPS certificate pair.
 *
 * <p>The pair is stored as two Managed State members, {@code https-cert.pem} and
 * {@code https-key.pem}. Callers use this capability for the certificate workflow in terms of a
 * prior state, a replacement, a reset, and a restore, and never in terms of files, so the same
 * workflow works while Syncthing runs with application-UID privileges and while it runs as UID
 * 0.</p>
 *
 * <p>Every operation is fail-closed: a capture that cannot read one of the files fails instead of
 * reporting the file as absent, and a failed mutation is reported so the caller can restore the
 * captured prior state.</p>
 */
public interface HttpsCertificateStorage {

    /**
     * Captures the exact current presence and content of the certificate pair.
     *
     * @throws ManagedStateException when the state cannot be read; a failure is never reported as
     *     an absent file
     */
    HttpsCertificateState snapshot() throws ManagedStateException;

    /**
     * Replaces the certificate pair with the given validated content.
     *
     * @throws ManagedStateException when either file cannot be written; the caller restores the
     *     captured prior state
     */
    void replace(byte[] certificatePem, byte[] keyPem) throws ManagedStateException;

    /**
     * Removes the certificate pair so Syncthing generates a fresh self-signed pair at the next
     * start.
     *
     * @throws ManagedStateException when a present file cannot be removed
     */
    void reset() throws ManagedStateException;

    /**
     * Restores the exact state captured by {@link #snapshot()}.
     *
     * @throws ManagedStateException when the captured state cannot be restored
     */
    void restore(HttpsCertificateState state) throws ManagedStateException;
}
