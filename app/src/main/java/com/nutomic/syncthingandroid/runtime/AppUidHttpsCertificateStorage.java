package com.nutomic.syncthingandroid.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * HTTPS certificate storage that runs with the application UID.
 *
 * <p>Normal Mode owns the certificate pair directly, so the implementation reads and writes the
 * two approved members with ordinary application file operations while preserving the established
 * behavior: replacements are written atomically, and the private key is restricted to its
 * owner.</p>
 */
final class AppUidHttpsCertificateStorage implements HttpsCertificateStorage {

    private final ManagedStateLocations locations;

    AppUidHttpsCertificateStorage(ManagedStateLocations locations) {
        this.locations = Objects.requireNonNull(locations);
    }

    @Override
    public HttpsCertificateState snapshot() throws ManagedStateException {
        locations.requireSafeStateRoot(ManagedStateFailure.STATE_ACCESS_FAILED);
        return new HttpsCertificateState(
                readIfPresent(locations.member(ManagedStateMember.HTTPS_CERT)),
                readIfPresent(locations.member(ManagedStateMember.HTTPS_KEY))
        );
    }

    @Override
    public void replace(byte[] certificatePem, byte[] keyPem) throws ManagedStateException {
        Objects.requireNonNull(certificatePem, "The replacement certificate is required");
        Objects.requireNonNull(keyPem, "The replacement key is required");
        locations.requireSafeStateRoot(ManagedStateFailure.STATE_ACCESS_FAILED);
        writeAtomic(locations.member(ManagedStateMember.HTTPS_CERT), certificatePem);
        writeAtomic(locations.member(ManagedStateMember.HTTPS_KEY), keyPem);
        restrictToOwner(locations.member(ManagedStateMember.HTTPS_KEY));
    }

    @Override
    public void reset() throws ManagedStateException {
        locations.requireSafeStateRoot(ManagedStateFailure.STATE_ACCESS_FAILED);
        deleteIfPresent(locations.member(ManagedStateMember.HTTPS_CERT));
        deleteIfPresent(locations.member(ManagedStateMember.HTTPS_KEY));
    }

    @Override
    public void restore(HttpsCertificateState state) throws ManagedStateException {
        Objects.requireNonNull(state, "The captured certificate state is required");
        locations.requireSafeStateRoot(ManagedStateFailure.STATE_ACCESS_FAILED);
        restoreFile(
                locations.member(ManagedStateMember.HTTPS_CERT),
                state.certificateBytes(),
                state.certificatePresent()
        );
        restoreFile(
                locations.member(ManagedStateMember.HTTPS_KEY),
                state.keyBytes(),
                state.keyPresent()
        );
        if (state.keyPresent()) {
            restrictToOwner(locations.member(ManagedStateMember.HTTPS_KEY));
        }
    }

    /** Reads one member exactly, or reports {@code null} only when it is genuinely absent. */
    private static byte[] readIfPresent(File file) throws ManagedStateException {
        if (ManagedStateStaging.isSymbolicLink(file)) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_ACCESS_FAILED,
                    "The certificate member " + file.getName() + " is a symbolic link"
            );
        }
        if (!file.exists()) {
            return null;
        }
        if (!file.isFile()) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_ACCESS_FAILED,
                    "The certificate member " + file.getName() + " is not a regular file"
            );
        }
        try (FileInputStream input = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_ACCESS_FAILED,
                    "Could not read the certificate member " + file.getName(),
                    e
            );
        }
    }

    private static void writeAtomic(File target, byte[] contents) throws ManagedStateException {
        File temporary = null;
        try {
            temporary = File.createTempFile(
                    target.getName() + "-", ".tmp", target.getParentFile()
            );
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(contents);
                output.flush();
                output.getFD().sync();
            }
        } catch (IOException e) {
            if (temporary != null) deleteQuietly(temporary);
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_ACCESS_FAILED,
                    "Could not write " + target.getName(),
                    e
            );
        }
        if (!temporary.renameTo(target)) {
            deleteQuietly(temporary);
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_ACCESS_FAILED,
                    "Could not replace " + target.getName()
            );
        }
    }

    private static void deleteIfPresent(File file) throws ManagedStateException {
        if (!file.exists() && !ManagedStateStaging.isSymbolicLink(file)) {
            return;
        }
        if (!file.delete()) {
            throw new ManagedStateException(
                    ManagedStateFailure.STATE_ACCESS_FAILED,
                    "Could not remove " + file.getName()
            );
        }
    }

    private static void restoreFile(File target, byte[] contents, boolean present)
            throws ManagedStateException {
        if (present) {
            writeAtomic(target, contents);
            return;
        }
        deleteIfPresent(target);
    }

    private static void deleteQuietly(File file) {
        if (file.exists()) {
            file.delete();
        }
    }

    private static void restrictToOwner(File file) throws ManagedStateException {
        if (ManagedStateStaging.isSymbolicLink(file) || !file.isFile()) {
            throw permissionFailure(file);
        }
        // Mirror the bundled Syncthing core, which writes the HTTPS key with 0600 permissions.
        boolean updated = true;
        updated &= file.setReadable(false, false);
        updated &= file.setReadable(true, true);
        updated &= file.setWritable(false, false);
        updated &= file.setWritable(true, true);
        updated &= file.setExecutable(false, false);
        if (!updated || ManagedStateStaging.isSymbolicLink(file) || !file.isFile()
                || !file.canRead() || !file.canWrite() || file.canExecute()) {
            throw permissionFailure(file);
        }
    }

    private static ManagedStateException permissionFailure(File file) {
        return new ManagedStateException(
                ManagedStateFailure.STATE_ACCESS_FAILED,
                "Could not restrict the private key to its owner: " + file.getName()
        );
    }
}
