package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.runtime.ManagedStateLocations;
import com.nutomic.syncthingandroid.runtime.ManagedStateStaging;
import com.nutomic.syncthingandroid.runtime.ManagedStateTestSupport;
import com.nutomic.syncthingandroid.runtime.RootBackendProductionScriptTestSupport;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionMethod;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;

/** Exercises the backup archive boundary before staged content can reach live Managed State. */
public class SyncthingBackupArchiveTest {
    @Before
    public void useHostNoFollowFileMetadata() {
        ManagedStateTestSupport.useJvmSymbolicLinkInspector();
    }

    @After
    public void clearHostNoFollowFileMetadata() {
        ManagedStateTestSupport.clearJvmSymbolicLinkInspector();
    }

    @Test
    public void extractsOnlyApprovedArchiveEntriesIntoOperationStaging() throws Exception {
        File root = Files.createTempDirectory("syncthing-backup-safe-").toFile();
        ManagedStateStaging staging = locations(root).newStaging();
        File archivePath = new File(root, "backup.zip");
        ZipFile archive = createArchive(archivePath,
                entry("config.xml", bytes("config")),
                entry("cert.pem", bytes("cert")),
                entry("key.pem", bytes("key")),
                entry("index-v2/folder/db", bytes("index")),
                entry("sharedpreferences.dat", serializedPreferences())
        );

        try {
            Map<?, ?> importedPreferences = SyncthingBackupArchive.extract(archive, staging);
            assertEquals("value", importedPreferences.get("preference"));
            assertEquals(Boolean.TRUE, importedPreferences.get("booleanPreference"));
            assertEquals(Integer.valueOf(7), importedPreferences.get("integerPreference"));
            assertEquals(Float.valueOf(1.5f), importedPreferences.get("floatPreference"));
            assertEquals(Long.valueOf(9L), importedPreferences.get("longPreference"));
            assertEquals(java.util.Collections.singleton("set-member"),
                    importedPreferences.get("setPreference"));

            assertArrayEquals(bytes("config"), Files.readAllBytes(staging.member(
                    com.nutomic.syncthingandroid.runtime.ManagedStateMember.CONFIG
            ).toPath()));
            assertArrayEquals(bytes("index"), Files.readAllBytes(
                    new File(staging.directory(), "index-v2/folder/db").toPath()
            ));
            assertTrue(new File(staging.directory(), "sharedpreferences.dat").isFile());
        } finally {
            staging.close();
        }
    }

    @Test
    public void extractionAllowsAbsentOptionalHttpsAndSharedPreferencesFiles() throws Exception {
        File root = Files.createTempDirectory("syncthing-backup-optional-state-").toFile();
        ManagedStateStaging staging = locations(root).newStaging();
        ZipFile archive = createArchive(new File(root, "backup.zip"),
                entry("config.xml", bytes("config")),
                entry("cert.pem", bytes("cert")),
                entry("key.pem", bytes("key"))
        );
        try {
            assertNull(SyncthingBackupArchive.extract(archive, staging));

            assertFalse(new File(staging.directory(), "https-cert.pem").exists());
            assertFalse(new File(staging.directory(), "https-key.pem").exists());
            assertFalse(new File(staging.directory(), "sharedpreferences.dat").exists());
        } finally {
            staging.close();
        }
    }

    @Test
    public void extractionRejectsMalformedSharedPreferencesBeforeReturningStaging()
            throws Exception {
        File root = Files.createTempDirectory("syncthing-backup-malformed-prefs-").toFile();
        ManagedStateStaging staging = locations(root).newStaging();
        ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        try (ObjectOutputStream preferences = new ObjectOutputStream(serialized)) {
            preferences.writeObject("not a preferences map");
        }
        ZipFile archive = createArchive(new File(root, "backup.zip"),
                entry("config.xml", bytes("config")),
                entry("cert.pem", bytes("cert")),
                entry("key.pem", bytes("key")),
                entry("sharedpreferences.dat", serialized.toByteArray())
        );
        try {
            assertThrows(IOException.class, () -> SyncthingBackupArchive.extract(archive, staging));
        } finally {
            staging.close();
        }
    }

