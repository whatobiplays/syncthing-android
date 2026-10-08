package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Covers the composite evidence store that root recovery and token-safe cleanup trust.
 *
 * <p>Every test writes real bytes into a temporary directory, so a cleanup runs against exactly
 * the canonical record and the run spool that a rooted launch leaves behind.</p>
 */
public class RootEvidenceStoreTest {

    private static final String EXECUTABLE = "/data/user/0/app/files/libsyncthingnative.so";

    @Test
    public void deletionProvesTheRunGoneOnlyAfterBothSourcesAreFree() throws IOException {
        File directory = temporaryDirectory();
        try {
            File canonicalFile = new File(directory, "record.txt");
            File spoolRoot = new File(directory, "spool");
            RootEvidenceStore store = new RootEvidenceStore(canonicalFile, spoolRoot);
            ExecutionIdentity identity = identity(4242, "token-a");
            store.write(identity);
            RootRunSpool.plan(spoolRoot, "token-a", SyncthingCommand.SERVE.name())
                    .materialize(identity);

            assertTrue(store.deleteIfRunTokenMatches("token-a"));

            assertEquals(
                    ExecutionRecordStore.ReadResult.Status.MISSING,
                    store.read().status()
            );
            assertEquals(
                    ExecutionRecordStore.PendingLaunch.Status.NONE,
                    store.readPendingLaunch().status()
            );
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void completePreExecEvidenceInTheSpoolIsRemovedByItsRunToken() throws IOException {
        File directory = temporaryDirectory();
        try {
            File spoolRoot = new File(directory, "spool");
            RootEvidenceStore store = new RootEvidenceStore(
                    new File(directory, "record.txt"), spoolRoot
            );
            RootRunSpool spool = RootRunSpool.create(
                    spoolRoot, "token-a", SyncthingCommand.SERVE.name()
            );
            writeText(
                    spool.evidenceFile(),
                    "version=1\npid=4242\nstart_ticks=9001\nboot_id=boot-a\nexe=" + EXECUTABLE
                            + "\ntoken=token-a\n"
            );

            assertTrue(store.deleteIfRunTokenMatches("token-a"));

            assertEquals(
                    ExecutionRecordStore.ReadResult.Status.MISSING,
                    store.read().status()
            );
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void runWithoutAnySurvivingEvidenceIsReportedGone() throws IOException {
        File directory = temporaryDirectory();
        try {
            RootEvidenceStore store = new RootEvidenceStore(
                    new File(directory, "record.txt"), new File(directory, "spool")
            );

            assertTrue(store.deleteIfRunTokenMatches("token-a"));
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void unattributableCanonicalEvidenceKeepsTheCleanupUnproven() throws IOException {
        File directory = temporaryDirectory();
        try {
            File canonicalFile = new File(directory, "record.txt");
            File spoolRoot = new File(directory, "spool");
            RootEvidenceStore store = new RootEvidenceStore(canonicalFile, spoolRoot);
            writeText(canonicalFile, "this is not an execution record\n");
            RootRunSpool.plan(spoolRoot, "token-a", SyncthingCommand.SERVE.name())
                    .materialize(identity(4242, "token-a"));

            assertFalse(
                    "damaged durable evidence can never prove that the run is gone",
                    store.deleteIfRunTokenMatches("token-a")
            );

            assertTrue("the unattributable record is retained", canonicalFile.exists());
            assertEquals(
                    ExecutionRecordStore.ReadResult.Status.CORRUPT,
                    store.read().status()
            );
            assertEquals(
                    "the cleanup still removed the state it could attribute",
                    ExecutionRecordStore.PendingLaunch.Status.NONE,
                    store.readPendingLaunch().status()
            );
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void anotherRunsRecordSurvivesAndDoesNotBlockTheCleanup() throws IOException {
        File directory = temporaryDirectory();
        try {
            File canonicalFile = new File(directory, "record.txt");
            File spoolRoot = new File(directory, "spool");
            RootEvidenceStore store = new RootEvidenceStore(canonicalFile, spoolRoot);
            store.write(identity(7000, "token-b"));
            RootRunSpool.plan(spoolRoot, "token-a", SyncthingCommand.SERVE.name())
                    .materialize(identity(4242, "token-a"));

            assertTrue(store.deleteIfRunTokenMatches("token-a"));

            ExecutionRecordStore.ReadResult read = store.read();
            assertEquals(ExecutionRecordStore.ReadResult.Status.VALID, read.status());
            assertEquals("token-b", read.identity().runToken());
        } finally {
            deleteRecursively(directory);
        }
    }

    private static ExecutionIdentity identity(int pid, String runToken) {
        return new ExecutionIdentity(pid, 9001, "boot-a", EXECUTABLE, runToken);
    }

    private static File temporaryDirectory() throws IOException {
        File directory = File.createTempFile("root-evidence-store", "");
        if (!directory.delete() || !directory.mkdir()) {
            throw new IOException("Could not prepare a temporary directory");
        }
        return directory;
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursively(File directory) {
        RootRunSpool.deleteDirectory(directory);
    }
}

