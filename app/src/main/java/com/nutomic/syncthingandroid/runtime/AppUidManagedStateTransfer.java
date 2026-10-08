package com.nutomic.syncthingandroid.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Objects;

/**
 * Managed State transfer that runs with the application UID.
 *
 * <p>Normal Mode owns Managed State directly, so every transfer step is an ordinary application
 * file operation. The implementation still refuses to follow symbolic links, still confines writes
 * to the six approved members, and still transfers through operation-owned staging, so the
 * behavior callers observe is the same as through the superuser backend.</p>
 */
final class AppUidManagedStateTransfer implements ManagedStateTransfer {

    @FunctionalInterface
    interface FileStatReader {
        FileStatus read(File path) throws IOException;
    }

    @FunctionalInterface
    interface DirectoryCopier {
        void copy(File source, File destination) throws ManagedStateException;
    }

    /** Owner and mode returned by a no-follow file-system stat. */
    static final class FileStatus {
        final int ownerUid;
        final int mode;

        FileStatus(int ownerUid, int mode) {
            this.ownerUid = ownerUid;
            this.mode = mode;
        }
    }

    private final ManagedStateLocations locations;
    private final FileStatReader fileStatReader;
    private final DirectoryCopier directoryCopier;

    AppUidManagedStateTransfer(ManagedStateLocations locations) {
        this(locations, AppUidManagedStateTransfer::readFileStatus,
                AppUidManagedStateTransfer::copyDirectory);
    }

    AppUidManagedStateTransfer(
            ManagedStateLocations locations,
            FileStatReader fileStatReader
    ) {
        this(locations, fileStatReader, AppUidManagedStateTransfer::copyDirectory);
    }

    AppUidManagedStateTransfer(
            ManagedStateLocations locations,
            FileStatReader fileStatReader,
            DirectoryCopier directoryCopier
    ) {
        this.locations = Objects.requireNonNull(locations);
        this.fileStatReader = Objects.requireNonNull(fileStatReader);
        this.directoryCopier = Objects.requireNonNull(directoryCopier);
    }

    @Override
    public ManagedStateStaging snapshotForExport() throws ManagedStateException {
        locations.requireSafeStateRoot(ManagedStateFailure.STATE_TRANSFER_FAILED);
        ManagedStateStaging staging = locations.newStaging();
        try {
            for (ManagedStateMember member : ManagedStateMember.values()) {
                File source = locations.member(member);
                if (!existsAsApprovedMember(source, member)) {
                    continue;
                }
                copyIntoStaging(source, staging.member(member), member);
            }
            return staging;
        } catch (ManagedStateException | RuntimeException e) {
            staging.cleanup();
            throw e;
        }
    }

    @Override
    public void installImportedState(ManagedStateStaging staging) throws ManagedStateException {
        Objects.requireNonNull(staging, "The import staging directory is required");
        locations.requireSafeStateRoot(ManagedStateFailure.STATE_TRANSFER_FAILED);
        staging.verifyProvenance(locations.stagingBase());
        staging.verifyAppReadable();
        requireStagedMember(staging, ManagedStateMember.CONFIG);
        requireStagedMember(staging, ManagedStateMember.CERT);
        requireStagedMember(staging, ManagedStateMember.KEY);
        validateWritableDirectory(locations.stateRoot());

        // Validate every live destination before the first replacement so one unsafe later
        // member cannot leave an earlier member imported into an otherwise unusable state tree.
        for (ManagedStateMember member : ManagedStateMember.values()) {
            validateLiveTarget(locations.member(member), member);
        }

        File index = locations.member(ManagedStateMember.INDEX);
        File stagedIndex = staging.member(ManagedStateMember.INDEX);
        if (stagedIndex.exists()) {
            installMember(stagedIndex, index, ManagedStateMember.INDEX);
        } else {
            // A legacy archive without index-v2 must never leave stale database state behind.
            removeTree(index);
        }

        for (ManagedStateMember member : ManagedStateMember.values()) {
            if (member == ManagedStateMember.INDEX) continue;
            File staged = staging.member(member);
            if (staged.exists()) {
                installMember(staged, locations.member(member), member);
            }
        }
    }

