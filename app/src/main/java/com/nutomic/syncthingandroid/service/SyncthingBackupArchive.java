package com.nutomic.syncthingandroid.service;

import com.nutomic.syncthingandroid.runtime.ManagedStateMember;
import com.nutomic.syncthingandroid.runtime.ManagedStateStaging;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.io.File;
import java.io.FileInputStream;
import java.io.InvalidClassException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads and writes the established backup format using only operation-owned staging files. */
final class SyncthingBackupArchive {
    private static final String INDEX_DIRECTORY = "index-v2";
    static final String SHARED_PREFERENCES = "sharedpreferences.dat";

    @FunctionalInterface
    interface ArchiveWriter {
        void write(File archive, ManagedStateStaging staging, String password) throws IOException;
    }

    private SyncthingBackupArchive() {
    }

    /** Creates and validates a temporary backup before replacing the destination archive. */
    static void write(File target, ManagedStateStaging staging, String password)
            throws IOException {
        write(target, staging, password, SyncthingBackupArchive::writeArchive);
    }

    /** Runs the transactional archive path with a writer that can report ZIP creation failures. */
    static void write(
            File target,
            ManagedStateStaging staging,
            String password,
            ArchiveWriter archiveWriter
    ) throws IOException {
        staging.verifyProvenance();
        validateStagedContents(staging, true);
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Could not create the backup directory");
        }
        if (ManagedStateStaging.isSymbolicLink(target)) {
            throw new IOException("The backup destination is a symbolic link");
        }

