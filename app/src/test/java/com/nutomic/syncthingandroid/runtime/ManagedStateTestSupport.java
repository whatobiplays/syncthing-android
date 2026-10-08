package com.nutomic.syncthingandroid.runtime;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

/** Shared fixtures for backend tests that do not exercise Managed State directly. */
public final class ManagedStateTestSupport {
    private ManagedStateTestSupport() {
    }

    /** Returns isolated temporary locations for one backend fixture. */
    public static ManagedStateLocations locations() {
        File root = new File(
                System.getProperty("java.io.tmpdir"),
                "syncthing-managed-state-test-" + UUID.randomUUID()
        );
        return new ManagedStateLocations(
                new File(root, "files"),
                new File(root, "cache"),
                10_000,
                10_000
        );
    }

    /** Uses the host JVM's no-follow metadata probe for filesystem-backed unit tests. */
    public static void useJvmSymbolicLinkInspector() {
        ManagedStateStaging.setSymbolicLinkInspectorForTests(
                file -> Files.isSymbolicLink(file.toPath())
        );
    }

    /** Restores the production Android metadata probe for tests that share a worker thread. */
    public static void clearJvmSymbolicLinkInspector() {
        ManagedStateStaging.setSymbolicLinkInspectorForTests(null);
    }

    /** Capabilities that fail loudly if a launch-only test unexpectedly uses them. */
    public static ManagedStateTransfer unusedTransfer() {
        return new ManagedStateTransfer() {
            @Override
            public ManagedStateStaging snapshotForExport() {
                throw new AssertionError("Managed State transfer was not expected");
            }

            @Override
            public void installImportedState(ManagedStateStaging staging) {
                throw new AssertionError("Managed State installation was not expected");
            }

            @Override
            public void repairAppAccess() {
                throw new AssertionError("Managed State repair was not expected");
            }
        };
    }

    /** Certificate capability that fails loudly if a launch-only test unexpectedly uses it. */
    public static HttpsCertificateStorage unusedCertificates() {
        return new HttpsCertificateStorage() {
            @Override
            public HttpsCertificateState snapshot() {
                throw new AssertionError("Certificate storage was not expected");
            }

            @Override
            public void replace(byte[] certificatePem, byte[] keyPem) {
                throw new AssertionError("Certificate storage was not expected");
            }

            @Override
            public void reset() {
                throw new AssertionError("Certificate storage was not expected");
            }

            @Override
            public void restore(HttpsCertificateState state) {
                throw new AssertionError("Certificate storage was not expected");
            }
        };
    }
}
