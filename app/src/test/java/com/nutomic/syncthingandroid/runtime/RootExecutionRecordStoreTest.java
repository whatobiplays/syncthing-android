package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Covers the durable, versioned execution record that root recovery trusts.
 *
 * <p>Every test writes real bytes into a temporary directory, so corrupt, unsupported, missing, and
 * oversized evidence are exercised exactly as recovery would read them.</p>
 */
public class RootExecutionRecordStoreTest {

    private static final String EXECUTABLE = "/data/user/0/app/files/libsyncthingnative.so";

    @Test
    public void writtenRecordRoundTripsEveryIdentityField() throws IOException {
        File directory = temporaryDirectory();
        try {
            RootExecutionRecordStore store = new RootExecutionRecordStore(
                    new File(directory, "record.txt")
            );
            ExecutionIdentity identity = new ExecutionIdentity(
                    4242, 9001, "boot-a", EXECUTABLE, "token-a"
            );

            store.write(identity);

            ExecutionRecordStore.ReadResult read = store.read();
            assertEquals(ExecutionRecordStore.ReadResult.Status.VALID, read.status());
            assertEquals(4242, read.identity().pid());
            assertEquals(9001, read.identity().processStartTimeTicks());
            assertEquals("boot-a", read.identity().bootId());
            assertEquals(EXECUTABLE, read.identity().executablePath());
            assertEquals("token-a", read.identity().runToken());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void missingRecordIsReportedAsMissing() throws IOException {
        File directory = temporaryDirectory();
        try {
            RootExecutionRecordStore store = new RootExecutionRecordStore(
                    new File(directory, "record.txt")
            );

            ExecutionRecordStore.ReadResult read = store.read();

            assertEquals(ExecutionRecordStore.ReadResult.Status.MISSING, read.status());
            assertNull(read.identity());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void truncatedRecordIsReportedAsCorrupt() throws IOException {
        File directory = temporaryDirectory();
        try {
            File record = new File(directory, "record.txt");
            writeText(record, "version=1\npid=42\nstart_ticks=7\n");

            ExecutionRecordStore.ReadResult read = new RootExecutionRecordStore(record).read();

            assertEquals(ExecutionRecordStore.ReadResult.Status.CORRUPT, read.status());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void recordWithUnexpectedLineSequenceIsReportedAsCorrupt() throws IOException {
        File directory = temporaryDirectory();
        try {
            File record = new File(directory, "record.txt");
            writeText(
                    record,
                    "version=1\npid=42\nboot_id=boot-a\nstart_ticks=7\nexe=" + EXECUTABLE
                            + "\ntoken=token-a\n"
            );

            ExecutionRecordStore.ReadResult read = new RootExecutionRecordStore(record).read();

            assertEquals(ExecutionRecordStore.ReadResult.Status.CORRUPT, read.status());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void emptyRecordIsReportedAsCorrupt() throws IOException {
        File directory = temporaryDirectory();
        try {
            File record = new File(directory, "record.txt");
            writeText(record, "");

            ExecutionRecordStore.ReadResult read = new RootExecutionRecordStore(record).read();

            assertEquals(ExecutionRecordStore.ReadResult.Status.CORRUPT, read.status());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void newerSchemaVersionIsReportedAsUnsupported() throws IOException {
        File directory = temporaryDirectory();
        try {
            File record = new File(directory, "record.txt");
            writeText(
                    record,
                    // Version 2 carries the pre-delivery launch state, so an unknown schema is a
                    // version this build cannot interpret at all.
                    "version=3\npid=42\nstart_ticks=7\nboot_id=boot-a\nexe=" + EXECUTABLE
                            + "\ntoken=token-a\n"
            );

            ExecutionRecordStore.ReadResult read = new RootExecutionRecordStore(record).read();

            assertEquals(ExecutionRecordStore.ReadResult.Status.UNSUPPORTED_VERSION, read.status());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void oversizedRecordIsReportedAsCorrupt() throws IOException {
        File directory = temporaryDirectory();
        try {
            File record = new File(directory, "record.txt");
            byte[] oversized = new byte[64 * 1024];
            java.util.Arrays.fill(oversized, (byte) 'x');
            try (FileOutputStream output = new FileOutputStream(record)) {
                output.write(oversized);
            }

            ExecutionRecordStore.ReadResult read = new RootExecutionRecordStore(record).read();

            assertEquals(ExecutionRecordStore.ReadResult.Status.CORRUPT, read.status());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void nonNumericPidIsReportedAsCorrupt() throws IOException {
        File directory = temporaryDirectory();
        try {
            File record = new File(directory, "record.txt");
            writeText(
                    record,
                    "version=1\npid=not-a-pid\nstart_ticks=7\nboot_id=boot-a\nexe=" + EXECUTABLE
                            + "\ntoken=token-a\n"
            );

            ExecutionRecordStore.ReadResult read = new RootExecutionRecordStore(record).read();

            assertEquals(ExecutionRecordStore.ReadResult.Status.CORRUPT, read.status());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void deleteOnlyMatchesTheCurrentRunToken() throws IOException {
        File directory = temporaryDirectory();
        try {
            File record = new File(directory, "record.txt");
            RootExecutionRecordStore store = new RootExecutionRecordStore(record);
            store.write(new ExecutionIdentity(4242, 9001, "boot-a", EXECUTABLE, "token-a"));

            assertFalse(
                    "a different run token must not clear live evidence",
                    store.deleteIfRunTokenMatches("token-b")
            );
            assertEquals(ExecutionRecordStore.ReadResult.Status.VALID, store.read().status());

            assertTrue(store.deleteIfRunTokenMatches("token-a"));
            assertEquals(ExecutionRecordStore.ReadResult.Status.MISSING, store.read().status());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void deleteOfUnreadableEvidenceReportsNoMatch() throws IOException {
        File directory = temporaryDirectory();
        try {
            File record = new File(directory, "record.txt");
            writeText(record, "garbage");

            assertFalse(new RootExecutionRecordStore(record).deleteIfRunTokenMatches("token-a"));
            assertTrue(record.exists());
        } finally {
            deleteRecursively(directory);
        }
    }

    private static File temporaryDirectory() throws IOException {
        File directory = File.createTempFile("root-record", "");
        if (!directory.delete() || !directory.mkdir()) {
            throw new IOException("Could not prepare a temporary directory");
        }
        return directory;
    }

    @Test
    public void strictestEvidenceStatusWinsWhenNeitherSourceIsValid() {
        assertEquals(
                ExecutionRecordStore.ReadResult.Status.READ_FAILED,
                ExecutionRecordStore.ReadResult.moreSevere(
                        ExecutionRecordStore.ReadResult.corrupt(),
                        ExecutionRecordStore.ReadResult.readFailed()
                ).status()
        );
        assertEquals(
                ExecutionRecordStore.ReadResult.Status.CORRUPT,
                ExecutionRecordStore.ReadResult.moreSevere(
                        ExecutionRecordStore.ReadResult.missing(),
                        ExecutionRecordStore.ReadResult.corrupt()
                ).status()
        );
        assertEquals(
                ExecutionRecordStore.ReadResult.Status.UNSUPPORTED_VERSION,
                ExecutionRecordStore.ReadResult.moreSevere(
                        ExecutionRecordStore.ReadResult.unsupportedVersion(),
                        ExecutionRecordStore.ReadResult.missing()
                ).status()
        );
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void pendingStateRoundTripsTheTransportIdentity() throws IOException {
        File directory = temporaryDirectory();
        try {
            File evidence = new File(directory, RootRunSpool.EVIDENCE_FILE);
            RootExecutionRecordStore.writePendingEvidence(
                    evidence,
                    new ExecutionIdentity(4242, 9001, "boot-a", EXECUTABLE, "token-a")
            );

            ExecutionRecordStore.PendingLaunch pending =
                    RootExecutionRecordStore.readPendingEvidence(evidence);
            assertEquals(ExecutionRecordStore.PendingLaunch.Status.PENDING, pending.status());
            assertEquals(4242, pending.transportIdentity().pid());
            assertEquals(9001, pending.transportIdentity().processStartTimeTicks());
            assertEquals("boot-a", pending.transportIdentity().bootId());
            assertEquals(EXECUTABLE, pending.transportIdentity().executablePath());
            assertEquals("token-a", pending.transportIdentity().runToken());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void pendingStateIsReadableThroughTheCanonicalEvidenceReader() throws IOException {
        // The creation confirmation reads the same file through the canonical reader, so the
        // pre-delivery state has to be recognizable there: it is the only durable identity a
        // launch that has not written its pre-exec evidence block yet can have.
        File directory = temporaryDirectory();
        try {
            File evidence = new File(directory, RootRunSpool.EVIDENCE_FILE);
            RootExecutionRecordStore.writePendingEvidence(
                    evidence,
                    new ExecutionIdentity(4242, 9001, "boot-a", EXECUTABLE, "token-a")
            );

            ExecutionRecordStore.ReadResult read =
                    RootExecutionRecordStore.readEvidenceFile(evidence);
            assertEquals(ExecutionRecordStore.ReadResult.Status.VALID, read.status());
            assertEquals(4242, read.identity().pid());
            assertEquals(EXECUTABLE, read.identity().executablePath());
            assertEquals("token-a", read.identity().runToken());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void pendingStateWithAnUnknownStateLineIsUnresolved() throws IOException {
        File directory = temporaryDirectory();
        try {
            File evidence = new File(directory, RootRunSpool.EVIDENCE_FILE);
            writeText(evidence, "version=2\nstate=delivered\npid=4242\nstart_ticks=9001\n"
                    + "boot_id=boot-a\nexe=" + EXECUTABLE + "\ntoken=token-a\n");

            assertEquals(
                    ExecutionRecordStore.PendingLaunch.Status.UNRESOLVED,
                    RootExecutionRecordStore.readPendingEvidence(evidence).status()
            );
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void truncatedPendingStateIsUnresolved() throws IOException {
        File directory = temporaryDirectory();
        try {
            File evidence = new File(directory, RootRunSpool.EVIDENCE_FILE);
            writeText(evidence, "version=2\nstate=pending\npid=4242\n");

            assertEquals(
                    ExecutionRecordStore.PendingLaunch.Status.UNRESOLVED,
                    RootExecutionRecordStore.readPendingEvidence(evidence).status()
            );
            assertEquals(
                    ExecutionRecordStore.ReadResult.Status.CORRUPT,
                    RootExecutionRecordStore.readEvidenceFile(evidence).status()
            );
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void emptyEvidenceFileCarriesNoPendingState() throws IOException {
        // The run spool pre-creates an empty evidence file, and the launch writes its pending state
        // before the first launch byte. An empty file therefore only means that no transport could
        // have received bytes for this run yet.
        File directory = temporaryDirectory();
        try {
            File evidence = new File(directory, RootRunSpool.EVIDENCE_FILE);
            writeText(evidence, "");

            assertEquals(
                    ExecutionRecordStore.PendingLaunch.Status.NONE,
                    RootExecutionRecordStore.readPendingEvidence(evidence).status()
            );
            assertEquals(
                    ExecutionRecordStore.ReadResult.Status.CORRUPT,
                    RootExecutionRecordStore.readEvidenceFile(evidence).status()
            );
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void preExecEvidenceIsNotPreDeliveryState() throws IOException {
        File directory = temporaryDirectory();
        try {
            File evidence = new File(directory, RootRunSpool.EVIDENCE_FILE);
            new RootExecutionRecordStore(evidence).write(
                    new ExecutionIdentity(4242, 9001, "boot-a", EXECUTABLE, "token-a")
            );

            assertEquals(
                    ExecutionRecordStore.PendingLaunch.Status.NONE,
                    RootExecutionRecordStore.readPendingEvidence(evidence).status()
            );
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void deleteRecursively(File directory) {
        RootRunSpool.deleteDirectory(directory);
    }
}
