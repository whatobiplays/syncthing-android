package com.nutomic.syncthingandroid.runtime;

/**
 * Semantic, mode-neutral operations on the closed Syncthing Managed State manifest.
 *
 * <p>Managed State is the bounded set of files and directories that make up one Syncthing
 * installation: {@code config.xml}, {@code cert.pem}, {@code key.pem}, {@code https-cert.pem},
 * {@code https-key.pem} and the {@code index-v2} directory. A transfer implementation performs the
 * same operations whether Syncthing runs with application-UID privileges or as UID 0; only the
 * mechanics below the boundary differ, and callers never need to know which backend was
 * selected.</p>
 *
 * <p>Transfers are expressed in approved members and operation-owned staging directories, never in
 * caller-supplied paths. Every operation is fail-closed: a failed or partially completed transfer
 * never falls back to application-UID state access and never lets a caller continue with state it
 * could not verify.</p>
 */
public interface ManagedStateTransfer {

    /**
     * Copies the current Managed State into one fresh operation-owned staging directory.
     *
     * <p>Only members that currently exist are staged; a member that is absent from Managed State
     * is absent from the snapshot, which is what the established archive contract expects. A
     * present member that is a symbolic link, has the wrong kind, or cannot be read fails the whole
     * snapshot, and the failed operation removes only its own staging directory.</p>
     *
     * <p>The returned staging directory becomes application-readable before this method returns,
     * so the caller may add further application-owned content to it and read it back. The caller
     * owns the returned directory and closes it when the operation ends.</p>
     *
     * @throws ManagedStateException when Managed State cannot be read, a member is not a safe
     *     regular file or directory, or the staging handoff to the application failed
     */
    ManagedStateStaging snapshotForExport() throws ManagedStateException;

    /**
     * Installs previously staged imported state into Managed State.
     *
     * <p>Only approved members that are present in the given staging directory replace live state.
     * A staged member that is missing keeps the existing live member, matching the established
     * import behavior of extracting the archive over the current state. The index database is the
     * one exception: when the import did not stage {@code index-v2}, the live {@code index-v2} is
     * removed so stale local database state can never survive a legacy archive.</p>
     *
     * <p>The staging directory must be one this application created below the fixed staging base,
     * and its content must be readable by this process; anything else fails closed before live
     * state is touched.</p>
     *
     * @throws ManagedStateException when the staging directory is not an operation-owned transfer
     *     directory, a required member is missing or unsafe, or installation fails
     */
    void installImportedState(ManagedStateStaging staging) throws ManagedStateException;

    /**
     * Restores application-UID access to the approved Managed State members.
     *
     * <p>The repair is confined to the closed manifest: it never walks the application data
     * directory and never touches user synchronization folders. It is idempotent and retry-safe, so
     * a repair that only partly completed - for example because ownership was changed before a
     * context repair failed - can be completed by a later call, including one made through a new
     * backend instance. Success is reported only after the application's own access to the
     * repaired members has been verified.</p>
     *
     * @throws ManagedStateException when an approved member cannot be repaired, or when access to a
     *     repaired member still cannot be verified
     */
    void repairAppAccess() throws ManagedStateException;
}
