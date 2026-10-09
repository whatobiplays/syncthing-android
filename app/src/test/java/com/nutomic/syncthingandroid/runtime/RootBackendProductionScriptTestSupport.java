package com.nutomic.syncthingandroid.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Runs an imported staging directory through RootBackend and the production libsu script builder. */
public final class RootBackendProductionScriptTestSupport {
    /** Home path configured folder paths expand to; the tests never use a home-relative path. */
    static final String TEST_TILDE_BASE = "/storage/emulated/0/syncthing";

    private RootBackendProductionScriptTestSupport() {
    }

    /** Installs staged content through RootBackend and returns the privileged install script. */
    public static String installImportedState(
            ManagedStateStaging staging,
            ManagedStateLocations locations
    ) throws IOException {
        Path temporary = Files.createTempDirectory("root-backend-production-script-");
        File stateDirectory = temporary.resolve("runtime-state").toFile();
        if (!stateDirectory.mkdir()) throw new IOException("Could not create root test state");
        File binary = temporary.resolve("libsyncthingnative.so").toFile();
        Files.write(binary.toPath(), new byte[] {1});

        ScriptedLibsuShell shell = new ScriptedLibsuShell(
                ScriptedLibsuShell.ATTACHED_STAT_LINE, null
        );
        RootShellFactory factory = timeoutMillis -> new LibsuRootShell(
                shell, new TestProcess(), locations, timeoutMillis
        );
        RootBackend backend = new RootBackend(
                null,
                stateDirectory,
                binary,
                null,
                temporary.toFile(),
            factory,
            locations,
            60_000,
            RootBackend.CREATION_CONFIRMATION_TIMEOUT_MILLIS,
            100,
            TEST_TILDE_BASE
    );

        try {
            backend.managedStateTransfer().installImportedState(staging);
            List<String> commands = shell.executedCommands();
            for (String command : commands) {
                if (command.contains("standroid_stage=")) return command;
            }
            throw new IOException("RootBackend did not invoke the production install script");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class TestProcess extends Process {
        private boolean destroyed;

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return !destroyed;
        }
    }
}
