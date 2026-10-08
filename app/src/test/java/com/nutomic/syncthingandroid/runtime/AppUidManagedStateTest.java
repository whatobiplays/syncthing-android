package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import org.junit.After;
import org.junit.Before;

/** Verifies application-UID Managed State import and certificate storage semantics. */
public class AppUidManagedStateTest {

    @Before
    public void useHostNoFollowFileMetadata() {
        ManagedStateTestSupport.useJvmSymbolicLinkInspector();
    }

    @After
    public void clearHostNoFollowFileMetadata() {
        ManagedStateTestSupport.clearJvmSymbolicLinkInspector();
    }

    @Test
    public void repairVerifiesReadAndWriteAccessForRegularFiles() throws Exception {
        File root = Files.createTempDirectory("app-uid-state-repair-file-").toFile();
        ManagedStateLocations locations = locations(root);
        byte[] contents = new byte[] { 0x31, 0x0a, (byte) 0xff };
        try {
            Files.write(locations.member(ManagedStateMember.CONFIG).toPath(), contents);

            new AppUidManagedStateTransfer(locations).repairAppAccess();

            assertArrayEquals(
                    contents,
                    Files.readAllBytes(locations.member(ManagedStateMember.CONFIG).toPath())
            );
        } finally {
            deleteTree(root.toPath());
        }
    }

    @Test
    public void repairFailsWithTypedCauseWhenAFileMemberIsNotARegularFile() throws Exception {
        File root = Files.createTempDirectory("app-uid-state-repair-kind-").toFile();
        ManagedStateLocations locations = locations(root);
        try {
            if (!locations.member(ManagedStateMember.CONFIG).mkdirs()) {
                throw new IOException("Could not create an invalid config member");
            }

            ManagedStateException failure = assertThrows(
                    ManagedStateException.class,
                    () -> new AppUidManagedStateTransfer(locations).repairAppAccess()
            );

            assertEquals(ManagedStateFailure.STATE_REPAIR_FAILED, failure.failure());
        } finally {
            deleteTree(root.toPath());
        }
    }

    @Test
    public void importKeepsAbsentHttpsMembersAndRemovesAnOmittedIndex() throws Exception {
        File root = Files.createTempDirectory("app-uid-state-import-").toFile();
        ManagedStateLocations locations = locations(root);
        ManagedStateStaging staging = locations.newStaging();
        try {
            byte[] oldCertificate = new byte[] { 0x01, 0x02 };
            byte[] oldKey = new byte[] { 0x03, 0x04 };
            Files.write(
                    locations.member(ManagedStateMember.HTTPS_CERT).toPath(), oldCertificate
            );
            Files.write(locations.member(ManagedStateMember.HTTPS_KEY).toPath(), oldKey);
            File oldIndex = new File(locations.member(ManagedStateMember.INDEX), "stale.db");
            if (!oldIndex.getParentFile().mkdirs()) {
                throw new IOException("Could not create the old index tree");
            }
            Files.write(oldIndex.toPath(), new byte[] { 0x05 });
            File unrelated = new File(locations.stateRoot(), "unrelated-state");
            byte[] unrelatedBytes = new byte[] { 0x06, 0x07 };
            Files.write(unrelated.toPath(), unrelatedBytes);

            Files.write(staging.member(ManagedStateMember.CONFIG).toPath(), new byte[] { 0x11 });
            Files.write(staging.member(ManagedStateMember.CERT).toPath(), new byte[] { 0x12 });
            Files.write(staging.member(ManagedStateMember.KEY).toPath(), new byte[] { 0x13 });

            testTransfer(locations).installImportedState(staging);

            assertArrayEquals(
                    new byte[] { 0x11 },
                    Files.readAllBytes(locations.member(ManagedStateMember.CONFIG).toPath())
            );
            assertArrayEquals(
                    oldCertificate,
                    Files.readAllBytes(locations.member(ManagedStateMember.HTTPS_CERT).toPath())
            );
            assertArrayEquals(
                    oldKey,
                    Files.readAllBytes(locations.member(ManagedStateMember.HTTPS_KEY).toPath())
            );
            assertFalse(locations.member(ManagedStateMember.INDEX).exists());
            assertArrayEquals(unrelatedBytes, Files.readAllBytes(unrelated.toPath()));
        } finally {
            staging.close();
            deleteTree(root.toPath());
        }
    }