    @Test
    public void rejectsUnapprovedSerializedPreferenceClassesBeforeRunningTheirHooks()
            throws Exception {
        File root = Files.createTempDirectory("syncthing-backup-preferences-filter-").toFile();
        File preferencesFile = new File(root, "sharedpreferences.dat");
        DeserializationProbe.readHookInvoked = false;
        ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        Map<String, Object> preferences = new java.util.HashMap<>();
        preferences.put("preference", new DeserializationProbe());
        try (ObjectOutputStream output = new ObjectOutputStream(serialized)) {
            output.writeObject(preferences);
        }
        Files.write(preferencesFile.toPath(), serialized.toByteArray());

        assertThrows(IOException.class,
                () -> SyncthingBackupArchive.readSharedPreferencesMap(preferencesFile));
        assertFalse("a class rejected by the archive boundary must not run readObject",
                DeserializationProbe.readHookInvoked);
    }

    @Test
    public void writesEstablishedArchiveEntriesWithAndWithoutPasswordEncryption() throws Exception {
        File root = Files.createTempDirectory("syncthing-backup-export-").toFile();
        ManagedStateStaging staging = locations(root).newStaging();
        try {
            Files.write(staging.member(
                    com.nutomic.syncthingandroid.runtime.ManagedStateMember.CONFIG
            ).toPath(), bytes("config"));
            Files.write(staging.member(
                    com.nutomic.syncthingandroid.runtime.ManagedStateMember.CERT
            ).toPath(), bytes("cert"));
            Files.write(staging.member(
                    com.nutomic.syncthingandroid.runtime.ManagedStateMember.KEY
            ).toPath(), bytes("key"));
            Files.write(
                    new File(staging.directory(), "https-cert.pem").toPath(), bytes("https cert")
            );
            Files.write(
                    new File(staging.directory(), "https-key.pem").toPath(), bytes("https key")
            );
            Files.write(
                    new File(staging.directory(), "sharedpreferences.dat").toPath(), bytes("prefs")
            );
            File indexEntry = new File(staging.directory(), "index-v2/folder/db");
            if (!indexEntry.getParentFile().mkdirs()) {
                throw new IOException("Could not create the staged index directory");
            }
            Files.write(indexEntry.toPath(), bytes("index"));

            File encryptedPath = new File(root, "encrypted.zip");
            SyncthingBackupArchive.write(encryptedPath, staging, "backup-secret");
            ZipFile encrypted = new ZipFile(encryptedPath, "backup-secret".toCharArray());
            assertTrue(encrypted.isEncrypted());
            assertArchiveContainsEstablishedEntries(encrypted);

            File plainPath = new File(root, "plain.zip");
            SyncthingBackupArchive.write(plainPath, staging, "");
            ZipFile plain = new ZipFile(plainPath);
            assertFalse(plain.isEncrypted());
            assertArchiveContainsEstablishedEntries(plain);
        } finally {
            staging.close();
        }
    }

