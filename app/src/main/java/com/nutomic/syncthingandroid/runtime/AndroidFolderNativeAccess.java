package com.nutomic.syncthingandroid.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * {@link FolderNativeAccess} implementation that uses the Android platform directly.
 *
 * <p>Every method here runs on a device. The class is deliberately small: it only translates
 * platform calls and their error numbers, so all policy stays in {@link AppUidFolderOperations},
 * where it can be tested without a device.</p>
 */
final class AndroidFolderNativeAccess implements FolderNativeAccess {
    /** Permission bits of one file mode; the file type bits are not part of a mode change. */
    private static final int PERMISSION_MASK = 07777;
    @Override
    public UnnamedTemporaryResult createUnnamedTemporary(String directory, int flags) {
        FileDescriptor descriptor = null;
        try {
            // An unnamed temporary file is a create, so the kernel reads the mode argument even
            // though the probe only needs the open to succeed or fail.
            descriptor = Os.open(directory, flags, 0600);
            return UnnamedTemporaryResult.CREATED;
        } catch (ErrnoException failure) {
            return classifyUnnamedTemporaryFailure(failure.errno);
        } catch (RuntimeException unsupported) {
            return UnnamedTemporaryResult.FAILED;
        } finally {
            if (descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (ErrnoException | RuntimeException ignored) {
                    // The probe result is already decided; a failed close changes nothing.
                }
            }
        }
    }

    @Override
    public FolderWriteability inferWriteability(String path) {
        try {
            return Os.access(path, OsConstants.W_OK | OsConstants.X_OK)
                    ? FolderWriteability.WRITABLE
                    : FolderWriteability.READ_ONLY;
        } catch (ErrnoException failure) {
            // A denied check and a write attempted on a read-only file system both prove that the
            // path cannot be written, so they are reported exactly like a check that answered "no".
            // Any other failure proved nothing, and reporting it as writeable or read-only would
            // hand the caller a verdict the kernel never made.
            return accessDenialProvesNoWriteAccess(failure.errno)
                    ? FolderWriteability.READ_ONLY
                    : FolderWriteability.UNKNOWN;
        } catch (RuntimeException unsupported) {
            return FolderWriteability.UNKNOWN;
        }
    }

    @Override
    public boolean isSymbolicLink(String path) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(path).st_mode);
        } catch (ErrnoException | RuntimeException unreadable) {
            // An entry that cannot be inspected is not reported as a link. The caller then decides
            // the entry through its ordinary file checks, which fail closed on anything unreadable.
            return false;
        }
    }

    @Override
    public void preservePermissions(String memberPath, String replacementPath) throws IOException {
        try {
            StructStat member = Os.lstat(memberPath);
            if (!OsConstants.S_ISREG(member.st_mode)) {
                throw new IOException(
                        "The member that is replaced is not a regular file: " + memberPath
                );
            }
            Os.chmod(replacementPath, member.st_mode & PERMISSION_MASK);
        } catch (ErrnoException failure) {
            throw new IOException(
                    "The permissions of the replaced member could not be preserved", failure
            );
        }
    }
    @Override
    public void replaceAtomically(String temporaryPath, String targetPath) throws IOException {
        try {
            Os.rename(temporaryPath, targetPath);
        } catch (ErrnoException failure) {
            throw new IOException("Could not replace " + targetPath, failure);
        }
    }

    @Override
    public MemberPresence inspectMember(String path) {
        try {
            Os.lstat(path);
            return MemberPresence.PRESENT;
        } catch (ErrnoException failure) {
            if (failure.errno == OsConstants.ENOENT || failure.errno == OsConstants.ENOTDIR) {
                return MemberPresence.ABSENT;
            }
            return MemberPresence.UNINSPECTABLE;
        } catch (RuntimeException unsupported) {
            return MemberPresence.UNINSPECTABLE;
        }
    }

    /**
     * Returns whether the error number of a failed access or create proves that the caller may not
     * write to the inspected path.
     *
     * <p>The kernel answers a denied access check with {@code EACCES} or {@code EPERM} and answers
     * a write attempted on a read-only file system with {@code EROFS}. Each of those is a definite
     * "no" to the writeability question, which is a different answer from a check that failed for a
     * reason saying nothing about write access, such as a path that vanished or a platform that
     * does not implement the call.</p>
     */
    static boolean accessDenialProvesNoWriteAccess(int errno) {
        return errno == OsConstants.EACCES
                || errno == OsConstants.EPERM
                || errno == OsConstants.EROFS;
    }

    /**
     * Translates one failed unnamed temporary file probe into the verdict its caller acts on.
     *
     * <p>A denial proves the folder cannot be written, so it is reported as a permission failure. An
     * error that means the file system or the kernel does not implement the probe is reported as
     * unsupported, which lets the caller fall back to its non-mutating access inference instead of
     * leaving the folder undetermined. Besides {@code EOPNOTSUPP}, that is {@code EINVAL} for a
     * kernel that does not know the flag, {@code EISDIR} for a file system that ignores it and opens
     * the directory itself, {@code ENOSYS} for a kernel that does not provide the operation and
     * {@code ENOTTY} for a file system that rejects the flag as inapplicable to it. Every other
     * error proved nothing and is reported as a plain failure, which the caller reports as
     * undetermined rather than as read-only access.</p>
     *
     * @param errno error number one failed probe reported
     * @return verdict for that error
     */
    static UnnamedTemporaryResult classifyUnnamedTemporaryFailure(int errno) {
        if (accessDenialProvesNoWriteAccess(errno)) {
            return UnnamedTemporaryResult.PERMISSION_DENIED;
        }
        if (errno == OsConstants.EOPNOTSUPP
                || errno == OsConstants.EINVAL
                || errno == OsConstants.EISDIR
                || errno == OsConstants.ENOSYS
                || errno == OsConstants.ENOTTY) {
            return UnnamedTemporaryResult.UNSUPPORTED;
        }
        return UnnamedTemporaryResult.FAILED;
    }
}
