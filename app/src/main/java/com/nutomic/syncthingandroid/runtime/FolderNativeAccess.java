package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;

/**
 * Native filesystem mechanics the application-UID folder operations depend on.
 *
 * <p>The interface exists so the decision logic of the folder operations - which probe strategy
 * applies, which failures are conclusive, and what the caller is told - can be exercised without
 * a device. Only {@link AndroidFolderNativeAccess} talks to the platform.</p>
 */
interface FolderNativeAccess {
    /** Outcome of one attempt to create an unnamed temporary file inside a directory. */
    enum UnnamedTemporaryResult {
        /** The directory accepted an unnamed temporary file, which was closed immediately. */
        CREATED,
        /** The filesystem does not implement unnamed temporary files. */
        UNSUPPORTED,
        /** The caller may not write inside the directory, or the mount is read-only. */
        PERMISSION_DENIED,
        /** The attempt failed for a reason that proves nothing about writeability. */
        FAILED
    }

    /**
     * Attempts one unnamed temporary file in the given directory.
     *
     * <p>A successful attempt creates no name inside the directory and leaves no entry behind: the
     * descriptor is closed again immediately.</p>
     *
     * @param directory absolute directory path the probe runs in
     * @param flags     open flags to use for the attempt
     */
    UnnamedTemporaryResult createUnnamedTemporary(String directory, int flags);

    /**
     * Reports the access the application UID has to the given path, without changing it.
     *
     * <p>This is the non-mutating fallback when a filesystem does not support unnamed temporary
     * files. The answer is a verdict rather than a bare yes or no: a check the platform refuses to
     * answer proves nothing about the path and reports {@link FolderWriteability#UNKNOWN}, so an
     * undetermined result is never mistaken for proven read-only access.</p>
     *
     * @param path absolute path to inspect
     * @return the access verdict for the path
     */
    FolderWriteability inferWriteability(String path);

    /**
     * Reports whether one path names a symbolic link, without following it.
     *
     * <p>A path that cannot be inspected at all reports {@code false}: the caller then decides the
     * entry through its ordinary file checks, which fail closed on anything that is not readable.</p>
     */
    boolean isSymbolicLink(String path);
    /**
     * Applies the permission bits of an existing member to its replacement file.
     *
     * <p>Only called while the member exists. Ownership and the SELinux label need no copy in
     * Normal Mode, because the replacement is written by the application UID inside the same
     * directory as the member, which is what the member's own ownership and label already reflect.
     * A member that is not a regular file, or whose attributes cannot be read, fails the operation
     * instead of silently producing a replacement with different permissions.</p>
     *
     * @param memberPath      path of the member that is about to be replaced
     * @param replacementPath path of the replacement that takes its place
     */
    void preservePermissions(String memberPath, String replacementPath) throws IOException;

    /**
     * Replaces one path with another file that is already complete.
     *
     * <p>The replacement must be atomic for readers: either the previous file or the complete new
     * file is visible, never a mixture.</p>
     */
    void replaceAtomically(String temporaryPath, String targetPath) throws IOException;

    /** How one path resolves when it is inspected without following it. */
    enum MemberPresence {
        /** The path names an entry that exists, whatever its kind. */
        PRESENT,
        /** The path names no entry, while the folder that holds it could still be inspected. */
        ABSENT,
        /** The path could not be inspected, so its absence is not proven. */
        UNINSPECTABLE
    }

    /**
     * Reports how one path resolves, without following it.
     *
     * <p>The answer separates an entry that is absent from one that cannot be inspected, so a
     * failed inspection never looks like a missing entry. Only the given path is resolved, which
     * keeps the check usable where listing the folder would materialize every name inside it.</p>
     *
     * @param path absolute path to inspect
     * @return how the path resolves
     */
    MemberPresence inspectMember(String path);
}