    @Test
    public void productionRootBackendAcceptsArchivePreferencesButInstallsOnlyManagedState()
            throws Exception {
        File root = Files.createTempDirectory("syncthing-root-backup-import-").toFile();
        ManagedStateLocations locations = locations(root);

        for (boolean includePreferences : new boolean[] {true, false}) {
            ManagedStateStaging exported = locations.newStaging();
            ManagedStateStaging imported = locations.newStaging();
            File archive = new File(root, includePreferences ? "with-prefs.zip" : "without-prefs.zip");
            try {
                writeRequiredState(exported, "configuration");
                Files.write(new File(exported.directory(), "https-cert.pem").toPath(), bytes("https cert"));
                Files.write(new File(exported.directory(), "https-key.pem").toPath(), bytes("https key"));
                File index = new File(exported.directory(), "index-v2/folder");
                assertTrue(index.mkdirs());
                Files.write(new File(index, "db").toPath(), bytes("index"));
                if (includePreferences) {
                    Files.write(
                            new File(exported.directory(), "sharedpreferences.dat").toPath(),
                            serializedPreferences()
                    );
                }

                SyncthingBackupArchive.write(archive, exported, "");
                Map<?, ?> preferences = SyncthingBackupArchive.extract(
                        new ZipFile(archive), imported
                );
                if (includePreferences) {
                    assertEquals("value", preferences.get("preference"));
                    assertTrue(new File(imported.directory(), "sharedpreferences.dat").isFile());
                } else {
                    assertNull(preferences);
                    assertFalse(new File(imported.directory(), "sharedpreferences.dat").exists());
                }

                String script = RootBackendProductionScriptTestSupport.installImportedState(
                        imported, locations
                );
                assertTrue("state jobs run inside a subshell owned by libsu",
                        script.startsWith("(\n") && script.endsWith("\n)"));
                assertTrue(script.contains("! -name sharedpreferences.dat"));
                assertTrue(script.contains(
                        "if [ -e \"$standroid_stage/sharedpreferences.dat\" ]"
                ));
                assertTrue(script.contains("[ -f \"$standroid_stage/sharedpreferences.dat\" ]"));
                assertTrue(script.contains("[ ! -L \"$standroid_stage/sharedpreferences.dat\" ]"));
                assertTrue(script.contains("! -user " + locations.applicationUid()));
                assertTrue(script.contains("! -group " + locations.applicationGid()));
                assertFalse(script.contains("$standroid_state/sharedpreferences.dat"));
                assertTrue(script.contains(
                        "for standroid_member in config.xml cert.pem key.pem https-cert.pem https-key.pem; do"
                ));
                assertTrue(script.contains(
                        "standroid_install_temp=\"$standroid_work/.install-$standroid_member\""
                ));
                assertFalse(script.contains("\"$standroid_stage/.install-"));
                assertTrue(script.contains(
                        "cp -R \"$standroid_index\" \"$standroid_index_work\""
                ));

                int liveFilePreflight = script.indexOf(
                        "for standroid_live_member in config.xml cert.pem key.pem "
                                + "https-cert.pem https-key.pem; do"
                );
                int liveFilePreflightEnd = script.indexOf("done", liveFilePreflight);
                int liveIndexPreflight = script.indexOf(
                        "standroid_live_index=\"$standroid_state/index-v2\""
                );
                int firstLiveWrite = script.indexOf(
                        "mv -f \"$standroid_install_temp\""
                );
                int preparedIndexCopy = script.indexOf(
                        "cp -R \"$standroid_index\" \"$standroid_index_work\""
                );
                assertTrue(liveFilePreflight >= 0 && liveFilePreflightEnd > liveFilePreflight);
                assertTrue(liveFilePreflightEnd < firstLiveWrite);
                assertTrue(liveIndexPreflight >= 0 && liveIndexPreflight < firstLiveWrite);
                assertTrue(preparedIndexCopy >= 0 && preparedIndexCopy < firstLiveWrite);
                assertTrue(script.contains(
                        "mv \"$standroid_live_index\" \"$standroid_index_backup\""
                ));
                assertTrue(script.contains(
                        "mv \"$standroid_index_work\" \"$standroid_live_index\""
                ));
                assertFalse(script.contains("rm -rf \"$standroid_state/index-v2\""));
                assertTrue(script.contains(
                        "standroid_live_target=\"$standroid_state/$standroid_live_member\""
                ));
                assertTrue(script.contains(
                        "[ -f \"$standroid_live_target\" ]"
                ));
                assertTrue(script.contains(
                        "[ ! -L \"$standroid_live_target\" ]"
                ));
                assertTrue(script.contains(
                        "[ -d \"$standroid_live_index\" ]"
                ));
                assertTrue(script.contains(
                        "[ ! -L \"$standroid_live_index\" ]"
                ));
                int perFileRecheck = script.indexOf(
                        "standroid_live_target=\"$standroid_state/$standroid_member\""
                );
                assertTrue(perFileRecheck >= 0 && perFileRecheck < firstLiveWrite);

                int memberLoopStart = script.indexOf("for standroid_member in");
                int memberLoopEnd = script.indexOf("done", memberLoopStart);
                assertTrue(memberLoopStart >= 0 && memberLoopEnd > memberLoopStart);
                assertFalse(script.substring(memberLoopStart, memberLoopEnd)
                        .contains("sharedpreferences.dat"));
            } finally {
                imported.close();
                exported.close();
            }
        }
    }

    @Test
    public void exportRequiresAllIdentityFilesAndDoesNotCreateAPreferencesOnlyArchive()
            throws Exception {
        File root = Files.createTempDirectory("syncthing-backup-required-state-").toFile();
        ManagedStateStaging staging = locations(root).newStaging();
        File target = new File(root, "config.zip");
        try {
            Files.write(new File(staging.directory(), "sharedpreferences.dat").toPath(), bytes("prefs"));

            assertThrows(IOException.class, () -> SyncthingBackupArchive.write(target, staging, ""));

            assertFalse(target.exists());
            assertNoArchiveTemporaryFiles(root);
        } finally {
            staging.close();
        }
    }

