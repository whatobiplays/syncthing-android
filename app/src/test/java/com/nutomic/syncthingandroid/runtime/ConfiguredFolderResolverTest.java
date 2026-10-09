package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Deterministic tests for the authoritative configured-folder resolution.
 *
 * <p>A privileged folder operation accepts a folder identifier and resolves its path from the
 * authoritative configuration document. The tests drive the document directly: a forged or stale
 * identifier, a malformed document, a document that tries to pull in another file, and a
 * home-relative path without a known home all have to fail instead of resolving something the
 * caller did not ask for.</p>
 */
public class ConfiguredFolderResolverTest {
    private static final String HOME = "/data/user/0/com.nutomic.syncthingandroid/files";

    @Test
    public void resolvesOnlyTheConfiguredFolderWithThatIdentifier() throws Exception {
        byte[] configuration = document(
                "<folder id=\"first\" path=\"/storage/emulated/0/first\"></folder>"
                        + "<folder id=\"second\" path=\"/storage/emulated/0/second\"></folder>"
        );

        assertEquals(
                "/storage/emulated/0/second",
                ConfiguredFolderResolver.resolveFolderPath(configuration, "second", HOME)
        );
    }

    @Test
    public void refusesAForgedOrStaleIdentifier() {
        byte[] configuration = document(
                "<folder id=\"configured\" path=\"/storage/emulated/0/folder\"></folder>"
        );

        FolderOperationException failure = assertThrows(
                FolderOperationException.class,
                () -> ConfiguredFolderResolver.resolveFolderPath(configuration, "forged", HOME)
        );

        assertEquals(FolderOperationFailure.FOLDER_NOT_CONFIGURED, failure.failure());
    }

    @Test
    public void refusesAFolderElementThatIsNotTopLevel() {
        byte[] configuration = document(
                "<defaults><folder id=\"nested\" path=\"/storage/emulated/0/nested\"></folder>"
                        + "</defaults>"
        );

        FolderOperationException failure = assertThrows(
                FolderOperationException.class,
                () -> ConfiguredFolderResolver.resolveFolderPath(configuration, "nested", HOME)
        );

        assertEquals(FolderOperationFailure.FOLDER_NOT_CONFIGURED, failure.failure());
    }

    @Test
    public void refusesAMissingOrMalformedDocument() {
        assertEquals(
                FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                assertThrows(
                        FolderOperationException.class,
                        () -> ConfiguredFolderResolver.resolveFolderPath(null, "folder", HOME)
                ).failure()
        );
        assertEquals(
                FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                assertThrows(
                        FolderOperationException.class,
                        () -> ConfiguredFolderResolver.resolveFolderPath(
                                new byte[0],
                                "folder",
                                HOME
                        )
                ).failure()
        );
        assertEquals(
                FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                assertThrows(
                        FolderOperationException.class,
                        () -> ConfiguredFolderResolver.resolveFolderPath(
                                "not a document".getBytes(StandardCharsets.UTF_8),
                                "folder",
                                HOME
                        )
                ).failure()
        );
    }

    @Test
    public void refusesADocumentThatReferencesAnExternalEntity() {
        String forged = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE configuration [<!ENTITY stolen SYSTEM \"file:///etc/hostname\">]>"
                + "<configuration><folder id=\"folder\" path=\"&stolen;\"></folder></configuration>";

        FolderOperationException failure = assertThrows(
                FolderOperationException.class,
                () -> ConfiguredFolderResolver.resolveFolderPath(
                        forged.getBytes(StandardCharsets.UTF_8),
                        "folder",
                        HOME
                )
        );

        assertEquals(FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE, failure.failure());
    }

    @Test
    public void expandsAHomeRelativePathWithTheConfiguredHome() throws Exception {
        byte[] configuration = document(
                "<folder id=\"folder\" path=\"~/folder\"></folder>"
        );

        assertEquals(
                HOME + "/folder",
                ConfiguredFolderResolver.resolveFolderPath(configuration, "folder", HOME)
        );
    }

    @Test
    public void expandsABareHomeReferenceToTheConfiguredHome() throws Exception {
        byte[] configuration = document("<folder id=\"folder\" path=\"~\"></folder>");

        assertEquals(
                HOME,
                ConfiguredFolderResolver.resolveFolderPath(configuration, "folder", HOME)
        );
    }

    @Test
    public void refusesATildePrefixThatIsNotAHomeReference() {
        // Syncthing expands only "~" and "~/...". Any other leading tilde stays a relative
        // path, so refusing it keeps the operation from aiming at "<home>archive" instead.
        byte[] configuration = document("<folder id=\"folder\" path=\"~archive\"></folder>");

        assertEquals(
                FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                assertThrows(
                        FolderOperationException.class,
                        () -> ConfiguredFolderResolver.resolveFolderPath(
                                configuration,
                                "folder",
                                HOME
                        )
                ).failure()
        );
    }

    @Test
    public void refusesAHomeRelativePathWhenNoAbsoluteHomeIsKnown() {
        byte[] configuration = document(
                "<folder id=\"folder\" path=\"~/folder\"></folder>"
        );

        assertEquals(
                FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                assertThrows(
                        FolderOperationException.class,
                        () -> ConfiguredFolderResolver.resolveFolderPath(configuration, "folder", null)
                ).failure()
        );
        assertEquals(
                FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                assertThrows(
                        FolderOperationException.class,
                        () -> ConfiguredFolderResolver.resolveFolderPath(
                                configuration,
                                "folder",
                                "relative/home"
                        )
                ).failure()
        );
    }

    @Test
    public void refusesARelativeOrEmptyConfiguredPath() {
        assertEquals(
                FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                assertThrows(
                        FolderOperationException.class,
                        () -> ConfiguredFolderResolver.resolveFolderPath(
                                document("<folder id=\"folder\" path=\"relative/folder\"></folder>"),
                                "folder",
                                HOME
                        )
                ).failure()
        );
        assertEquals(
                FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                assertThrows(
                        FolderOperationException.class,
                        () -> ConfiguredFolderResolver.resolveFolderPath(
                                document("<folder id=\"folder\"></folder>"),
                                "folder",
                                HOME
                        )
                ).failure()
        );
    }

    /** Wraps folder elements in the document element the configuration uses. */
    private static byte[] document(String folders) {
        return ("<configuration>" + folders + "</configuration>")
                .getBytes(StandardCharsets.UTF_8);
    }
}
