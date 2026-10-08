package com.nutomic.syncthingandroid.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One fresh, operation-owned transfer directory used to move Managed State between the selected
 * backend and the shared archive code.
 *
 * <p>Managed State itself is never written by the application while a privileged backend is
 * selected, so every transfer goes through a directory that this class creates under one fixed
 * application-private staging base. The identifier of a transfer directory is generated here, so
 * callers never supply a privileged path: they receive this object, hand it back to the backend,
 * and the backend validates it again before it touches anything.</p>
 *
 * <p>The transfer directory belongs to exactly one operation. The operation that created it owns
 * its cleanup, and cleanup removes this directory only - never a sibling, never the staging base,
 * and never a path reached through a symbolic link.</p>
 */
public final class ManagedStateStaging implements AutoCloseable {
    /** Prefix of every generated transfer-directory name. */
    static final String OPERATION_PREFIX = "op-";

    @FunctionalInterface
    interface SymbolicLinkInspector {
        boolean isSymbolicLink(File file) throws IOException;
    }

    private static final ThreadLocal<SymbolicLinkInspector> TEST_SYMBOLIC_LINK_INSPECTOR =
            new ThreadLocal<>();

    private final File base;
    private final File directory;
    private final String operationId;

    /**
     * Creates one fresh transfer directory below the given staging base.
     *
     * @throws ManagedStateException when the base or the transfer directory cannot be created,
     *     or when the base is not a real application-private directory
     */
    public static ManagedStateStaging create(File stagingBase) throws ManagedStateException {
        File base = prepareBase(stagingBase);
        String operationId = OPERATION_PREFIX + UUID.randomUUID();
        File directory = new File(base, operationId);
        if (directory.exists() || !directory.mkdir()) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "Could not create the operation transfer directory"
            );
        }
        if (!restrictToOwner(directory)) {
            deleteTree(directory);
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "Could not restrict the operation staging directory"
            );
        }
        return new ManagedStateStaging(base, directory, operationId);
    }

    /** Prepares the shared staging base from the application process, never from UID 0. */
    static File prepareBase(File stagingBase) throws ManagedStateException {
        Objects.requireNonNull(stagingBase, "The staging base is required");
        File base = stagingBase.getAbsoluteFile();
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "Could not create the transfer staging base"
            );
        }
        if (!base.isDirectory() || isSymbolicLink(base)) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "The transfer staging base is not an application-private directory"
            );
        }
        if (!restrictToOwner(base)) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "Could not restrict the transfer staging base"
            );
        }
        File probe = null;
        try {
            probe = File.createTempFile(".managed-state-", ".probe", base);
            if (!probe.delete()) {
                throw new IOException("Could not remove the staging access probe");
            }
        } catch (IOException e) {
            if (probe != null) probe.delete();
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "The application cannot create and remove entries in the transfer staging base",
                    e
            );
        }
        return base;
    }

    /**
     * Construction seam used to model forged or stale transfer directories in tests.
     *
     * <p>Production code creates transfer directories through {@link #create(File)} only.</p>
     */
    ManagedStateStaging(File base, File directory, String operationId) {
        this.base = Objects.requireNonNull(base).getAbsoluteFile();
        this.directory = Objects.requireNonNull(directory).getAbsoluteFile();
        this.operationId = Objects.requireNonNull(operationId);
    }

    /** Returns the transfer directory, for the shared archive code that consumes its contents. */
    public File directory() {
        return directory;
    }

    /**
     * Returns the generated identifier of this transfer directory.
     *
     * <p>Backends pass this identifier to their privileged helper session so the helper acts on the
     * operation-owned directory only. The identifier is generated here, never supplied by a caller,
     * so it can never carry a path escape.</p>
     */
    String operationId() {
        return operationId;
    }

    /** Returns the fixed path of one approved member inside this transfer directory. */
    public File member(ManagedStateMember member) {
        return new File(directory, member.fileName());
    }

    /** Returns the approved members that currently exist inside this transfer directory. */
    public Set<ManagedStateMember> presentMembers() {
        Set<ManagedStateMember> present = EnumSet.noneOf(ManagedStateMember.class);
        for (ManagedStateMember member : ManagedStateMember.values()) {
            if (member(member).exists()) {
                present.add(member);
            }
        }
        return present;
    }

    /**
     * Validates that this object still describes one operation-owned transfer directory.
     *
     * <p>Every backend validates a transfer directory through this method before installing from
     * it, so a forged, stale, or symlinked path can never reach a privileged operation.</p>
     *
     * @throws ManagedStateException when the directory is not a generated direct child of the
     *     staging base, is missing, or is a symbolic link
     */
    public void verifyProvenance() throws ManagedStateException {
        verifyProvenance(base);
    }

    /**
     * Validates that this object still describes one operation-owned transfer directory below the
     * given staging base.
     *
     * <p>A backend validates a staging directory against its own configured base instead of the
     * base recorded in the object, so a forged directory can never borrow another location's
     * provenance.</p>
     *
     * @param expectedBase staging base the directory has to be a direct child of
     * @throws ManagedStateException when the directory was not generated below the base, is
     *     missing, or is a symbolic link
     */
    public void verifyProvenance(File expectedBase) throws ManagedStateException {
        if (!hasValidProvenance(expectedBase)) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "The transfer directory is not an owned staging directory"
            );
        }
    }

    /**
     * Proves that the application can consume and clean this transfer directory.
     *
     * <p>This is the handoff check that follows a privileged snapshot. A privileged helper runs as
     * another identity, so "the command succeeded" does not prove the application can actually
     * read what was staged: file modes, ownership, and the SELinux context of the staged tree all
     * have to hold. Every staged entry is therefore inspected from this process - regular files
     * are opened, directories are listed recursively, symbolic links and special files are
     * refused - and any failure fails the whole transfer closed.</p>
     *
     * @throws ManagedStateException when any staged entry cannot be read by this process, has an
     *     unexpected kind, or is a symbolic link
     */
    public void verifyAppReadable() throws ManagedStateException {
        verifyProvenance();
        verifyTreeReadable(directory);
    }

    /**
     * Removes this operation's transfer directory.
     *
     * <p>Cleanup is deliberately unambitious: it refuses to act unless this object still describes
     * an operation-owned directory, it never follows a symbolic link, and it removes nothing but
     * that one directory tree.</p>
     *
     * @return whether the transfer directory is gone
     */
    public boolean cleanup() {
        if (!hasValidProvenance()) {
            return false;
        }
        return !directory.exists() || deleteTree(directory);
    }

    @Override
    public void close() {
        cleanup();
    }

    private boolean hasValidProvenance() {
        return hasValidProvenance(base);
    }

    private boolean hasValidProvenance(File expectedBase) {
        try {
            if (!operationId.startsWith(OPERATION_PREFIX)
                    || !directory.getName().equals(operationId)
                    || isSymbolicLink(directory)
                    || !directory.isDirectory()
                    || !directory.getParentFile().getCanonicalFile()
                            .equals(expectedBase.getCanonicalFile())) {
                return false;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return true;
    }

    /**
     * Proves that this process can read one tree of regular files and directories.
     *
     * <p>Every entry is inspected from this process: directories are listed recursively, regular
     * files are opened, and symbolic links and special files fail the whole check. Ownership, mode,
     * and the security context have to combine into real read access for the process that runs
     * this check, which is why the check reads rather than merely observing metadata.</p>
     *
     * @param dir root of the tree to verify
     * @throws ManagedStateException when an entry cannot be listed, read, or has an unexpected kind
     */
    static void verifyTreeReadable(File dir) throws ManagedStateException {
        File[] entries = dir.listFiles();
        if (entries == null) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                    "The Managed State tree cannot be listed: " + dir.getName()
            );
        }
        for (File entry : entries) {
            if (isSymbolicLink(entry)) {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_TRANSFER_FAILED,
                        "The Managed State tree contains a symbolic link: " + entry.getName()
                );
            }
            if (entry.isDirectory()) {
                verifyTreeReadable(entry);
                continue;
            }
            if (!entry.isFile()) {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_TRANSFER_FAILED,
                        "The Managed State tree contains a special file: " + entry.getName()
                );
            }
            try (InputStream input = new FileInputStream(entry)) {
                input.read();
            } catch (IOException e) {
                throw new ManagedStateException(
                        ManagedStateFailure.STATE_TRANSFER_FAILED,
                        "The Managed State file cannot be read: " + entry.getName(),
                        e
                );
            }
        }
    }

    /**
     * Reports whether one path is a symbolic link, without following the link itself.
     *
     * <p>{@code lstat} inspects the directory entry itself instead of following its target, so
     * dangling links are detected as well. An absent entry is reported only for {@code ENOENT};
     * any other inspection failure counts as linked, so callers fail closed.</p>
     */
    public static boolean isSymbolicLink(File file) {
        SymbolicLinkInspector testInspector = TEST_SYMBOLIC_LINK_INSPECTOR.get();
        if (testInspector != null) {
            return inspectSymbolicLink(file, testInspector);
        }
        return inspectSymbolicLink(file, ManagedStateStaging::isSymbolicLinkWithLstat);
    }

    /** Uses {@code lstat}, which reports a dangling symbolic link without following its target. */
    private static boolean isSymbolicLinkWithLstat(File file) throws IOException {
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) {
                return false;
            }
            throw new IOException("Could not inspect " + file.getName(), e);
        }
    }

    private static boolean inspectSymbolicLink(
            File file,
            SymbolicLinkInspector inspector
    ) {
        File parent = file.getParentFile();
        if (parent == null || file.getName().isEmpty()) {
            return true;
        }
        try {
            return inspector.isSymbolicLink(file);
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    /** Installs a host-filesystem probe for JVM tests; production always uses Android {@code lstat}. */
    static void setSymbolicLinkInspectorForTests(SymbolicLinkInspector inspector) {
        if (inspector == null) {
            TEST_SYMBOLIC_LINK_INSPECTOR.remove();
        } else {
            TEST_SYMBOLIC_LINK_INSPECTOR.set(inspector);
        }
    }

    /**
     * Deletes one tree without following symbolic links and reports whether it is gone.
     *
     * <p>A symbolic link is unlinked itself; its target is never touched. A directory is emptied
     * first, so the removal stays inside the given tree.</p>
     */
    static boolean deleteTree(File file) {
        if (isSymbolicLink(file)) {
            return file.delete() && !file.exists() && !isSymbolicLink(file);
        }
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries == null) {
                return false;
            }
            boolean childrenDeleted = true;
            for (File entry : entries) {
                childrenDeleted &= deleteTree(entry);
            }
            if (!childrenDeleted) return false;
        }
        if (!file.exists()) return !isSymbolicLink(file);
        return file.delete() && !file.exists() && !isSymbolicLink(file);
    }

    /** Restricts one generated directory to its owner. */
    private static boolean restrictToOwner(File file) {
        boolean success = true;
        success &= file.setReadable(false, false);
        success &= file.setReadable(true, true);
        success &= file.setWritable(false, false);
        success &= file.setWritable(true, true);
        success &= file.setExecutable(false, false);
        success &= file.setExecutable(true, true);
        return success;
    }
}
