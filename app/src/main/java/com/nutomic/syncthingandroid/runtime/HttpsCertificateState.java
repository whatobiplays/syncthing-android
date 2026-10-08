package com.nutomic.syncthingandroid.runtime;

/**
 * The exact presence and content of the user-supplied HTTPS certificate pair at one point in time.
 *
 * <p>An HTTPS-certificate mutation captures this state before it changes anything, so a failed
 * verification can restore Managed State precisely: a file that existed is restored with its
 * original bytes, and a file that did not exist is removed again. Memory holds the captured
 * content, so the handle needs no cleanup and no privileged operation to discard.</p>
 *
 * <p>The handle is opaque to callers: they may ask whether each file was present, but only the
 * selected backend may read the captured content when it restores state.</p>
 */
public final class HttpsCertificateState {
    private final byte[] certificatePem;
    private final byte[] keyPem;

    /**
     * Captures one prior HTTPS certificate pair.
     *
     * @param certificatePem content of {@code https-cert.pem}, or {@code null} when it was absent
     * @param keyPem content of {@code https-key.pem}, or {@code null} when it was absent
     */
    HttpsCertificateState(byte[] certificatePem, byte[] keyPem) {
        this.certificatePem = certificatePem == null ? null : certificatePem.clone();
        this.keyPem = keyPem == null ? null : keyPem.clone();
    }

    /** Returns whether the HTTPS certificate file existed when this state was captured. */
    public boolean certificatePresent() {
        return certificatePem != null;
    }

    /** Returns whether the HTTPS key file existed when this state was captured. */
    public boolean keyPresent() {
        return keyPem != null;
    }

    /** Returns a copy of the captured certificate content, or {@code null} when it was absent. */
    byte[] certificateBytes() {
        return certificatePem == null ? null : certificatePem.clone();
    }

    /** Returns a copy of the captured key content, or {@code null} when it was absent. */
    byte[] keyBytes() {
        return keyPem == null ? null : keyPem.clone();
    }
}