        File temporary = File.createTempFile("." + target.getName() + "-", ".tmp", parent);
        try {
            archiveWriter.write(temporary, staging, password);
            validateWrittenArchive(temporary, password);
            if (!temporary.renameTo(target)) {
                throw new IOException("Could not replace the existing backup archive");
            }
        } catch (IOException failure) {
            deleteTemporaryArchive(temporary, failure);
            throw failure;
        } catch (RuntimeException failure) {
            deleteTemporaryArchive(temporary, failure);
            throw failure;
        }
    }

    /** Writes one complete ZIP at the temporary path using the established archive format. */
    private static void writeArchive(File target, ManagedStateStaging staging, String password)
            throws IOException {
        ZipParameters parameters = new ZipParameters();
        parameters.setCompressionMethod(CompressionMethod.DEFLATE);
        parameters.setCompressionLevel(CompressionLevel.NORMAL);
        ZipFile zipFile;
        if (password == null || password.isEmpty()) {
            zipFile = new ZipFile(target);
            parameters.setEncryptFiles(false);
        } else {
            zipFile = new ZipFile(target, password.toCharArray());
            parameters.setEncryptFiles(true);
            parameters.setEncryptionMethod(EncryptionMethod.AES);
            parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        }

        for (ManagedStateMember member : archiveFileOrder()) {
            File file = staging.member(member);
            if (file.exists()) zipFile.addFile(file, parameters);
        }
        File sharedPreferences = new File(staging.directory(), SHARED_PREFERENCES);
        if (sharedPreferences.exists()) zipFile.addFile(sharedPreferences, parameters);
        File index = new File(staging.directory(), INDEX_DIRECTORY);
        if (index.exists()) zipFile.addFolder(index, parameters);
    }

    /** Reopens the finished ZIP and checks that its complete entry set matches the import contract. */
    private static void validateWrittenArchive(File archive, String password) throws IOException {
        boolean encrypted = password != null && !password.isEmpty();
        ZipFile zip = encrypted
                ? new ZipFile(archive, password.toCharArray())
                : new ZipFile(archive);
        validateEntries(zip.getFileHeaders());
        if (zip.isEncrypted() != encrypted) {
            throw new IOException("The backup archive does not match its encryption setting");
        }
    }

    /** Removes an incomplete temporary ZIP and attaches cleanup failure to the primary error. */
    private static void deleteTemporaryArchive(File temporary, Throwable failure) {
        if (temporary.exists() && !temporary.delete()) {
            failure.addSuppressed(new IOException("Could not remove the incomplete backup archive"));
        }
    }

    /** Validates the complete archive before extracting any entry to the fresh staging directory. */
    static Map<?, ?> extract(ZipFile archive, ManagedStateStaging staging)
            throws IOException, ClassNotFoundException {
        staging.verifyProvenance();
        File[] stagedEntries = staging.directory().listFiles();
        if (stagedEntries == null || stagedEntries.length != 0) {
            throw new IOException("The import staging directory is not empty");
        }
        List<FileHeader> headers = archive.getFileHeaders();
        validateEntries(headers);

        for (FileHeader header : headers) {
            archive.extractFile(header, staging.directory().getAbsolutePath());
        }
        validateStagedContents(staging, false);
        File sharedPreferences = new File(staging.directory(), SHARED_PREFERENCES);
        return sharedPreferences.exists() ? readSharedPreferencesMap(sharedPreferences) : null;
    }

    /** Reads only the established Java preference classes before state can replace live files. */
    static Map<?, ?> readSharedPreferencesMap(File file)
            throws IOException, ClassNotFoundException {
        try (ObjectInputStream input = new PreferencesObjectInputStream(
                new FileInputStream(file)
        )) {
            Object object = input.readObject();
            if (!(object instanceof Map)) {
                throw new IOException("Shared preferences archive entry is not a map");
            }
            Map<?, ?> preferences = (Map<?, ?>) object;
            for (Map.Entry<?, ?> entry : preferences.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IOException("Shared preferences archive contains a non-string key");
                }
                Object value = entry.getValue();
                if (value instanceof Set) {
                    for (Object member : (Set<?>) value) {
                        if (!(member instanceof String)) {
                            throw new IOException(
                                    "Shared preferences archive contains a non-string set member"
                            );
                        }
                    }
                } else if (!(value instanceof Boolean)
                        && !(value instanceof String)
                        && !(value instanceof Integer)
                        && !(value instanceof Float)
                        && !(value instanceof Long)) {
                    throw new IOException("Shared preferences archive contains an invalid value");
                }
            }
            return preferences;
        }
    }

    /** Rejects unrecognized serialized classes before their deserialization hooks can execute. */
    private static final class PreferencesObjectInputStream extends ObjectInputStream {
        PreferencesObjectInputStream(java.io.InputStream input) throws IOException {
            super(input);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass descriptor)
                throws IOException, ClassNotFoundException {
            String name = descriptor.getName();
            if (!isAllowedPreferenceClass(name)) {
                throw new InvalidClassException(name, "Class is not allowed in archived preferences");
            }
            return super.resolveClass(descriptor);
        }

        @Override
        protected Class<?> resolveProxyClass(String[] interfaces) throws IOException {
            throw new InvalidClassException(
                    "java.lang.reflect.Proxy",
                    "Serialized proxies are not allowed in archived preferences"
            );
        }
    }

    private static boolean isAllowedPreferenceClass(String name) {
        switch (name) {
            case "java.lang.Boolean":
            case "java.lang.Float":
            case "java.lang.Integer":
            case "java.lang.Long":
            case "java.lang.Number":
            case "java.lang.String":
            case "java.util.HashMap":
            case "java.util.HashSet":
                return true;
            default:
                return false;
        }
    }

    /** Checks names, entry kinds, duplicates, and parent-child type consistency as one pass. */
    static void validateEntries(List<FileHeader> headers) throws IOException {
        if (headers == null) throw new IOException("The backup archive has no file entries");
        Map<String, Boolean> kinds = new HashMap<>();
        Set<String> required = new HashSet<>();
        for (FileHeader header : headers) {
            if (header == null) throw new IOException("The backup archive contains an invalid entry");
            String rawName = header.getFileName();
            boolean trailingSeparator = rawName != null && rawName.endsWith("/");
            boolean directory = header.isDirectory();
            String name = validateName(rawName, directory, trailingSeparator);
            rejectSpecialFileType(header, directory);
            if (kinds.put(name, directory) != null) {
                throw new IOException("The backup archive contains a duplicate entry: " + name);
            }
            if (isManagedStateFile(name) && !directory) {
                required.add(name);
            } else if (SHARED_PREFERENCES.equals(name) && !directory) {
                // Shared preferences are an archive adjunct, not Managed State.
            } else if (INDEX_DIRECTORY.equals(name) && directory) {
                // The directory itself is optional and may be omitted by legacy archives.
            } else if (name.startsWith(INDEX_DIRECTORY + "/")) {
                // Descendants of index-v2 are validated by the same path and kind checks below.
            } else {
                throw new IOException("The backup archive contains an unsupported entry: " + name);
            }
        }

        for (String requiredName : new String[] {"config.xml", "cert.pem", "key.pem"}) {
            if (!required.contains(requiredName)) {
                throw new IOException("The backup archive is missing " + requiredName);
            }
        }
        for (Map.Entry<String, Boolean> entry : kinds.entrySet()) {
            String path = entry.getKey();
            int slash = path.indexOf('/');
            while (slash >= 0) {
                String parent = path.substring(0, slash);
                Boolean parentIsDirectory = kinds.get(parent);
                if (Boolean.FALSE.equals(parentIsDirectory)) {
                    throw new IOException("A file is also used as an archive directory: " + parent);
                }
                slash = path.indexOf('/', slash + 1);
            }
            if (!entry.getValue()) {
                String prefix = path + "/";
                for (String candidate : kinds.keySet()) {
                    if (candidate.startsWith(prefix)) {
                        throw new IOException("A file entry has descendants: " + path);
                    }
                }
            }
        }
    }

    private static String validateName(
            String rawName, boolean directory, boolean trailingSeparator
    ) throws IOException {
        if (rawName == null || rawName.isEmpty() || rawName.startsWith("/")
                || rawName.indexOf('\\') >= 0 || rawName.indexOf('\0') >= 0
                || rawName.indexOf(':') >= 0 || (trailingSeparator && !directory)) {
            throw new IOException("The backup archive contains an unsafe path");
        }
        String name = trailingSeparator
                ? rawName.substring(0, rawName.length() - 1)
                : rawName;
        if (name.isEmpty()) throw new IOException("The backup archive contains an empty path");
        String[] segments = name.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("The backup archive contains a traversing path");
            }
        }
        if (INDEX_DIRECTORY.equals(name) && !directory) {
            throw new IOException("The index-v2 archive entry must be a directory");
        }
        if (isManagedStateFile(name)
                || SHARED_PREFERENCES.equals(name)) {
            if (segments.length != 1 || directory) {
                throw new IOException("A top-level archive member has the wrong type");
            }
        } else if (!INDEX_DIRECTORY.equals(name) && !name.startsWith(INDEX_DIRECTORY + "/")) {
            throw new IOException("The backup archive contains an unsupported path");
        }
        return name;
    }

    private static void rejectSpecialFileType(FileHeader header, boolean directory)
            throws IOException {
        if ((header.getVersionMadeBy() >>> 8) != 3) return;
        byte[] attributes = header.getExternalFileAttributes();
        if (attributes == null || attributes.length < 4) return;
        int mode = ((attributes[3] & 0xff) << 8) | (attributes[2] & 0xff);
        int type = mode & 0170000;
        if (type == 0) return;
        int expected = directory ? 0040000 : 0100000;
        if (type != expected) {
            throw new IOException("The backup archive contains a symbolic link or special file");
        }
    }

    private static void validateStagedContents(ManagedStateStaging staging, boolean exporting)
            throws IOException {
        File directory = staging.directory();
        if (ManagedStateStaging.isSymbolicLink(directory) || !directory.isDirectory()) {
            throw new IOException("The operation staging directory is unsafe");
        }
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("Could not read the operation staging directory");
        for (File entry : entries) {
            String name = entry.getName();
            if (isManagedStateFile(name)
                    || SHARED_PREFERENCES.equals(name)) {
                if (ManagedStateStaging.isSymbolicLink(entry) || !entry.isFile()) {
                    throw new IOException("A staged archive member is not a regular file");
                }
            } else if (INDEX_DIRECTORY.equals(name)) {
                if (ManagedStateStaging.isSymbolicLink(entry) || !entry.isDirectory()) {
                    throw new IOException("The staged index-v2 member is not a directory");
                }
                validateIndexTree(entry);
            } else {
                throw new IOException("The operation staging directory contains an unknown entry");
            }
        }
        for (String required : new String[] {"config.xml", "cert.pem", "key.pem"}) {
            File file = new File(directory, required);
            if (ManagedStateStaging.isSymbolicLink(file) || !file.isFile()) {
                throw new IOException("The staged backup is missing " + required);
            }
        }
        if (exporting) return;
        String stagingRoot = directory.getCanonicalPath() + File.separator;
        for (File entry : entries) {
            if (!entry.getCanonicalPath().startsWith(stagingRoot)) {
                throw new IOException("An extracted archive entry escaped staging");
            }
        }
    }

    private static void validateIndexTree(File directory) throws IOException {
        if (ManagedStateStaging.isSymbolicLink(directory)) {
            throw new IOException("The index-v2 archive contains a symbolic link");
        }
        File[] entries = directory.listFiles();
        if (entries == null) {
            throw new IOException("The index-v2 archive cannot be read");
        }
        for (File entry : entries) {
            if (ManagedStateStaging.isSymbolicLink(entry)) {
                throw new IOException("The index-v2 archive contains a symbolic link");
            }
            if (entry.isDirectory()) {
                validateIndexTree(entry);
            } else if (!entry.isFile()) {
                throw new IOException("The index-v2 archive contains a special file");
            }
        }
    }

    private static List<ManagedStateMember> archiveFileOrder() {
        List<ManagedStateMember> order = new ArrayList<>();
        order.add(ManagedStateMember.CONFIG);
        order.add(ManagedStateMember.KEY);
        order.add(ManagedStateMember.CERT);
        order.add(ManagedStateMember.HTTPS_CERT);
        order.add(ManagedStateMember.HTTPS_KEY);
        return order;
    }

    private static boolean isManagedStateFile(String name) {
        for (ManagedStateMember member : ManagedStateMember.values()) {
            if (member.isFile() && member.fileName().equals(name)) return true;
        }
        return false;
    }
}
