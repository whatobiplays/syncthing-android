package com.nutomic.syncthingandroid.runtime;

import com.topjohnwu.superuser.NoShellException;
import com.topjohnwu.superuser.Shell;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Acquires root shells from the device's superuser transport using libsu core.
 *
 * <p>The factory never uses libsu's default {@link Shell.Builder#build()} path, because that path
 * silently falls back to a non-root {@code sh} when root is unavailable. It creates the root
 * process itself and hands that exact process to libsu, so a non-root fallback can never be
 * mistaken for a granted root request. A shell is only returned after both the libsu shell status
 * and an explicit {@code id -u} probe confirm UID 0.</p>
 *
 * <p>Deadline coordination: libsu's internal shell-verification timeout is set strictly below the
 * caller's activation deadline, and every shell operation has its own bounded transport timeout.
 * The bounds together keep a whole activation inside the requested deadline, and each failure mode
 * maps to a distinct {@link RootFailure}.</p>
 */
final class LibsuRootShellFactory implements RootShellFactory {
    /** Time reserved inside the activation deadline for classification and the UID probe. */
    static final long INTERNAL_TIMEOUT_MARGIN_MILLIS = 20_000;
    /** Upper bound for libsu's own shell-verification timeout, in seconds. */
    private static final long MAXIMUM_INTERNAL_TIMEOUT_SECONDS = 40;

    private final String[] rootCommand;
    private final long operationTimeoutMillis;
    private final ManagedStateLocations locations;

    LibsuRootShellFactory(ManagedStateLocations locations) {
        this(new String[] {"su"}, locations);
    }

    LibsuRootShellFactory(String[] rootCommand, ManagedStateLocations locations) {
        this(rootCommand, LibsuRootShell.OPERATION_TIMEOUT_MILLIS, locations);
    }

    /**
     * Creates a factory whose acquired transports use an explicit helper-operation bound.
     *
     * <p>Production acquires shells with {@link LibsuRootShell#OPERATION_TIMEOUT_MILLIS}; the
     * override exists so the acquisition failure of a provenance probe that stalls past its
     * deadline can be exercised deterministically.</p>
     */
    LibsuRootShellFactory(
            String[] rootCommand,
            long operationTimeoutMillis,
            ManagedStateLocations locations
    ) {
        Objects.requireNonNull(rootCommand);
        if (rootCommand.length == 0) {
            throw new IllegalArgumentException("The root command must not be empty");
        }
        this.rootCommand = rootCommand.clone();
        this.operationTimeoutMillis = operationTimeoutMillis;
        this.locations = Objects.requireNonNull(locations);
    }