    private void validateLiveTarget(File live, ManagedStateMember member)
            throws ManagedStateException {
        if (ManagedStateStaging.isSymbolicLink(live)) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "The member " + member.fileName() + " is a symbolic link"
            );
        }
        if (!live.exists()) return;
        boolean correctKind = member.isDirectory() ? live.isDirectory() : live.isFile();
        if (!correctKind) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "The member " + member.fileName() + " has an unexpected kind"
            );
        }
        if (member.isDirectory()) {
            validateCanDelete(live.getParentFile(), live);
            validateLiveDirectory(live);
        }
    }

    private void validateLiveDirectory(File directory) throws ManagedStateException {
        validateWritableDirectory(directory);
        File[] entries = directory.listFiles();
        if (entries == null) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "Could not list the existing Managed State directory " + directory.getName()
            );
        }
        for (File entry : entries) {
            if (ManagedStateStaging.isSymbolicLink(entry)) {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_TRANSFER_FAILED,
                        "The existing Managed State directory contains a symbolic link: "
                                + entry.getName()
                );
            }
            validateCanDelete(directory, entry);
            if (entry.isDirectory()) {
                validateLiveDirectory(entry);
            } else if (!entry.isFile()) {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_TRANSFER_FAILED,
                        "The existing Managed State directory contains a special file: "
                                + entry.getName()
                );
            }
        }
    }

    /** Checks POSIX sticky-directory ownership before the import removes an existing entry. */
    private void validateCanDelete(File parent, File entry) throws ManagedStateException {
        try {
            FileStatus parentStatus = fileStatReader.read(parent);
            FileStatus entryStatus = fileStatReader.read(entry);
            boolean sticky = (parentStatus.mode & 01000) != 0;
            boolean appOwnsParent = parentStatus.ownerUid == locations.applicationUid();
            boolean appOwnsEntry = entryStatus.ownerUid == locations.applicationUid();
            if (sticky && !appOwnsParent && !appOwnsEntry) {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_TRANSFER_FAILED,
                        "The app cannot remove " + entry.getName()
                                + " from its sticky parent directory"
                );
            }
        } catch (IOException e) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "Could not verify removal ownership for " + entry.getName(),
                    e
            );
        }
    }

    private static FileStatus readFileStatus(File file) throws IOException {
        try {
            StructStat stat = Os.lstat(file.getAbsolutePath());
            return new FileStatus(stat.st_uid, stat.st_mode);
        } catch (ErrnoException e) {
            throw new IOException("Could not inspect " + file.getName(), e);
        }
    }

    private static void validateWritableDirectory(File directory) throws ManagedStateException {
        if (ManagedStateStaging.isSymbolicLink(directory) || !directory.isDirectory()
                || !directory.canWrite() || !directory.canExecute()) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "The Managed State directory cannot be safely replaced: "
                            + directory.getName()
            );
        }
    }

    @Override
    public void repairAppAccess() throws ManagedStateException {
        locations.requireSafeStateRoot(ManagedStateFailure.STATE_REPAIR_FAILED);
        for (ManagedStateMember member : ManagedStateMember.values()) {
            File path = locations.member(member);
            if (ManagedStateStaging.isSymbolicLink(path)) {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_REPAIR_FAILED,
                        "The Managed State member " + member.fileName() + " is a symbolic link"
                );
            }
            if (!path.exists()) {
                continue;
            }
            repairAccess(path, member);
        }
    }

    /**
     * Reports whether one approved member currently exists with its required kind and is not a
     * symbolic link.
     *
     * @return {@code false} only when the member is genuinely absent; an unsafe member fails
     */
    private boolean existsAsApprovedMember(File path, ManagedStateMember member)
            throws ManagedStateException {
        if (ManagedStateStaging.isSymbolicLink(path)) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "The Managed State member " + member.fileName() + " is a symbolic link"
            );
        }
        if (!path.exists()) {
            return false;
        }
        boolean correctKind = member.isDirectory() ? path.isDirectory() : path.isFile();
        if (!correctKind) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "The Managed State member " + member.fileName() + " has an unexpected kind"
            );
        }
        return true;
    }

    private void copyIntoStaging(File source, File target, ManagedStateMember member)
            throws ManagedStateException {
        if (member.isDirectory()) {
            copyDirectory(source, target);
        } else {
            copyFile(source, target);
        }
    }

    private static void copyFile(File source, File target) throws ManagedStateException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "Could not stage " + source.getName(),
                    e
            );
        }
    }

    private static void copyDirectory(File source, File target) throws ManagedStateException {
        if (!target.mkdir() && !target.isDirectory()) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "Could not create the staged directory " + target.getName()
            );
        }
        File[] entries = source.listFiles();
        if (entries == null) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "Could not list the state directory " + source.getName()
            );
        }
        for (File entry : entries) {
            if (ManagedStateStaging.isSymbolicLink(entry)) {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_TRANSFER_FAILED,
                        "The state directory contains a symbolic link: " + entry.getName()
                );
            }
            File targetEntry = new File(target, entry.getName());
            if (entry.isDirectory()) {
                copyDirectory(entry, targetEntry);
            } else if (entry.isFile()) {
                copyFile(entry, targetEntry);
            } else {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_TRANSFER_FAILED,
                        "The state directory contains a special file: " + entry.getName()
                );
            }
        }
    }

    private static void requireStagedMember(ManagedStateStaging staging, ManagedStateMember member)
            throws ManagedStateException {
        if (!staging.member(member).isFile()) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "The import staging is missing " + member.fileName()
            );
        }
    }

    /**
     * Replaces one live member with its staged copy.
     *
     * <p>A regular file is replaced through a temporary file in the same directory so the live
     * member is never observed half written. The index directory is replaced as a tree: the live
     * tree is removed without following links, and the staged tree is copied in its place.</p>
     */
    private void installMember(File staged, File live, ManagedStateMember member)
            throws ManagedStateException {
        if (ManagedStateStaging.isSymbolicLink(staged) || ManagedStateStaging.isSymbolicLink(live)) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "The member " + member.fileName() + " is a symbolic link"
            );
        }
        if (member.isDirectory()) {
            String token = java.util.UUID.randomUUID().toString();
            File parent = live.getParentFile();
            File prepared = new File(parent, "." + live.getName() + "-" + token + ".install");
            File previous = new File(parent, "." + live.getName() + "-" + token + ".previous");
            if (prepared.exists() || previous.exists()) {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_TRANSFER_FAILED,
                        "Could not reserve temporary paths for " + member.fileName()
                );
            }
            try {
                directoryCopier.copy(staged, prepared);
            } catch (ManagedStateException failure) {
                removeTree(prepared);
                throw failure;
            }
            boolean movedPrevious = false;
            if (live.exists()) {
                if (!live.renameTo(previous)) {
                    removeTree(prepared);
                    throw new ManagedStateException(
                            ManagedStateFailure.STATE_TRANSFER_FAILED,
                            "Could not preserve the existing " + member.fileName()
                    );
                }
                movedPrevious = true;
            }
            if (!prepared.renameTo(live)) {
                if (movedPrevious && !previous.renameTo(live)) {
                    throw new ManagedStateException(
                            ManagedStateFailure.STATE_TRANSFER_FAILED,
                            "Could not install or restore " + member.fileName()
                    );
                }
                removeTree(prepared);
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_TRANSFER_FAILED,
                        "Could not install " + member.fileName()
                );
            }
            if (movedPrevious) removeTree(previous);
            return;
        }
        File temporary;
        try {
            temporary = File.createTempFile(
                    live.getName() + "-", ".install", live.getParentFile()
            );
        } catch (IOException e) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "Could not prepare the installation of " + member.fileName(),
                    e
            );
        }
        try {
            copyFile(staged, temporary);
            if (!temporary.renameTo(live)) {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_TRANSFER_FAILED,
                        "Could not install " + member.fileName()
                );
            }
        } catch (ManagedStateException e) {
            removeTree(temporary);
            throw e;
        }
    }

    /** Removes one live member without following symbolic links; a missing member is a no-op. */
    private static void removeTree(File path) throws ManagedStateException {
        if (!path.exists() && !ManagedStateStaging.isSymbolicLink(path)) {
            return;
        }
        if (!ManagedStateStaging.deleteTree(path) || path.exists()
                || ManagedStateStaging.isSymbolicLink(path)) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "Could not remove the existing Managed State member " + path.getName()
            );
        }
    }

    /**
     * Makes one approved member accessible to this application and verifies the result.
     *
     * <p>Only owner access is requested, because the application is the owner of Normal Mode state.
     * A member owned by another identity cannot be repaired here, which is reported as a repair
     * failure rather than silently ignored.</p>
     */
    private static void repairAccess(File path, ManagedStateMember member)
            throws ManagedStateException {
        if (member.isDirectory()) {
            repairDirectoryAccess(path);
        } else {
            repairFileAccess(path);
        }
    }

    private static void repairFileAccess(File file) throws ManagedStateException {
        if (ManagedStateStaging.isSymbolicLink(file) || !file.isFile()) {
            throw repairFailure(file);
        }
        try {
            if (!file.setReadable(true, true) || !file.setWritable(true, true)
                    || ManagedStateStaging.isSymbolicLink(file) || !file.isFile()
                    || !file.canRead() || !file.canWrite()) {
                throw repairFailure(file);
            }
            try (InputStream input = new FileInputStream(file);
                 RandomAccessFile writable = new RandomAccessFile(file, "rw")) {
                input.read();
                writable.getFilePointer();
            }
        } catch (IOException | SecurityException e) {
            throw repairFailure(file);
        }
    }

    private static void repairDirectoryAccess(File directory) throws ManagedStateException {
        if (!directory.setReadable(true, true)
                || !directory.setWritable(true, true)
                || !directory.setExecutable(true, true)) {
            throw repairFailure(directory);
        }
        File[] entries = directory.listFiles();
        if (entries == null) {
            throw repairFailure(directory);
        }
        for (File entry : entries) {
            if (ManagedStateStaging.isSymbolicLink(entry)) {
                throw repairFailure(entry);
            }
            if (entry.isDirectory()) {
                repairDirectoryAccess(entry);
            } else if (entry.isFile()) {
                repairFileAccess(entry);
            } else {
                throw repairFailure(entry);
            }
        }
    }

    private static ManagedStateException repairFailure(File path) {
        return new ManagedStateException(
                ManagedStateFailure.STATE_REPAIR_FAILED,
                "Could not restore application access to " + path.getName()
        );
    }
}
