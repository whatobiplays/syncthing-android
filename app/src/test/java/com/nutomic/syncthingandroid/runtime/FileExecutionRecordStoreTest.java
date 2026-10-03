package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileExecutionRecordStoreTest {
    private static final ExecutionIdentity IDENTITY = new ExecutionIdentity(
            41, 9001, "boot-a", "/data/app/lib/libsyncthingnative.so", "run-a"
    );

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void versionedRecordRoundTripsEveryOwnershipField() throws Exception {
        FileExecutionRecordStore store = new FileExecutionRecordStore(
                new File(temporaryFolder.getRoot(), "execution.bin")
        );

        store.write(IDENTITY);
        ExecutionRecordStore.ReadResult result = store.read();

        assertEquals(ExecutionRecordStore.ReadResult.Status.VALID, result.status());
        assertEquals(41, result.identity().pid());
        assertEquals(9001, result.identity().processStartTimeTicks());
        assertEquals("boot-a", result.identity().bootId());
        assertEquals(IDENTITY.executablePath(), result.identity().executablePath());
        assertEquals("run-a", result.identity().runToken());
    }

    @Test
    public void truncatedRecordIsClassifiedAsCorrupt() throws Exception {
        File record = new File(temporaryFolder.getRoot(), "execution.bin");
        FileExecutionRecordStore store = new FileExecutionRecordStore(record);
        store.write(IDENTITY);
        try (FileOutputStream output = new FileOutputStream(record)) {
            output.write(new byte[]{1, 2, 3});
        }

        assertEquals(ExecutionRecordStore.ReadResult.Status.CORRUPT, store.read().status());
    }

    @Test
    public void trailingBytesAfterValidRecordAreClassifiedAsCorrupt() throws Exception {
        File record = new File(temporaryFolder.getRoot(), "execution.bin");
        FileExecutionRecordStore store = new FileExecutionRecordStore(record);
        store.write(IDENTITY);
        try (FileOutputStream output = new FileOutputStream(record, true)) {
            output.write(0x7f);
        }

        assertEquals(ExecutionRecordStore.ReadResult.Status.CORRUPT, store.read().status());
    }

    @Test
    public void emptyRunTokenIsClassifiedAsCorrupt() throws Exception {
        File record = new File(temporaryFolder.getRoot(), "execution.bin");
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(record))) {
            output.writeInt(0x53544558);
            output.writeInt(1);
            output.writeInt(41);
            output.writeLong(9001);
            output.writeUTF("boot-a");
            output.writeUTF(IDENTITY.executablePath());
            output.writeUTF("");
        }

        assertEquals(ExecutionRecordStore.ReadResult.Status.CORRUPT,
                new FileExecutionRecordStore(record).read().status());
    }

    @Test
    public void filesystemReadFailureIsNotClassifiedAsCorruption() throws Exception {
        File unreadableRecord = temporaryFolder.newFolder("execution.bin");

        assertEquals(ExecutionRecordStore.ReadResult.Status.READ_FAILED,
                new FileExecutionRecordStore(unreadableRecord).read().status());
    }

    @Test
    public void unsupportedRecordVersionIsRetainedAsTypedEvidence() throws Exception {
        File record = new File(temporaryFolder.getRoot(), "execution.bin");
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(record))) {
            output.writeInt(0x53544558);
            output.writeInt(99);
        }
        FileExecutionRecordStore store = new FileExecutionRecordStore(record);

        assertEquals(
                ExecutionRecordStore.ReadResult.Status.UNSUPPORTED_VERSION,
                store.read().status()
        );
        assertTrue(record.exists());
    }

    @Test
    public void deletionRequiresTheMatchingRunToken() throws Exception {
        FileExecutionRecordStore store = new FileExecutionRecordStore(
                new File(temporaryFolder.getRoot(), "execution.bin")
        );
        store.write(IDENTITY);

        assertFalse(store.deleteIfRunTokenMatches("run-older"));
        assertEquals("run-a", store.read().identity().runToken());
        assertTrue(store.deleteIfRunTokenMatches("run-a"));
        assertEquals(ExecutionRecordStore.ReadResult.Status.MISSING, store.read().status());
    }
}