    @Test
    public void failedZipCreationPreservesThePreviouslyValidBackup() throws Exception {
        File root = Files.createTempDirectory("syncthing-backup-transaction-").toFile();
        ManagedStateStaging staging = locations(root).newStaging();
        File target = new File(root, "config.zip");
        try {
            writeRequiredState(staging, "previous config");
            SyncthingBackupArchive.write(target, staging, "");
            byte[] previousArchive = Files.readAllBytes(target.toPath());

            assertThrows(IOException.class, () -> SyncthingBackupArchive.write(
                    target,
                    staging,
                    "",
                    (temporary, exportedStaging, password) -> {
                        Files.write(temporary.toPath(), bytes("partial zip"));
                        throw new IOException("simulated archive write failure");
                    }
            ));

            assertArrayEquals(previousArchive, Files.readAllBytes(target.toPath()));
            assertNoArchiveTemporaryFiles(root);
        } finally {
            staging.close();
        }
    }

    @Test
    public void successfulExportReplacesAnExistingValidBackup() throws Exception {
        File root = Files.createTempDirectory("syncthing-backup-replace-").toFile();
        ManagedStateStaging staging = locations(root).newStaging();
        File target = new File(root, "config.zip");
        File extracted = new File(root, "extracted");
        try {
            writeRequiredState(staging, "first config");
            SyncthingBackupArchive.write(target, staging, "");
            writeRequiredState(staging, "replacement config");

            SyncthingBackupArchive.write(target, staging, "");
            new ZipFile(target).extractFile("config.xml", extracted.getAbsolutePath());

            assertArrayEquals(
                    bytes("replacement config"),
                    Files.readAllBytes(new File(extracted, "config.xml").toPath())
            );
        } finally {
            staging.close();
        }
    }

    @Test
    public void rejectsTraversalBeforeWritingOutsideStaging() throws Exception {
        File root = Files.createTempDirectory("syncthing-backup-traversal-").toFile();
        ManagedStateStaging staging = locations(root).newStaging();
        File archivePath = new File(root, "backup.zip");
        ZipFile archive = createArchive(archivePath,
                entry("config.xml", bytes("config")),
                entry("cert.pem", bytes("cert")),
                entry("key.pem", bytes("key")),
                entry("../escaped.txt", bytes("escape"))
        );

        try {
            assertThrows(IOException.class, () -> SyncthingBackupArchive.extract(archive, staging));
            assertFalse(new File(staging.directory().getParentFile(), "escaped.txt").exists());
            assertFalse(new File(staging.directory(), "escaped.txt").exists());
        } finally {
            staging.close();
        }
    }

    @Test
    public void rejectsDuplicateSingletonAndFileDirectoryCollisions() throws Exception {
        List<FileHeader> duplicate = Arrays.asList(
                header("config.xml", false, 0),
                header("config.xml", false, 0),
                header("cert.pem", false, 0),
                header("key.pem", false, 0)
        );
        assertThrows(IOException.class, () -> SyncthingBackupArchive.validateEntries(duplicate));

        List<FileHeader> collision = Arrays.asList(
                header("config.xml", false, 0),
                header("cert.pem", false, 0),
                header("key.pem", false, 0),
                header("index-v2/", true, 0),
                header("index-v2/child", false, 0),
                header("index-v2/child/grandchild", false, 0)
        );
        assertThrows(IOException.class, () -> SyncthingBackupArchive.validateEntries(collision));
    }

    @Test
    public void rejectsUnknownEntriesAndUnixSymlinks() throws Exception {
        List<FileHeader> unknown = Arrays.asList(
                header("config.xml", false, 0),
                header("cert.pem", false, 0),
                header("key.pem", false, 0),
                header("unrelated.txt", false, 0)
        );
        assertThrows(IOException.class, () -> SyncthingBackupArchive.validateEntries(unknown));

        List<FileHeader> symlink = Arrays.asList(
                header("config.xml", false, 0),
                header("cert.pem", false, 0),
                header("key.pem", false, 0),
                header("index-v2/link", false, 0120000)
        );
        assertThrows(IOException.class, () -> SyncthingBackupArchive.validateEntries(symlink));
    }

