package com.nutomic.syncthingandroid.runtime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * App-owned spool directory for one root Syncthing run.
 *
 * <p>Every run gets its own directory under the app's no-backup storage. The evidence file first
 * receives the durable pre-delivery state of the launch transport. The launch script writes the
 * complete execution record into the staging file and then renames that file over the evidence file,
 * so the durable record changes from pending state to complete pre-exec evidence atomically and is
 * never truncated or partially overwritten. The output file receives the merged standard output and
 * standard error of the launched process, and the command file records which bundled command produced
 * the run, so leftover output can be reconciled after app death. Every file is created by the app
 * before launch, so the UID-0 process always writes into existing app-owned files instead of creating
 * root-owned ones.</p>
 *
 * <p>The directory also carries the exclusive in-process ownership lease of the run. The lease is
 * an operating-system file lock on an app-owned file, so it exists exactly as long as the
 * application process that took it and disappears automatically when that process dies. It is not
 * durable evidence: a crash releases it, and the durable pre-delivery record and launch evidence
 * remain the only state that keeps an orphaned run from being reconciled unsafely.</p>
 */
final class RootRunSpool {
    static final String EVIDENCE_FILE = "evidence";
    /** Staging file the launch script fills before its atomic rename over {@link #EVIDENCE_FILE}. */
    static final String EVIDENCE_STAGING_FILE = "evidence.staging";
    static final String OUTPUT_FILE = "output";
    static final String COMMAND_FILE = "command";
    static final String CONSUMED_FILE = "consumed";
    /**
     * Prefix of the stable sibling file whose exclusive file lock represents ownership of one run.
     *
     * <p>The lease file never lives inside the run directory it protects. Unlinking a locked file
     * keeps its lock attached to the old inode, so a lease inside the deletable directory could be
     * replaced by a second lock on a new inode while the first owner still believed it owned the
     * run.</p>
     */
    static final String LEASE_FILE_PREFIX = ".lease-";
    /** Poll interval used while tailing a growing spool file. */
    static final long OUTPUT_POLL_MILLIS = 50;

    private static final int MAXIMUM_COMMAND_BYTES = 256;

    private final File spoolRoot;
    private final File directory;
    private final String runToken;
    private final String commandName;
    /** Exclusive in-process ownership of this run, held while a local handle still works on it. */
    private Lease lease;
    /**
     * Test seam invoked after this handle has taken the run's lease and before the run directory is
     * created, so a test can prove no run is ever visible without an owner. Production code leaves
     * it {@code null}.
     */
    Runnable leaseAcquiredHookForTesting;
    /**
     * Test seam invoked after this handle has started deleting a run it owns and before the run
     * directory is unlinked, so a test can prove a deletion in progress still excludes other
     * owners. Production code leaves it {@code null}.
     */
    Runnable deletionStartedHookForTesting;

    private RootRunSpool(File spoolRoot, File directory, String runToken, String commandName) {
        this.spoolRoot = spoolRoot;
        this.directory = directory;
        this.runToken = runToken;
        this.commandName = commandName;
    }

    /**
     * Plans the app-owned spool of one run without touching the filesystem.
     *
     * <p>Preparation plans the run early so the audited launch script can be encoded from its
     * paths, but nothing exists on disk yet. A materialized spool that carries no durable
     * pre-delivery state would look like an empty leftover run to any other runtime's recovery,
     * which could reconcile it away while this launch is still waiting for root.
     * {@link #materialize(ExecutionIdentity)} therefore creates the complete spool only at the
     * arming boundary, after root capability and the final recovery classification are secured.</p>
     */
    static RootRunSpool plan(File spoolRoot, String runToken, String commandName) {
        Objects.requireNonNull(spoolRoot);
        Objects.requireNonNull(runToken);
        Objects.requireNonNull(commandName);
        return new RootRunSpool(spoolRoot, new File(spoolRoot, runToken), runToken, commandName);
    }

