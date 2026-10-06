package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Covers the app-owned per-run spool and the reconciliation of output that accumulated while no
 * application process was reading.
 *
 * <p>Every case builds real spool directories in a temporary directory, so file ownership, command
 * attribution, and one-shot versus long-running output handling are exercised on the real code
 * paths the launching and recovery flows use.</p>
 */
public class RootRunSpoolTest {

    @Test
    public void createdSpoolPrecreatesAppOwnedFilesForTheRun() throws IOException {
        File root = temporaryDirectory();
        try {
            RootRunSpool spool = RootRunSpool.create(root, "token-a", SyncthingCommand.SERVE.name());

            assertTrue(spool.evidenceFile().isFile());
            assertTrue(spool.outputFile().isFile());
            assertTrue(spool.commandFile().isFile());
            assertEquals(0, spool.evidenceFile().length());
            assertEquals(0, spool.outputFile().length());
            assertEquals(SyncthingCommand.SERVE.name(), RootRunSpool.readCommandName(spool.directory()));
            assertEquals(1, RootRunSpool.listRunDirectories(root).size());
            assertEquals("token-a", spool.runToken());
            assertFalse(RootRunSpool.hasOutput(spool.directory()));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void plannedSpoolStaysInvisibleUntilItIsMaterialized() throws IOException {
        File root = temporaryDirectory();
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool spool = RootRunSpool.plan(
                    spoolRoot, "token-a", SyncthingCommand.SERVE.name()
            );

            // Preparation only plans the run: nothing exists on disk that a concurrent recovery
            // could classify as an attributed leftover run while root is still being acquired.
            assertEquals("token-a", spool.runToken());
            assertEquals(SyncthingCommand.SERVE.name(), spool.commandName());
            assertFalse(spool.directory().exists());
            assertFalse(spool.evidenceFile().exists());
            assertFalse(spool.outputFile().exists());
            assertTrue(RootRunSpool.listRunDirectories(spoolRoot).isEmpty());

            spool.materialize(new ExecutionIdentity(
                    7, 11, "boot-a", "/data/app/lib/libsyncthingnative.so", "token-a"
            ));

            assertTrue(spool.directory().isDirectory());
            assertTrue(spool.evidenceFile().isFile());
            assertTrue(spool.evidenceStagingFile().isFile());
            assertTrue(spool.outputFile().isFile());
            assertTrue(spool.commandFile().isFile());
            assertEquals(
                    ExecutionRecordStore.PendingLaunch.Status.PENDING,
                    RootSpoolEvidence.readPendingLaunch(spool.directory()).status()
            );
            assertEquals("0", readText(spool.consumedFile()));
            assertEquals(SyncthingCommand.SERVE.name(), RootRunSpool.readCommandName(spool.directory()));
            assertEquals(1, RootRunSpool.listRunDirectories(spoolRoot).size());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void outputTailStreamsDataAndEndsOnlyAfterExit() throws IOException {
        File root = temporaryDirectory();
        try {
            RootRunSpool spool = RootRunSpool.create(root, "token-a", SyncthingCommand.SERVE.name());
            FakeRootTransport.Device device = new FakeRootTransport.Device();
            FakeRootTransport.Shell shell = new FakeRootTransport.Shell(device, 0);
            shell.adopt(device.addLaunchedProcess("/data/app/lib/libsyncthingnative.so", "token-a"));

            InputStream tail = spool.openOutputTail(shell);
            appendText(spool.outputFile(), "first line\n");
            assertEquals("first line\n", readExact(tail, "first line\n".length()));

            appendText(spool.outputFile(), "second line\n");
            assertEquals("second line\n", readExact(tail, "second line\n".length()));

            device.onlyLiveProcess().exit(0);
            assertEquals(-1, tail.read());
            tail.close();
            assertTrue(RootRunSpool.hasOutput(spool.directory()));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void reconciliationAppendsLeftoverServeOutputToSharedLog() throws IOException {
        File root = temporaryDirectory();
        File log = new File(root, "syncthing.log");
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool leftover = RootRunSpool.create(
                    spoolRoot, "token-a", SyncthingCommand.SERVE.name()
            );
            appendText(leftover.outputFile(), "output while the app was gone\n");
            RootRunSpoolReconciler reconciler = new RootRunSpoolReconciler(
                    spoolRoot, log, root
            );

            int reconciled = reconciler.reconcile(null);

            assertEquals(1, reconciled);
            assertEquals(
                    "output while the app was gone\n",
                    new String(java.nio.file.Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8)
            );
            assertFalse(leftover.directory().exists());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void reconciliationDiscardsOneShotOutput() throws IOException {
        File root = temporaryDirectory();
        File log = new File(root, "syncthing.log");
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool leftover = RootRunSpool.create(
                    spoolRoot, "token-a", SyncthingCommand.DEVICE_ID.name()
            );
            appendText(leftover.outputFile(), "device id output\n");
            RootRunSpoolReconciler reconciler = new RootRunSpoolReconciler(spoolRoot, log, root);

            assertEquals(1, reconciler.reconcile(null));

            assertFalse(leftover.directory().exists());
            assertFalse("one-shot output must not pollute the shared log", log.exists());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void reconciliationAppendsLeftoverDeltaResetOutputToSharedLog() throws IOException {
        File root = temporaryDirectory();
        File log = new File(root, "syncthing.log");
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool leftover = RootRunSpool.create(
                    spoolRoot, "token-a", SyncthingCommand.RESET_DELTAS.name()
            );
            RootServeLogWriter writer = RootServeLogWriter.forRunDirectory(
                    leftover.directory(), log, root
            );
            appendText(leftover.outputFile(), "delta reset while the app was alive\n");
            writer.appendPendingOutput();

            appendText(leftover.outputFile(), "delta reset while the app was gone\n");
            RootRunSpoolReconciler reconciler = new RootRunSpoolReconciler(spoolRoot, log, root);

            assertEquals(1, reconciler.reconcile(null));

            assertEquals(
                    "the delta-reset run is serve-style: only its unconsumed bytes are appended",
                    "delta reset while the app was alive\n"
                            + "delta reset while the app was gone\n",
                    readText(log)
            );
            assertFalse(leftover.directory().exists());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void unknownCommandNameWithOutputIsRetained() throws IOException {
        File root = temporaryDirectory();
        File log = new File(root, "syncthing.log");
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool leftover = RootRunSpool.create(
                    spoolRoot, "token-a", SyncthingCommand.SERVE.name()
            );
            appendText(leftover.outputFile(), "output of an unattributable run\n");
            writeText(leftover.commandFile(), "serve --debug-reset-delta-idxs");
            RootRunSpoolReconciler reconciler = new RootRunSpoolReconciler(spoolRoot, log, root);

            assertEquals(0, reconciler.reconcile(null));

            assertTrue(
                    "a command outside the closed vocabulary has no defined output policy",
                    leftover.directory().exists()
            );
            assertFalse("nothing may be appended on a guessed policy", log.exists());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void unknownCommandNameWithEvidenceIsRetained() throws IOException {
        File root = temporaryDirectory();
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool leftover = RootRunSpool.create(
                    spoolRoot, "token-a", SyncthingCommand.DEVICE_ID.name()
            );
            writeText(leftover.commandFile(), "device");
            appendText(leftover.evidenceFile(), "version=1\npid=17\n");
            RootRunSpoolReconciler reconciler = new RootRunSpoolReconciler(
                    spoolRoot, new File(root, "syncthing.log"), root
            );

            assertEquals(0, reconciler.reconcile(null));

            assertTrue(
                    "recorded evidence is never destroyed with an unattributable run",
                    leftover.directory().exists()
            );
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void unattributableLeftoverOutputIsRetained() throws IOException {
        File root = temporaryDirectory();
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool leftover = RootRunSpool.create(
                    spoolRoot, "token-a", SyncthingCommand.SERVE.name()
            );
            appendText(leftover.outputFile(), "unattributable output\n");
            assertTrue(leftover.commandFile().delete());
            RootRunSpoolReconciler reconciler = new RootRunSpoolReconciler(
                    spoolRoot, new File(root, "syncthing.log"), root
            );

            int reconciled = reconciler.reconcile(null);

            assertEquals(0, reconciled);
            assertTrue("evidence with output is never destroyed", leftover.directory().exists());
            assertNull(RootRunSpool.readCommandName(leftover.directory()));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void emptyLeftoverRunIsRemovedWithoutLogOutput() throws IOException {
        File root = temporaryDirectory();
        File log = new File(root, "syncthing.log");
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool leftover = RootRunSpool.create(
                    spoolRoot, "token-a", SyncthingCommand.SERVE.name()
            );
            RootRunSpoolReconciler reconciler = new RootRunSpoolReconciler(spoolRoot, log, root);

            assertEquals(1, reconciler.reconcile(null));

            assertFalse(leftover.directory().exists());
            assertFalse(log.exists());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void activeRunIsNeverReconciled() throws IOException {
        File root = temporaryDirectory();
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool active = RootRunSpool.create(
                    spoolRoot, "token-active", SyncthingCommand.SERVE.name()
            );
            appendText(active.outputFile(), "live output\n");
            RootRunSpoolReconciler reconciler = new RootRunSpoolReconciler(
                    spoolRoot, new File(root, "syncthing.log"), root
            );

            assertEquals(0, reconciler.reconcile(active));

            assertTrue(active.directory().exists());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void reconciliationResumesAtTheRecordedConsumptionOffset() throws IOException {
        File root = temporaryDirectory();
        File log = new File(root, "syncthing.log");
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool leftover = RootRunSpool.create(
                    spoolRoot, "token-a", SyncthingCommand.SERVE.name()
            );
            RootServeLogWriter writer = RootServeLogWriter.forRunDirectory(
                    leftover.directory(), log, root
            );
            appendText(leftover.outputFile(), "output while the app was alive\n");
            writer.appendPendingOutput();
            assertEquals("output while the app was alive\n", readText(log));

            appendText(leftover.outputFile(), "output while the app was gone\n");
            RootRunSpoolReconciler reconciler = new RootRunSpoolReconciler(spoolRoot, log, root);

            assertEquals(1, reconciler.reconcile(null));

            assertEquals(
                    "only the output that never reached the log may be appended",
                    "output while the app was alive\noutput while the app was gone\n",
                    readText(log)
            );
            assertFalse(leftover.directory().exists());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void reconciliationOfAFullyConsumedLeftoverNeverReplaysHistory() throws IOException {
        File root = temporaryDirectory();
        File log = new File(root, "syncthing.log");
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool leftover = RootRunSpool.create(
                    spoolRoot, "token-a", SyncthingCommand.SERVE.name()
            );
            appendText(leftover.outputFile(), "already reconciled output\n");
            RootServeLogWriter writer = RootServeLogWriter.forRunDirectory(
                    leftover.directory(), log, root
            );
            writer.appendPendingOutput();
            RootRunSpoolReconciler reconciler = new RootRunSpoolReconciler(spoolRoot, log, root);

            // Models a leftover spool that survived an earlier reconciliation, for example
            // because deleting it failed while its output had already reached the log.
            assertEquals(1, reconciler.reconcile(null));

            assertEquals("already reconciled output\n", readText(log));
            assertEquals(
                    "a second reconciliation attempt resumes at the durable offset",
                    0,
                    writer.appendPendingOutput()
            );
            assertEquals("already reconciled output\n", readText(log));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void duplicationAfterACrashIsBoundedToTheChunkThatWasInFlight() throws IOException {
        File root = temporaryDirectory();
        File log = new File(root, "syncthing.log");
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool leftover = RootRunSpool.create(
                    spoolRoot, "token-a", SyncthingCommand.SERVE.name()
            );
            byte[] history = new byte[RootServeLogWriter.CHUNK_BYTES * 3];
            java.util.Arrays.fill(history, (byte) 'x');
            try (FileOutputStream output = new FileOutputStream(leftover.outputFile())) {
                output.write(history);
            }
            // Models a crash between appending the first chunk and durably recording the offset:
            // the log holds that chunk while the recorded offset still says nothing was consumed.
            try (FileOutputStream output = new FileOutputStream(log)) {
                output.write(history, 0, RootServeLogWriter.CHUNK_BYTES);
            }
            RootRunSpoolReconciler reconciler = new RootRunSpoolReconciler(spoolRoot, log, root);

            assertEquals(1, reconciler.reconcile(null));

            assertEquals(
                    "a crash may duplicate at most the chunk that was in flight",
                    history.length + RootServeLogWriter.CHUNK_BYTES,
                    log.length()
            );
        } finally {
            deleteRecursively(root);
        }
    }

    /** The durable pre-delivery state is readable from the run directory before any launch byte. */
    @Test
    public void recordedPendingLaunchStateIsReadableFromItsRunDirectory() throws IOException {
        File root = temporaryDirectory();
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool spool = RootRunSpool.create(
                    spoolRoot, "token-a", SyncthingCommand.DEVICE_ID.name()
            );
            assertEquals(
                    ExecutionRecordStore.PendingLaunch.Status.NONE,
                    RootSpoolEvidence.readPendingLaunch(spool.directory()).status()
            );

            spool.materialize(new ExecutionIdentity(
                    4242, 9001, "boot-a", "/data/app/lib/libsyncthingnative.so", "token-a"
            ));

            ExecutionRecordStore.PendingLaunch pending =
                    RootSpoolEvidence.readPendingLaunch(spool.directory());
            assertEquals(ExecutionRecordStore.PendingLaunch.Status.PENDING, pending.status());
            assertEquals(4242, pending.transportIdentity().pid());
            assertEquals(9001, pending.transportIdentity().processStartTimeTicks());
            assertEquals("boot-a", pending.transportIdentity().bootId());
            assertEquals(
                    "/data/app/lib/libsyncthingnative.so",
                    pending.transportIdentity().executablePath()
            );
            assertEquals("token-a", pending.transportIdentity().runToken());
        } finally {
            deleteRecursively(root);
        }
    }

    /** A spool-wide scan reports the newest pending transport and fails closed on damaged state. */
    @Test
    public void spoolWideScanReportsTheNewestPendingTransportAndUnreadableState() throws IOException {
        File root = temporaryDirectory();
        try {
            File spoolRoot = new File(root, "runs");
            RootRunSpool older = RootRunSpool.create(
                    spoolRoot, "token-old", SyncthingCommand.SERVE.name()
            );
            RootRunSpool newer = RootRunSpool.create(
                    spoolRoot, "token-new", SyncthingCommand.DEVICE_ID.name()
            );
            older.materialize(new ExecutionIdentity(
                    1, 2, "boot-a", "/data/app/lib/libsyncthingnative.so", "token-old"
            ));
            newer.materialize(new ExecutionIdentity(
                    3, 4, "boot-a", "/data/app/lib/libsyncthingnative.so", "token-new"
            ));
            assertTrue(older.evidenceFile().setLastModified(1_000_000L));
            assertTrue(newer.evidenceFile().setLastModified(2_000_000L));

            RootSpoolEvidence evidence = new RootSpoolEvidence(spoolRoot);
            assertEquals("token-new", evidence.readPendingLaunch().transportIdentity().runToken());

            // A damaged pending record outranks every readable one, because it can never prove that
            // no transport is in flight.
            RootRunSpool damaged = RootRunSpool.create(
                    spoolRoot, "token-damaged", SyncthingCommand.SERVE.name()
            );
            appendText(damaged.evidenceFile(), "version=2\nstate=pending\npid=5\n");

            assertEquals(
                    ExecutionRecordStore.PendingLaunch.Status.UNRESOLVED,
                    evidence.readPendingLaunch().status()
            );
        } finally {
            deleteRecursively(root);
        }
    }

    private static String readExact(InputStream input, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(buffer, offset, length - offset);
            if (count < 0) {
                throw new AssertionError("The spool stream ended before " + length + " bytes");
            }
            offset += count;
        }
        return new String(buffer, StandardCharsets.UTF_8);
    }

    private static void appendText(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readText(File file) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File temporaryDirectory() throws IOException {
        File directory = File.createTempFile("root-spool", "");
        if (!directory.delete() || !directory.mkdir()) {
            throw new IOException("Could not prepare a temporary directory");
        }
        return directory;
    }

    private static void deleteRecursively(File directory) {
        RootRunSpool.deleteDirectory(directory);
    }
}