    @Test
    public void rejectsAbsoluteAndBackslashSeparatedPaths() throws Exception {
        for (String path : Arrays.asList(
                "/config.xml", "C:/config.xml", "index-v2\\child", "index-v2/../key.pem"
        )) {
            List<FileHeader> headers = Arrays.asList(
                    header("config.xml", false, 0),
                    header("cert.pem", false, 0),
                    header("key.pem", false, 0),
                    header(path, false, 0)
            );
            assertThrows(IOException.class,
                    () -> SyncthingBackupArchive.validateEntries(headers));
        }
    }

    private static ManagedStateLocations locations(File root) {
        return new ManagedStateLocations(
                new File(root, "files"), new File(root, "cache"), 10_000, 10_000
        );
    }

    private static ZipFile createArchive(File path, Entry... entries) throws Exception {
        ZipFile zip = new ZipFile(path);
        for (Entry entry : entries) {
            ZipParameters parameters = new ZipParameters();
            parameters.setCompressionMethod(CompressionMethod.DEFLATE);
            parameters.setFileNameInZip(entry.name);
            zip.addStream(new ByteArrayInputStream(entry.contents), parameters);
        }
        return zip;
    }

    private static void assertArchiveContainsEstablishedEntries(ZipFile archive)
            throws Exception {
        List<String> names = new ArrayList<>();
        for (FileHeader header : archive.getFileHeaders()) names.add(header.getFileName());
        for (String required : Arrays.asList(
                "config.xml", "key.pem", "cert.pem", "https-cert.pem", "https-key.pem",
                "sharedpreferences.dat", "index-v2/folder/db"
        )) {
            assertTrue("archive is missing " + required, names.contains(required));
        }
        for (String name : names) {
            assertTrue(
                    "archive contains an unsupported entry " + name,
                    Arrays.asList(
                            "config.xml", "key.pem", "cert.pem", "https-cert.pem",
                            "https-key.pem", "sharedpreferences.dat", "index-v2/", "index-v2"
                    ).contains(name) || name.startsWith("index-v2/")
            );
        }
    }

    private static void writeRequiredState(ManagedStateStaging staging, String config)
            throws IOException {
        Files.write(new File(staging.directory(), "config.xml").toPath(), bytes(config));
        Files.write(new File(staging.directory(), "cert.pem").toPath(), bytes("cert"));
        Files.write(new File(staging.directory(), "key.pem").toPath(), bytes("key"));
    }

    private static void assertNoArchiveTemporaryFiles(File directory) {
        File[] entries = directory.listFiles();
        assertTrue("the backup directory must remain listable", entries != null);
        for (File entry : entries) {
            assertFalse(
                    "a failed export must clean its temporary archive",
                    entry.getName().startsWith(".config.zip-")
            );
        }
    }

    private static Entry entry(String name, byte[] contents) {
        return new Entry(name, contents);
    }

    private static FileHeader header(String path, boolean directory, int unixType) {
        FileHeader header = new FileHeader();
        header.setFileName(path);
        header.setDirectory(directory);
        if (unixType != 0) {
            header.setVersionMadeBy((3 << 8) | 20);
            header.setExternalFileAttributes(new byte[] {
                    0, 0, (byte) unixType, (byte) (unixType >>> 8)
            });
        }
        return header;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] serializedPreferences() throws IOException {
        ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        Map<String, Object> preferenceValues = new java.util.HashMap<>();
        preferenceValues.put("preference", "value");
        preferenceValues.put("booleanPreference", true);
        preferenceValues.put("integerPreference", 7);
        preferenceValues.put("floatPreference", 1.5f);
        preferenceValues.put("longPreference", 9L);
        preferenceValues.put("setPreference", new java.util.HashSet<>(
                java.util.Collections.singleton("set-member")
        ));
        try (ObjectOutputStream output = new ObjectOutputStream(serialized)) {
            output.writeObject(preferenceValues);
        }
        return serialized.toByteArray();
    }

    private static final class DeserializationProbe implements Serializable {
        private static final long serialVersionUID = 1L;
        private static boolean readHookInvoked;

        private void readObject(ObjectInputStream input)
                throws IOException, ClassNotFoundException {
            input.defaultReadObject();
            readHookInvoked = true;
        }
    }

    private static final class Entry {
        final String name;
        final byte[] contents;

        Entry(String name, byte[] contents) {
            this.name = name;
            this.contents = contents;
        }
    }
}