    /**
     * Creates a complete app-owned spool without durable pre-delivery state.
     *
     * <p>This models a leftover run of a finished launch, so tests and evidence fixtures can build
     * the exact directory shape reconciliation has to classify. A launch never uses this factory:
     * it plans its spool first and materializes it through
     * {@link #materialize(ExecutionIdentity)}.</p>
     */
    static RootRunSpool create(File spoolRoot, String runToken, String commandName)
            throws IOException {
        RootRunSpool spool = plan(spoolRoot, runToken, commandName);
        if (!spool.directory.isDirectory() && !spool.directory.mkdirs()) {
            throw new IOException("Could not create the root run spool directory");
        }
        spool.createEmptyFile(spool.evidenceFile());
        spool.createAppOwnedFiles();
        return spool;
    }

    /**
     * Creates the complete app-owned spool of this run and records its durable pre-delivery state.
     *
     * <p>Ownership is taken before the run becomes visible: the lease is acquired first, and only
     * then are the run directory and its durable pre-delivery state created. A concurrent
     * reconciliation can therefore never observe a run directory that no owner holds, and can never
     * take the lease of the handle that is arming it. The durable state is written before any launch
     * byte can be delivered, so a recovery either sees no run at all or a run whose pending state
     * makes every classifier fail closed. The remaining app-owned files - the staging file the
     * launch script fills, the output file, the consumption offset, and the command name - are
     * created before transport, so the UID-0 process always writes into files this application
     * created.</p>
     *
     * <p>A failed acquisition leaves every existing run directory untouched, because that directory
     * may belong to another owner. A failure after acquisition removes only the partially created
     * run of this handle while the handle still holds its lease.</p>
     *
     * @param pendingTransport the launch transport recorded as the durable pre-delivery state
     * @throws IOException when the run's lease cannot be taken or its app-owned files cannot be
     *     created
     */
    void materialize(ExecutionIdentity pendingTransport) throws IOException {
        Objects.requireNonNull(pendingTransport);
        if (!spoolRoot.isDirectory() && !spoolRoot.mkdirs() && !spoolRoot.isDirectory()) {
            throw new IOException("Could not create the root run spool root");
        }
        // A failed acquisition must not touch the run directory, because that directory may belong
        // to another owner that holds the lease this handle could not take.
        if (!acquireLease()) {
            throw new IOException("Could not take exclusive ownership of the root run spool");
        }
        if (leaseAcquiredHookForTesting != null) {
            leaseAcquiredHookForTesting.run();
        }
        try {
            if (!directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("Could not create the root run spool directory");
            }
            RootExecutionRecordStore.writePendingEvidence(evidenceFile(), pendingTransport);
            createAppOwnedFiles();
        } catch (IOException | RuntimeException e) {
            // This handle owns the run, so removing what it just created is safe; ownership is
            // released only after the partially created run is gone. A partial run directory that
            // cannot be removed keeps its lease file, because unlinking the namespace that provides
            // exclusion while the directory still exists would let another owner lock a replacement
            // inode of the same pathname.
            if (deleteDirectory(directory)) {
                releaseLeaseAndDeleteLeaseFile();
            } else {
                releaseLease();
            }
            throw e;
        }
    }

    /**
     * Takes the exclusive in-process ownership lease of this run.
     *
     * <p>The lease is only ever held by one handle at a time: a preparation keeps it from arming
     * through process creation and the returned execution keeps it until the run is finished, and
     * a successful deletion releases it only after the directory is gone.</p>
     *
     * @return whether this spool holds the lease afterwards
     */
    boolean acquireLease() {
        if (leaseHeld()) {
            return true;
        }
        releaseLease();
        Lease acquired = tryAcquireLease(leaseFile());
        if (acquired == null) {
            return false;
        }
        lease = acquired;
        return true;
    }

    /** Reports whether this spool currently owns the run through a held lease. */
    boolean leaseHeld() {
        return lease != null && lease.isHeld();
    }

    /**
     * Gives up in-process ownership while leaving the run on disk for a later recovery.
     *
     * <p>Used when an execution intentionally retains its spool for reconciliation and when a
     * failed launch creates no execution handle: once no local reader or writer needs the run any
     * more, its ownership must not stay behind as an in-process lock that would hide the run from
     * every later reconciliation in this application process.</p>
     */
    void releaseLease() {
        Lease held = lease;
        lease = null;
        if (held != null) {
            held.release();
        }
    }