    @Test
    public void importRejectsADanglingLiveIndexLinkInsteadOfTreatingItAsAbsent()
            throws Exception {
        File root = Files.createTempDirectory("app-uid-state-dangling-index-").toFile();
        ManagedStateLocations locations = locations(root);
        ManagedStateStaging staging = locations.newStaging();
        File config = locations.member(ManagedStateMember.CONFIG);
        File index = locations.member(ManagedStateMember.INDEX);
        try {
            Files.write(staging.member(ManagedStateMember.CONFIG).toPath(), new byte[] { 0x41 });
            Files.write(staging.member(ManagedStateMember.CERT).toPath(), new byte[] { 0x42 });
            Files.write(staging.member(ManagedStateMember.KEY).toPath(), new byte[] { 0x43 });
            Files.createSymbolicLink(index.toPath(), root.toPath().resolve("missing-index-target"));
            assertFalse("a dangling link is not reported by File.exists()", index.exists());

            ManagedStateException failure = org.junit.Assert.assertThrows(
                    ManagedStateException.class,
                    () -> new AppUidManagedStateTransfer(locations).installImportedState(staging)
            );

            assertEquals(ManagedStateFailure.STATE_TRANSFER_FAILED, failure.failure());
            assertFalse("live config must remain unchanged after unsafe preflight", config.exists());
            assertTrue(Files.isSymbolicLink(index.toPath()));
        } finally {
            staging.close();
            deleteTree(root.toPath());
        }
    }

    @Test
    public void appUidBackendsRejectASymlinkedStateRootBeforeReadingItsTarget() throws Exception {
        File root = Files.createTempDirectory("app-uid-state-root-link-").toFile();
        File outside = new File(root, "outside");
        File stateRoot = new File(root, "files");
        File stagingBase = new File(root, "cache");
        assertTrue(outside.mkdir());
        byte[] externalConfig = new byte[] { 0x31 };
        Files.write(new File(outside, "config.xml").toPath(), externalConfig);
        Files.createSymbolicLink(stateRoot.toPath(), outside.toPath());
        ManagedStateLocations locations = new ManagedStateLocations(stateRoot, stagingBase, 10_000, 10_000);
        try {
            ManagedStateException transferFailure = org.junit.Assert.assertThrows(
                    ManagedStateException.class,
                    () -> new AppUidManagedStateTransfer(locations).snapshotForExport()
            );
            ManagedStateException certificateFailure = org.junit.Assert.assertThrows(
                    ManagedStateException.class,
                    () -> new AppUidHttpsCertificateStorage(locations).snapshot()
            );

            assertEquals(ManagedStateFailure.STATE_TRANSFER_FAILED, transferFailure.failure());
            assertEquals(ManagedStateFailure.STATE_ACCESS_FAILED, certificateFailure.failure());
            assertArrayEquals(externalConfig, Files.readAllBytes(
                    new File(outside, "config.xml").toPath()
            ));
            assertFalse(stagingBase.exists());
        } finally {
            deleteTree(root.toPath());
        }
    }

    @Test
    public void importPreflightsEveryLiveTargetBeforeReplacingAnyMember() throws Exception {
        File root = Files.createTempDirectory("app-uid-state-import-preflight-").toFile();
        ManagedStateLocations locations = locations(root);
        ManagedStateStaging staging = locations.newStaging();
        byte[] oldConfig = new byte[] { 0x01 };
        byte[] oldCertificate = new byte[] { 0x02 };
        byte[] plantedTarget = new byte[] { 0x03 };
        try {
            Files.write(locations.member(ManagedStateMember.CONFIG).toPath(), oldConfig);
            Files.write(locations.member(ManagedStateMember.CERT).toPath(), oldCertificate);
            File external = new File(root, "external-key.pem");
            Files.write(external.toPath(), plantedTarget);
            Files.createSymbolicLink(
                    locations.member(ManagedStateMember.KEY).toPath(), external.toPath()
            );
            writeRequiredState(staging);

            ManagedStateException failure = assertThrows(
                    ManagedStateException.class,
                    () -> testTransfer(locations).installImportedState(staging)
            );

            assertEquals(ManagedStateFailure.STATE_TRANSFER_FAILED, failure.failure());
            assertArrayEquals(oldConfig,
                    Files.readAllBytes(locations.member(ManagedStateMember.CONFIG).toPath()));
            assertArrayEquals(oldCertificate,
                    Files.readAllBytes(locations.member(ManagedStateMember.CERT).toPath()));
            assertTrue(Files.isSymbolicLink(locations.member(ManagedStateMember.KEY).toPath()));
            assertArrayEquals(plantedTarget, Files.readAllBytes(external.toPath()));
        } finally {
            staging.close();
            deleteTree(root.toPath());
        }
    }

