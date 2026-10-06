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
    /** App-owned file whose exclusive file lock represents in-process ownership of this run. */
    static final String LEASE_FILE = "lease";
    /** Poll interval used while tailing a growing spool file. */
    static final long OUTPUT_POLL_MILLIS = 50;

    private static final int MAXIMUM_COMMAND_BYTES = 256;

    private final File directory;
    private final String runToken;
    private final String commandName;
    /** Exclusive in-process ownership of this run, held while a local handle still works on it. */
    private Lease lease;

    private RootRunSpool(File directory, String runToken, String commandName) {
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
        return new RootRunSpool(new File(spoolRoot, runToken), runToken, commandName);
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
     * <p>The durable state is written first, so no concurrent recovery can ever observe this run as
     * an empty leftover: either the run directory does not exist yet, or it already carries the
     * pre-delivery state that makes every classifier fail closed. The remaining app-owned files -
     * the staging file the launch script fills, the output file, the consumption offset, and the
     * command name - are created before any launch byte can be delivered, so the UID-0 process
     * always writes into files this application created. A failure while the spool is being created
     * removes the partially created run directory, so a launch that cannot be armed leaves no
     * leftovers behind.</p>
     *
     * @param pendingTransport the launch transport recorded as the durable pre-delivery state
     * @throws IOException when the run directory or one of its app-owned files cannot be created
     */
    void materialize(ExecutionIdentity pendingTransport) throws IOException {
        Objects.requireNonNull(pendingTransport);
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create the root run spool directory");
        }
        try {
            // Arming takes exclusive in-process ownership first, so no concurrent reconciliation
            // in this application process can ever inspect, append to, or delete a run this
            // launch is still preparing. A failure to take the lease fails arming before any
            // launch byte can be transported.
            if (!acquireLease()) {
                throw new IOException("Could not take exclusive ownership of the root run spool");
            }
            RootExecutionRecordStore.writePendingEvidence(evidenceFile(), pendingTransport);
            createAppOwnedFiles();
        } catch (IOException | RuntimeException e) {
            delete();
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
        Lease acquired = tryAcquireLease(directory);
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

    /**
     * Takes the exclusive ownership lease of one run directory.
     *
     * @param directory run directory to own
     * @return the held lease, or {@code null} when another handle in this application process
     *     already owns the run or the lock cannot be taken at all
     */
    static Lease tryAcquireLease(File directory) {
        FileChannel channel = null;
        try {
            channel = new RandomAccessFile(new File(directory, LEASE_FILE), "rw").getChannel();
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
        return new SpoolTailInputStream(outputFile(), shell::hasExited, OUTPUT_POLL_MILLIS);
    }

    /**
     * Removes this run's spool directory after its exit has been proven.
     *
     * <p>The owner keeps exclusive ownership through the deletion itself and releases its lease
     * only afterwards, so no concurrent reconciliation can interleave between giving up ownership
     * and removing the directory.</p>
     */
    boolean delete() {
        boolean deleted = deleteDirectory(directory);
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