    /** Returns this run's stable sibling lease file. */
    File leaseFile() {
        return leaseFileFor(spoolRoot, runToken);
    }

    /** Unlinks this run's lease file while this handle still holds its lock. */
    private void deleteLeaseFileWhileHeld() {
        if (leaseHeld()) {
            leaseFile().delete();
        }
    }

    /**
     * Gives up ownership after removing this run's lease file while the lock is still held.
     *
     * <p>Only a handle that owns the run and has already removed the run directory may call this,
     * so the unlink never races another owner of a live run.</p>
     */
    private void releaseLeaseAndDeleteLeaseFile() {
        deleteLeaseFileWhileHeld();
        releaseLease();
    }

    /**
     * Takes the exclusive ownership lease of one run.
     *
     * @param leaseFile stable sibling lease file of the run, which must never live inside a
     *     directory its owner may delete
     * @return the held lease, or {@code null} when another handle in this application process or
     *     another application process already owns the run, or the lock cannot be taken at all
     */
    static Lease tryAcquireLease(File leaseFile) {
        FileChannel channel = null;
        try {
            channel = new RandomAccessFile(leaseFile, "rw").getChannel();
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // Another handle in this application process owns the run.
                lock = null;
            }
            if (lock == null) {
                channel.close();
                return null;
            }
            return new Lease(channel, lock);
        } catch (IOException | RuntimeException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // The channel is released with the process even when closing fails.
                }
            }
            return null;
        }
    }

    /** Returns the stable sibling lease file of one run below its spool root. */
    static File leaseFileFor(File spoolRoot, String runToken) {
        return new File(spoolRoot, LEASE_FILE_PREFIX + runToken);
    }

    /** Reports whether one spool-root entry is a lease file rather than a run directory. */
    static boolean isLeaseFileName(String name) {
        return name.startsWith(LEASE_FILE_PREFIX);
    }

    /**
     * Unlinks the lease file of one run while its lock is held.
     *
     * <p>Callers must already hold the lease and must only call this after the run directory is
     * gone: unlinking a lease that still protects a live run would let another owner lock a new
     * inode of the same pathname and believe it owned that run.</p>
     */
    static void deleteLeaseFileWhileHeld(File spoolRoot, String runToken) {
        leaseFileFor(spoolRoot, runToken).delete();
    }

    /**
     * Removes the lease files whose run directory is gone.
     *
     * <p>An orphaned lease file is never execution evidence and never blocks recovery, because a
     * run is only ever reconciled through its own directory. Removing one is only safe while this
     * process can take its lock, because the unlink then cannot detach an inode another owner
     * holds.</p>
     */
    static void deleteOrphanLeaseFiles(File spoolRoot) {
        File[] children = spoolRoot == null ? null : spoolRoot.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = child.getName();
            if (!isLeaseFileName(name)) {
                continue;
            }
            String runToken = name.substring(LEASE_FILE_PREFIX.length());
            if (new File(spoolRoot, runToken).exists()) {
                continue;
            }
            Lease orphan = tryAcquireLease(child);
            if (orphan == null) {
                continue;
            }
            try {
                if (!new File(spoolRoot, runToken).exists()) {
                    child.delete();
                }
            } finally {
                orphan.release();
            }
        }
    }

    /** Exclusive operating-system lease of one run directory. */
    static final class Lease {
        private final FileChannel channel;
        private final FileLock lock;

        private Lease(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        /** Reports whether this lease still owns its run directory exclusively. */
        boolean isHeld() {
            return lock.isValid();
        }

        /** Releases exclusive ownership; safe to call more than once. */
        void release() {
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } catch (IOException ignored) {
                // The lock disappears with the process even when releasing it fails.
            } finally {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // The channel is released with the process even when closing it fails.
                }
            }
        }
    }

    /**
     * Creates the app-owned files of this run, with the command name written last so a partially
     * created spool can never be attributed to a bundled command.
     */
    private void createAppOwnedFiles() throws IOException {
        createEmptyFile(evidenceStagingFile());
        createEmptyFile(outputFile());
        writeText(consumedFile(), "0");
        writeText(commandFile(), commandName);
    }

    File directory() {
        return directory;
    }

    String runToken() {
        return runToken;
    }

    String commandName() {
        return commandName;
    }

    File evidenceFile() {
        return new File(directory, EVIDENCE_FILE);
    }

    /**
     * App-owned file that receives the complete execution evidence before the launch script renames
     * it over {@link #evidenceFile()}.
     *
     * <p>The file exists before the launch, so the UID-0 launch process never creates durable state
     * of its own and the rename always replaces an app-owned file.</p>
     */
    File evidenceStagingFile() {
        return new File(directory, EVIDENCE_STAGING_FILE);
    }

    File outputFile() {
        return new File(directory, OUTPUT_FILE);
    }

    File commandFile() {
        return new File(directory, COMMAND_FILE);
    }

    /**
     * Offset of this run's output that has already been appended to the shared Syncthing log.
     *
     * <p>The offset is advanced only after the corresponding bytes reached the log, so a crash can
     * duplicate at most the chunk that was in flight and can never lose output.</p>
     */
    File consumedFile() {
        return new File(directory, CONSUMED_FILE);
    }

    /** Opens the blocking reader that streams this run's output as the process writes it. */
    InputStream openOutputTail(RootShell shell) throws IOException {
        return openOutputTail(shell::hasExited);
    }

    /**
     * Opens the blocking reader with an execution-level liveness source.
     *
     * <p>Root transports whose local {@code su} client is only a proxy must use exact execution
     * liveness here instead of client-process liveness, otherwise a dead proxy can truncate output
     * while the UID-0 Syncthing process is still running.</p>
     */
    InputStream openOutputTail(SpoolTailInputStream.Liveness liveness) throws IOException {
        return new SpoolTailInputStream(outputFile(), liveness, OUTPUT_POLL_MILLIS);
    }

    /**
     * Removes this run's spool directory after its exit has been proven.
     *
     * <p>The owner keeps exclusive ownership through the whole deletion: the run contents are
     * unlinked while the lease is still held, and the lease file itself is only unlinked after the
     * run directory it protects no longer exists. A concurrent reconciliation can therefore never
     * interleave between giving up ownership and removing the directory, and can never replace the
     * lock while this deletion is still running.</p>
     */
    boolean delete() {
        if (deletionStartedHookForTesting != null) {
            deletionStartedHookForTesting.run();
        }
        boolean deleted = deleteDirectory(directory);
        if (deleted) {
            deleteLeaseFileWhileHeld();
        }
        releaseLease();
        return deleted;
    }

    /** Lists the run directories that are present below one spool root. */
    static List<File> listRunDirectories(File spoolRoot) {
        File[] children = spoolRoot == null ? null : spoolRoot.listFiles();
        List<File> directories = new ArrayList<>();
        if (children == null) {
            return directories;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                directories.add(child);
            }
        }
        return directories;
    }

    /** Reads the recorded command name of a leftover run, or {@code null} when it is unreadable. */
    static String readCommandName(File directory) {
        File commandFile = new File(directory, COMMAND_FILE);
        try (FileInputStream input = new FileInputStream(commandFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAXIMUM_COMMAND_BYTES) {
                    return null;
                }
                output.write(buffer, 0, count);
            }
            String name = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            return name.isEmpty() ? null : name;
        } catch (IOException e) {
            return null;
        }
    }

    /** Reports whether a leftover run already produced output. */
    static boolean hasOutput(File directory) {
        File output = new File(directory, OUTPUT_FILE);
        return output.isFile() && output.length() > 0;
    }

    /** Deletes one directory tree; returns whether the directory no longer exists. */
    static boolean deleteDirectory(File directory) {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteDirectory(child);
                } else {
                    child.delete();
                }
            }
        }
        return !directory.exists() || directory.delete();
    }

    private void createEmptyFile(File file) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.flush();
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }
}