    @Override
    public RootShell acquire(long timeoutMillis) throws RootTransportException {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("The activation deadline must be positive");
        }
        return acquire(timeoutMillis, startRootTransportProcess(), LibsuRootShellFactory::buildLibsuShell);
    }

    /**
     * Acquires a root shell over an already-started root transport process.
     *
     * <p>The libsu build step is a parameter so tests can drive the acceptance-failure teardown
     * deterministically; production always passes {@link LibsuRootShellFactory#buildLibsuShell}.
     * Once libsu has returned a shell, the transport wrapper is constructed immediately, so every
     * later verification failure is torn down through {@link LibsuRootShell#close()}. That close
     * destroys the underlying process when libsu cannot close the shell itself, which is what
     * prevents an invalid UID probe or a rejected shell status from leaving a privileged
     * transport process alive.</p>
     */
    RootShell acquire(long timeoutMillis, Process process, ShellBuilder shellBuilder)
            throws RootTransportException {
        return acquire(timeoutMillis, process, shellBuilder, ownerProcessIdOrUnprovable());
    }

    /**
     * Acquires a verified root shell and records its exit status provenance.
     *
     * <p>The owner process identifier is supplied explicitly so the provenance rule can be
     * exercised without the Android process state, which is unavailable to JVM unit tests.</p>
     */
    RootShell acquire(
            long timeoutMillis,
            Process process,
            ShellBuilder shellBuilder,
            int ownerProcessId
    ) throws RootTransportException {
        Objects.requireNonNull(process);
        Objects.requireNonNull(shellBuilder);
        Shell shell;
        try {
            shell = shellBuilder.build(process, internalShellTimeoutSeconds(timeoutMillis));
        } catch (NoShellException e) {
            String evidence = buildFailureEvidence(e, process);
            destroyQuietly(process);
            throw classifyBuildFailure(evidence);
        } catch (RuntimeException e) {
            destroyQuietly(process);
            throw new RootTransportException(
                    RootFailure.ROOT_TRANSPORT_FAILED,
                    "Could not construct the root shell",
                    e
            );
        }
        if (shell == null) {
            destroyQuietly(process);
            throw new RootTransportException(
                    RootFailure.ROOT_TRANSPORT_FAILED,
                    "The root shell build returned no shell for the root transport process"
            );
        }
        LibsuRootShell rootShell =
                new LibsuRootShell(shell, process, locations, operationTimeoutMillis);
        try {
            requireRootShellStatus(shell.getStatus(), Shell.ROOT_SHELL, Shell.NON_ROOT_SHELL);
            requireRootUser(rootShell.currentUid());
            recordExitStatusProvenance(rootShell, ownerProcessId);
            return rootShell;
        } catch (RootTransportException e) {
            closeQuietly(rootShell);
            throw e;
        } catch (IOException | RuntimeException e) {
            closeQuietly(rootShell);
            throw new RootTransportException(
                    RootFailure.ROOT_TRANSPORT_FAILED,
                    "Could not verify the acquired root shell",
                    e
            );
        }
    }

    /**
     * Records whether the transport's exit status may be attributed to the launched process.
     *
     * <p>The probe runs while the transport client is still alive. An unreadable answer never
     * fails an acquisition that already verified UID 0: the status simply stays unattributable,
     * and the exit verification then reports a typed result instead of an unauthenticated status.
     * A probe whose failure left the transport unusable, because our own teardown closed it or
     * because the shell died while the probe ran, does fail the acquisition through the transport
     * failure it reports, so a dead or closed shell is never handed out as a verified one.</p>
     *
     * @throws IOException when the failed probe left the shell transport unusable
     */
    private static void recordExitStatusProvenance(LibsuRootShell rootShell, int ownerProcessId)
            throws IOException {
        if (ownerProcessId <= 0) {
            return;
        }
        rootShell.determineExitStatusProvenance(ownerProcessId);
    }

    /**
     * Returns the identifier of the application process that owns the transport.
     *
     * <p>An identifier the platform cannot report leaves the exit status unattributable, which is
     * the conservative answer for a transport whose relationship to the launched process cannot be
     * proven.</p>
     */
    private static int ownerProcessIdOrUnprovable() {
        try {
            return android.os.Process.myPid();
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }

    interface ShellBuilder {
        Shell build(Process process, long internalTimeoutSeconds);
    }

    /**
     * Returns the timeout handed to libsu's internal shell verification.
     *
     * <p>The value stays below the caller's deadline by {@link #INTERNAL_TIMEOUT_MARGIN_MILLIS}
     * and never exceeds {@link #MAXIMUM_INTERNAL_TIMEOUT_SECONDS}, so libsu reports its own
     * timeout verdict before the caller's deadline expires.</p>
     */
    static long internalShellTimeoutSeconds(long timeoutMillis) {
        long availableMillis = timeoutMillis - INTERNAL_TIMEOUT_MARGIN_MILLIS;
        long seconds = (Math.max(availableMillis, 1) + 999) / 1000;
        return Math.min(seconds, MAXIMUM_INTERNAL_TIMEOUT_SECONDS);
    }

    /** Maps the combined libsu and transport evidence onto one typed failure. */
    static RootTransportException classifyBuildFailure(String evidence) {
        String text = evidence == null ? "" : evidence.toLowerCase(Locale.ROOT);
        if (text.contains("timeout")) {
            return new RootTransportException(
                    RootFailure.ROOT_ACTIVATION_TIMEOUT,
                    "The root shell did not respond inside the activation window: " + evidence
            );
        }
        if (text.contains("terminated")
                || text.contains("denied")
                || text.contains("permission")
                || text.contains("not a shell")) {
            return new RootTransportException(
                    RootFailure.ROOT_DENIED,
                    "The root request was not granted: " + evidence
            );
        }
        return new RootTransportException(
                RootFailure.ROOT_TRANSPORT_FAILED,
                "Could not construct the root shell: " + evidence
        );
    }

    /** Rejects every shell that libsu did not verify as a root shell. */
    static void requireRootShellStatus(int status, int rootShellStatus, int nonRootShellStatus) {
        if (status == rootShellStatus) {
            return;
        }
        if (status == nonRootShellStatus) {
            throw new RootTransportException(
                    RootFailure.ROOT_DENIED,
                    "The root transport fell back to a non-root shell"
            );
        }
        throw new RootTransportException(
                RootFailure.ROOT_DENIED,
                "The acquired shell was not verified as a root shell"
        );
    }

    /** Requires the explicit user-id probe to confirm UID 0. */
    static void requireRootUser(String uid) {
        if (!"0".equals(uid)) {
            throw new RootTransportException(
                    RootFailure.UID_VERIFICATION_FAILED,
                    "The root shell reported user id '" + uid + "' instead of 0"
            );
        }
    }

    private Process startRootTransportProcess() {
        try {
            return new ProcessBuilder(rootCommand).start();
        } catch (IOException | RuntimeException e) {
            throw new RootTransportException(
                    RootFailure.ROOT_UNAVAILABLE,
                    "No root transport is available on this device",
                    e
            );
        }
    }

    /** Builds the libsu shell that wraps one already-started root transport process. */
    private static Shell buildLibsuShell(Process process, long internalTimeoutSeconds) {
        return Shell.Builder.create()
                .setTimeout(internalTimeoutSeconds)
                .build(process);
    }

    private static String buildFailureEvidence(NoShellException failure, Process process) {
        StringBuilder evidence = new StringBuilder();
        Throwable cause = failure.getCause();
        if (cause != null && cause.getMessage() != null) {
            evidence.append(cause.getMessage());
        }
        if (failure.getMessage() != null) {
            evidence.append(' ').append(failure.getMessage());
        }
        String stderr = drainAvailableStderr(process);
        if (!stderr.isEmpty()) {
            evidence.append(' ').append(stderr);
        }
        return evidence.toString().trim();
    }

    private static String drainAvailableStderr(Process process) {
        try {
            InputStream stderr = process.getErrorStream();
            int available = stderr.available();
            if (available <= 0) {
                return "";
            }
            byte[] buffer = new byte[Math.min(available, 4096)];
            int read = stderr.read(buffer, 0, buffer.length);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    private static void destroyQuietly(Process process) {
        try {
            process.destroy();
        } catch (RuntimeException ignored) {
            // The transport process is already gone.
        }
    }

    /**
     * Closes a transport whose verification failed without masking the verification verdict.
     *
     * <p>{@link LibsuRootShell#close()} destroys the underlying root process when libsu cannot
     * close its shell, so a post-build verification failure can never leave a privileged
     * transport process alive. Any remaining teardown failure is swallowed so the caller still
     * receives the original typed failure.</p>
     */
    private static void closeQuietly(RootShell shell) {
        try {
            shell.close();
        } catch (RuntimeException ignored) {
            // The transport process is already gone or cannot be torn down further; the
            // verification failure remains the result the caller has to receive.
        }
    }
}
