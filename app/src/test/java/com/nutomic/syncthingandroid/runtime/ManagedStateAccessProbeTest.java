package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.EnumSet;

/** Verifies app access across the full Managed State manifest, not only config.xml. */
public class ManagedStateAccessProbeTest {

    @Test
    public void readableConfigurationDoesNotMaskAnUnreadableRequiredMember() throws Exception {
        File stateRoot = Files.createTempDirectory("managed-state-access-probe").toFile();
        File config = new File(stateRoot, ManagedStateMember.CONFIG.fileName());
        File certificate = new File(stateRoot, ManagedStateMember.CERT.fileName());
        try {
            ManagedStateStaging.setSymbolicLinkInspectorForTests(
                    file -> Files.isSymbolicLink(file.toPath()));
            assertTrue(config.createNewFile());
            assertTrue(certificate.createNewFile());
            Files.setPosixFilePermissions(certificate.toPath(),
                    Collections.<PosixFilePermission>emptySet());

            assertTrue(config.canRead());
            assertFalse(certificate.canRead());
            assertFalse(ManagedStateAccessProbe.isAccessible(new ManagedStateLocations(
                    stateRoot,
                    new File(stateRoot, "staging"),
                    10_000,
                    10_000
            )));
        } finally {
            ManagedStateStaging.setSymbolicLinkInspectorForTests(null);
            Files.setPosixFilePermissions(certificate.toPath(), EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
            Files.deleteIfExists(certificate.toPath());
            Files.deleteIfExists(config.toPath());
            Files.deleteIfExists(stateRoot.toPath());
        }
    }

    @Test
    public void readableIndexRootDoesNotMaskAnUnreadableDescendant() throws Exception {
        File stateRoot = Files.createTempDirectory("managed-state-index-probe").toFile();
        File config = new File(stateRoot, ManagedStateMember.CONFIG.fileName());
        File index = new File(stateRoot, ManagedStateMember.INDEX.fileName());
        File nested = new File(index, "nested");
        File database = new File(nested, "entry.ldb");
        try {
            ManagedStateStaging.setSymbolicLinkInspectorForTests(
                    file -> Files.isSymbolicLink(file.toPath()));
            assertTrue(config.createNewFile());
            assertTrue(index.mkdir());
            assertTrue(nested.mkdir());
            assertTrue(database.createNewFile());
            assertTrue(ManagedStateAccessProbe.isAccessible(new ManagedStateLocations(
                    stateRoot, new File(stateRoot, "staging"), 10_000, 10_000)));
            Files.setPosixFilePermissions(database.toPath(),
                    Collections.<PosixFilePermission>emptySet());
            assertFalse(ManagedStateAccessProbe.isAccessible(new ManagedStateLocations(
                    stateRoot, new File(stateRoot, "staging"), 10_000, 10_000)));
        } finally {
            ManagedStateStaging.setSymbolicLinkInspectorForTests(null);
            Files.setPosixFilePermissions(database.toPath(), EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.deleteIfExists(database.toPath());
            Files.deleteIfExists(nested.toPath());
            Files.deleteIfExists(index.toPath());
            Files.deleteIfExists(config.toPath());
            Files.deleteIfExists(stateRoot.toPath());
        }
    }
}