    @Test
    public void importPreflightsIndexDeletionPermissionsBeforeReplacingMembers() throws Exception {
        File root = Files.createTempDirectory("app-uid-state-index-access-").toFile();
        ManagedStateLocations locations = locations(root);
        ManagedStateStaging staging = locations.newStaging();
        File index = locations.member(ManagedStateMember.INDEX);
        byte[] oldConfig = new byte[] { 0x01 };
        byte[] oldCertificate = new byte[] { 0x02 };
        try {
            Files.write(locations.member(ManagedStateMember.CONFIG).toPath(), oldConfig);
            Files.write(locations.member(ManagedStateMember.CERT).toPath(), oldCertificate);
            if (!index.mkdir()) throw new IOException("Could not create the existing index");
            File oldDatabase = new File(index, "stale.db");
            Files.write(oldDatabase.toPath(), new byte[] { 0x03 });
            writeRequiredState(staging);
            Files.setPosixFilePermissions(index.toPath(), EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_EXECUTE
            ));

            ManagedStateException failure = assertThrows(
                    ManagedStateException.class,
                    () -> testTransfer(locations).installImportedState(staging)
            );

            assertEquals(ManagedStateFailure.STATE_TRANSFER_FAILED, failure.failure());
            assertArrayEquals(oldConfig,
                    Files.readAllBytes(locations.member(ManagedStateMember.CONFIG).toPath()));
            assertArrayEquals(oldCertificate,
                    Files.readAllBytes(locations.member(ManagedStateMember.CERT).toPath()));
            assertArrayEquals(new byte[] { 0x03 }, Files.readAllBytes(oldDatabase.toPath()));
        } finally {
            if (index.exists()) Files.setPosixFilePermissions(index.toPath(), EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            ));
            staging.close();
            deleteTree(root.toPath());
        }
    }

    @Test
    public void importRejectsStickyIndexEntriesTheAppCannotRemoveBeforeReplacingMembers()
            throws Exception {
        File root = Files.createTempDirectory("app-uid-state-sticky-index-").toFile();
        ManagedStateLocations locations = locations(root);
        ManagedStateStaging staging = locations.newStaging();
        File index = locations.member(ManagedStateMember.INDEX);
        File oldDatabase = new File(index, "foreign.db");
        byte[] oldConfig = new byte[] { 0x01 };
        byte[] oldCertificate = new byte[] { 0x02 };
        try {
            Files.write(locations.member(ManagedStateMember.CONFIG).toPath(), oldConfig);
            Files.write(locations.member(ManagedStateMember.CERT).toPath(), oldCertificate);
            if (!index.mkdir()) throw new IOException("Could not create the existing index");
            Files.write(oldDatabase.toPath(), new byte[] { 0x03 });
            Files.setPosixFilePermissions(index.toPath(), EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE
            ));
            writeRequiredState(staging);

            int appUid = locations.applicationUid();
            AppUidManagedStateTransfer transfer = new AppUidManagedStateTransfer(
                    locations,
                    path -> {
                        if (path.equals(index)) {
                            return new AppUidManagedStateTransfer.FileStatus(appUid + 1, 01777);
                        }
                        if (path.equals(oldDatabase)) {
                            return new AppUidManagedStateTransfer.FileStatus(appUid + 2, 0100666);
                        }
                        return new AppUidManagedStateTransfer.FileStatus(appUid, 0700);
                    }
            );

            ManagedStateException failure = assertThrows(
                    ManagedStateException.class,
                    () -> transfer.installImportedState(staging)
            );

            assertEquals(ManagedStateFailure.STATE_TRANSFER_FAILED, failure.failure());
            assertArrayEquals(oldConfig,
                    Files.readAllBytes(locations.member(ManagedStateMember.CONFIG).toPath()));
            assertArrayEquals(oldCertificate,
                    Files.readAllBytes(locations.member(ManagedStateMember.CERT).toPath()));
            assertArrayEquals(new byte[] { 0x03 }, Files.readAllBytes(oldDatabase.toPath()));
        } finally {
            Files.setPosixFilePermissions(index.toPath(), EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            ));
            staging.close();
            deleteTree(root.toPath());
        }
    }

    @Test
    public void replacingIndexPreservesUnrelatedEntriesAndInstallsThePreparedTree() throws Exception {
        File root = Files.createTempDirectory("app-uid-state-index-replace-").toFile();
        ManagedStateLocations locations = locations(root);
        ManagedStateStaging staging = locations.newStaging();
        try {
            writeRequiredState(staging);
            File oldEntry = new File(locations.member(ManagedStateMember.INDEX), "obsolete.db");
            if (!oldEntry.getParentFile().mkdirs()) {
                throw new IOException("Could not create the old index tree");
            }
            Files.write(oldEntry.toPath(), new byte[] { 0x01 });
            File replacementEntry = new File(staging.directory(), "index-v2/active.db");
            if (!replacementEntry.getParentFile().mkdirs()) {
                throw new IOException("Could not create the replacement index tree");
            }
            Files.write(replacementEntry.toPath(), new byte[] { 0x02 });

            testTransfer(locations).installImportedState(staging);

            assertFalse(oldEntry.exists());
            assertArrayEquals(
                    new byte[] { 0x02 },
                    Files.readAllBytes(new File(
                            locations.member(ManagedStateMember.INDEX), "active.db"
                    ).toPath())
            );
        } finally {
            staging.close();
            deleteTree(root.toPath());
        }
    }

    @Test
    public void failedIndexPreparationLeavesTheExistingStateUntouched() throws Exception {
        File root = Files.createTempDirectory("app-uid-state-index-copy-failure-").toFile();
        ManagedStateLocations locations = locations(root);
        ManagedStateStaging staging = locations.newStaging();
        File oldConfig = locations.member(ManagedStateMember.CONFIG);
        File oldCertificate = locations.member(ManagedStateMember.CERT);
        File oldDatabase = new File(locations.member(ManagedStateMember.INDEX), "active.db");
        byte[] oldConfigBytes = new byte[] { 0x01 };
        byte[] oldCertificateBytes = new byte[] { 0x02 };
        byte[] oldDatabaseBytes = new byte[] { 0x03 };
        try {
            Files.write(oldConfig.toPath(), oldConfigBytes);
            Files.write(oldCertificate.toPath(), oldCertificateBytes);
            if (!oldDatabase.getParentFile().mkdirs()) {
                throw new IOException("Could not create the existing index tree");
            }
            Files.write(oldDatabase.toPath(), oldDatabaseBytes);
            writeRequiredState(staging);
            File stagedDatabase = new File(staging.directory(), "index-v2/replacement.db");
            if (!stagedDatabase.getParentFile().mkdirs()) {
                throw new IOException("Could not create the staged index tree");
            }
            Files.write(stagedDatabase.toPath(), new byte[] { 0x04 });

            AppUidManagedStateTransfer transfer = new AppUidManagedStateTransfer(
                    locations,
                    path -> new AppUidManagedStateTransfer.FileStatus(
                            locations.applicationUid(), 0700
                    ),
                    (source, destination) -> {
                        if (!destination.mkdir()) {
                            throw new ManagedStateException(
                                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                                    "Could not create a partial prepared index"
                            );
                        }
                        try {
                            Files.write(new File(destination, "partial.db").toPath(),
                                    new byte[] { 0x05 });
                        } catch (IOException e) {
                            throw new ManagedStateException(
                                    ManagedStateFailure.STATE_TRANSFER_FAILED,
                                    "Could not write a partial prepared index",
                                    e
                            );
                        }
                        throw new ManagedStateException(
                                ManagedStateFailure.STATE_TRANSFER_FAILED,
                                "Simulated failure while preparing the replacement index"
                        );
                    }
            );

            ManagedStateException failure = assertThrows(
                    ManagedStateException.class,
                    () -> transfer.installImportedState(staging)
            );

            assertEquals(ManagedStateFailure.STATE_TRANSFER_FAILED, failure.failure());
            assertArrayEquals(oldConfigBytes, Files.readAllBytes(oldConfig.toPath()));
            assertArrayEquals(oldCertificateBytes, Files.readAllBytes(oldCertificate.toPath()));
            assertArrayEquals(oldDatabaseBytes, Files.readAllBytes(oldDatabase.toPath()));
        } finally {
            staging.close();
            deleteTree(root.toPath());
        }
    }

    @Test
    public void failedIndexDeletionReturnsATypedTransferFailure() throws Exception {
        File root = Files.createTempDirectory("app-uid-state-index-delete-failure-").toFile();
        File undeletable = new File(root, "index-v2") {
            @Override
            public boolean delete() {
                return false;
            }
        };
        Files.write(undeletable.toPath(), new byte[] { 0x01 });
        try {
            Method removeTree = AppUidManagedStateTransfer.class.getDeclaredMethod(
                    "removeTree", File.class
            );
            removeTree.setAccessible(true);
            try {
                removeTree.invoke(null, undeletable);
                throw new AssertionError("a failed index deletion must fail the import");
            } catch (InvocationTargetException invocationFailure) {
                assertTrue(invocationFailure.getCause() instanceof ManagedStateException);
                assertEquals(
                        ManagedStateFailure.STATE_TRANSFER_FAILED,
                        ((ManagedStateException) invocationFailure.getCause()).failure()
                );
            }
        } finally {
            deleteTree(root.toPath());
        }
    }

    @Test
    public void certificateRestorePreservesExactBytesAndPriorAbsence() throws Exception {
        File root = Files.createTempDirectory("app-uid-cert-restore-").toFile();
        ManagedStateLocations locations = locations(root);
        try {
            byte[] previousCertificate = new byte[] { 0x01, 0x0d, 0x0a, (byte) 0xff };
            Files.write(
                    locations.member(ManagedStateMember.HTTPS_CERT).toPath(), previousCertificate
            );
            AppUidHttpsCertificateStorage storage = new AppUidHttpsCertificateStorage(locations);
            HttpsCertificateState previous = storage.snapshot();
            assertTrue(previous.certificatePresent());
            assertFalse(previous.keyPresent());

            storage.replace(new byte[] { 0x21 }, new byte[] { 0x22 });
            storage.restore(previous);

            assertArrayEquals(
                    previousCertificate,
                    Files.readAllBytes(locations.member(ManagedStateMember.HTTPS_CERT).toPath())
            );
            assertFalse(locations.member(ManagedStateMember.HTTPS_KEY).exists());
        } finally {
            deleteTree(root.toPath());
        }
    }

    @Test
    public void certificateSnapshotRejectsANonRegularMemberBeforeOpeningIt() throws Exception {
        File root = Files.createTempDirectory("app-uid-cert-non-regular-").toFile();
        ManagedStateLocations locations = locations(root);
        File certificate = locations.member(ManagedStateMember.HTTPS_CERT);
        try {
            Files.createDirectories(certificate.toPath());

            ManagedStateException failure = org.junit.Assert.assertThrows(
                    ManagedStateException.class,
                    () -> new AppUidHttpsCertificateStorage(locations).snapshot()
            );

            assertEquals(ManagedStateFailure.STATE_ACCESS_FAILED, failure.failure());
            assertTrue(failure.getMessage().contains("not a regular file"));
        } finally {
            deleteTree(root.toPath());
        }
    }

    @Test
    public void replacementAndRestoreKeepThePrivateKeyOwnerOnly() throws Exception {
        File root = Files.createTempDirectory("app-uid-cert-key-permissions-").toFile();
        ManagedStateLocations locations = locations(root);
        File key = locations.member(ManagedStateMember.HTTPS_KEY);
        try {
            Files.write(key.toPath(), new byte[] { 0x01, 0x02 });
            key.setReadable(true, false);
            key.setWritable(true, false);
            AppUidHttpsCertificateStorage storage = new AppUidHttpsCertificateStorage(locations);
            HttpsCertificateState previous = storage.snapshot();

            storage.replace(new byte[] { 0x03 }, new byte[] { 0x04 });
            assertOwnerOnlyKeyPermissions(key);
            storage.restore(previous);

            assertArrayEquals(new byte[] { 0x01, 0x02 }, Files.readAllBytes(key.toPath()));
            assertOwnerOnlyKeyPermissions(key);
        } finally {
            deleteTree(root.toPath());
        }
    }

    @Test
    public void privateKeyPermissionSettingFailureIsReportedAsTypedAccessFailure()
            throws Exception {
        File root = Files.createTempDirectory("app-uid-cert-key-permission-failure-").toFile();
        File key = new File(root, "https-key.pem");
        Files.write(key.toPath(), new byte[] { 0x01 });
        File permissionFailure = new File(key.getPath()) {
            @Override
            public boolean setReadable(boolean readable, boolean ownerOnly) {
                return false;
            }
        };
        try {
            Method restrictToOwner = AppUidHttpsCertificateStorage.class.getDeclaredMethod(
                    "restrictToOwner", File.class
            );
            restrictToOwner.setAccessible(true);
            try {
                restrictToOwner.invoke(null, permissionFailure);
                throw new AssertionError("permission-setting failures must not report success");
            } catch (InvocationTargetException invocationFailure) {
                assertTrue(invocationFailure.getCause() instanceof ManagedStateException);
                assertEquals(
                        ManagedStateFailure.STATE_ACCESS_FAILED,
                        ((ManagedStateException) invocationFailure.getCause()).failure()
                );
            }
        } finally {
            deleteTree(root.toPath());
        }
    }

    @Test
    public void certificateReplacementDoesNotFollowAPlantedTemporarySymlink() throws Exception {
        File root = Files.createTempDirectory("app-uid-cert-symlink-").toFile();
        ManagedStateLocations locations = locations(root);
        try {
            File external = new File(root, "unrelated.bin");
            byte[] externalBytes = new byte[] { 0x31, 0x32 };
            Files.write(external.toPath(), externalBytes);
            Path temporaryLink = new File(root, "https-cert.pem.tmp").toPath();
            Files.createSymbolicLink(temporaryLink, external.toPath());

            new AppUidHttpsCertificateStorage(locations).replace(
                    new byte[] { 0x41 }, new byte[] { 0x42 }
            );

            assertArrayEquals(externalBytes, Files.readAllBytes(external.toPath()));
            assertTrue(Files.isSymbolicLink(temporaryLink));
            assertArrayEquals(
                    new byte[] { 0x41 },
                    Files.readAllBytes(locations.member(ManagedStateMember.HTTPS_CERT).toPath())
            );
        } finally {
            deleteTree(root.toPath());
        }
    }

    @Test
    public void stagingCleanupRemovesOnlyItsOperationDirectory() throws Exception {
        File root = Files.createTempDirectory("app-uid-state-stage-confinement-").toFile();
        ManagedStateLocations locations = locations(root);
        ManagedStateStaging staging = locations.newStaging();
        File sibling = new File(locations.stagingBase(), "op-unrelated");
        File unrelated = new File(sibling, "keep.bin");
        byte[] unrelatedBytes = new byte[] { 0x55, 0x66 };
        try {
            if (!sibling.mkdir()) throw new IOException("Could not create a sibling staging entry");
            Files.write(unrelated.toPath(), unrelatedBytes);
            Files.write(staging.member(ManagedStateMember.CONFIG).toPath(), new byte[] { 0x11 });

            staging.close();

            assertFalse(staging.directory().exists());
            assertArrayEquals(unrelatedBytes, Files.readAllBytes(unrelated.toPath()));
        } finally {
            staging.close();
            deleteTree(root.toPath());
        }
    }

    private static ManagedStateLocations locations(File root) {
        return new ManagedStateLocations(
                root, new File(root, "cache"), 20_001, 20_001
        );
    }

    private static AppUidManagedStateTransfer testTransfer(ManagedStateLocations locations) {
        return new AppUidManagedStateTransfer(
                locations,
                path -> new AppUidManagedStateTransfer.FileStatus(
                        locations.applicationUid(), 0700
                )
        );
    }

    private static void assertOwnerOnlyKeyPermissions(File key) throws IOException {
        Set<PosixFilePermission> expected = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        );
        assertEquals(expected, Files.getPosixFilePermissions(key.toPath()));
    }

    private static void writeRequiredState(ManagedStateStaging staging) throws IOException {
        Files.write(staging.member(ManagedStateMember.CONFIG).toPath(), new byte[] { 0x11 });
        Files.write(staging.member(ManagedStateMember.CERT).toPath(), new byte[] { 0x12 });
        Files.write(staging.member(ManagedStateMember.KEY).toPath(), new byte[] { 0x13 });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
