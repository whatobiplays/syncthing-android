package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.service.SyncthingService.HttpsCertReplaceResult;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CertificateVerificationStateTest {
    @Test
    public void destructionWhileStartingRollsBackAndIgnoresLateCallbacks() throws IOException {
        try (CertificateFiles files = new CertificateFiles()) {
            CertificateVerificationState state = new CertificateVerificationState();
            state.beginVerification();
            state.onStarting();
            AtomicInteger rollbacks = new AtomicInteger();
            AtomicInteger serveLaunches = new AtomicInteger();
            AtomicInteger listenerCalls = new AtomicInteger();
            AtomicReference<HttpsCertReplaceResult> listenerResult = new AtomicReference<>();

            assertTrue(state.resolveForDestruction(
                    () -> {
                        files.restoreOriginalState();
                        rollbacks.incrementAndGet();
                    },
                    () -> {
                        listenerResult.set(HttpsCertReplaceResult.FAILED);
                        listenerCalls.incrementAndGet();
                    }
            ));

            assertEquals("old certificate", files.readCertificate());
            assertEquals("old key", files.readKey());
            assertFalse(Files.exists(files.certBackup));
            assertFalse(Files.exists(files.keyBackup));
            assertEquals(1, rollbacks.get());
            assertEquals(0, serveLaunches.get());
            assertEquals(HttpsCertReplaceResult.FAILED, listenerResult.get());
            assertEquals(1, listenerCalls.get());
            assertFalse(state.isWorkflowActive());

            assertEquals(CertificateVerificationState.Outcome.IGNORED,
                    state.onVerificationResult(true));
            assertEquals(CertificateVerificationState.Outcome.IGNORED,
                    state.onVerificationResult(false));
            assertFalse(state.resolveForDestruction(
                    rollbacks::incrementAndGet, listenerCalls::incrementAndGet
            ));
            assertEquals(1, rollbacks.get());
            assertEquals(1, listenerCalls.get());
            assertEquals(0, serveLaunches.get());
        }
    }

    @Test
    public void destructionDuringFailureRecoveryRollsBackOnlyOnce() throws IOException {
        try (CertificateFiles files = new CertificateFiles()) {
            CertificateVerificationState state = new CertificateVerificationState();
            state.beginVerification();
            state.onStarting();
            assertEquals(CertificateVerificationState.Outcome.FAILURE,
                    state.onVerificationResult(false));

            AtomicInteger rollbacks = new AtomicInteger();
            AtomicInteger serveLaunches = new AtomicInteger();
            AtomicInteger listenerCalls = new AtomicInteger();
            AtomicReference<HttpsCertReplaceResult> listenerResult = new AtomicReference<>();
            Runnable lateRecovery = state.completeFailureRecovery(
                    () -> {
                        files.restoreOriginalState();
                        rollbacks.incrementAndGet();
                    },
                    () -> true,
                    serveLaunches::incrementAndGet,
                    () -> { },
                    () -> listenerCalls.incrementAndGet()
            );

            assertTrue(state.resolveForDestruction(
                    () -> {
                        files.restoreOriginalState();
                        rollbacks.incrementAndGet();
                    },
                    () -> {
                        listenerResult.set(HttpsCertReplaceResult.FAILED);
                        listenerCalls.incrementAndGet();
                    }
            ));
            lateRecovery.run();

            assertEquals("old certificate", files.readCertificate());
            assertEquals("old key", files.readKey());
            assertEquals(1, rollbacks.get());
            assertEquals(0, serveLaunches.get());
            assertEquals(HttpsCertReplaceResult.FAILED, listenerResult.get());
            assertEquals(1, listenerCalls.get());
            assertFalse(state.isWorkflowActive());
        }
    }

    @Test
    public void destructionRemovesReplacementFilesWhenNoPreviousCertificateExisted()
            throws IOException {
        try (CertificateFiles files = new CertificateFiles(false)) {
            CertificateVerificationState state = new CertificateVerificationState();
            state.beginVerification();
            state.onStarting();
            AtomicInteger listenerCalls = new AtomicInteger();

            assertTrue(state.resolveForDestruction(
                    files::restoreOriginalState,
                    listenerCalls::incrementAndGet
            ));

            assertFalse(Files.exists(files.cert));
            assertFalse(Files.exists(files.key));
            assertEquals(1, listenerCalls.get());
        }
    }

    @Test
    public void successDispatchCallsOnlySuccessHandler() {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger pending = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        CertificateVerificationState.dispatch(
                CertificateVerificationState.Outcome.SUCCESS,
                success::incrementAndGet,
                pending::incrementAndGet,
                failure::incrementAndGet
        );

        assertEquals(1, success.get());
        assertEquals(0, pending.get());
        assertEquals(0, failure.get());
    }

    @Test
    public void explicitStopDuringStartingKeepsNewCertificateAndResolvesPendingStartOnce()
            throws IOException {
        try (CertificateFiles files = new CertificateFiles()) {
            CertificateVerificationState state = new CertificateVerificationState();
            state.beginVerification();
            state.onStarting();

            AtomicInteger serveLaunches = new AtomicInteger();
            AtomicInteger rollbacks = new AtomicInteger();
            AtomicReference<HttpsCertReplaceResult> listenerResult = new AtomicReference<>();
            AtomicInteger listenerCalls = new AtomicInteger();

            CertificateVerificationState.Outcome stopOutcome =
                    state.onExplicitStop(false, false);
            assertEquals(CertificateVerificationState.Outcome.SUCCESS_PENDING_START, stopOutcome);
            CertificateVerificationState.dispatch(
                    stopOutcome,
                    () -> listenerResult.set(HttpsCertReplaceResult.SUCCESS),
                    () -> state.completePendingStart(
                            files::deleteBackups,
                            () -> {
                                listenerResult.set(HttpsCertReplaceResult.SUCCESS_PENDING_START);
                                listenerCalls.incrementAndGet();
                            }
                    ),
                    () -> {
                        files.restorePreviousCertificate();
                        rollbacks.incrementAndGet();
                        serveLaunches.incrementAndGet();
                        listenerResult.set(HttpsCertReplaceResult.FAILED);
                        listenerCalls.incrementAndGet();
                    }
            );

            CertificateVerificationState.Outcome stoppedExecutionOutcome =
                    state.onVerificationResult(false);
            CertificateVerificationState.dispatch(
                    stoppedExecutionOutcome,
                    () -> listenerCalls.incrementAndGet(),
                    () -> listenerCalls.incrementAndGet(),
                    () -> listenerCalls.incrementAndGet()
            );
            state.completePendingStart(files::deleteBackups, listenerCalls::incrementAndGet);

            assertEquals("new certificate", files.readCertificate());
            assertEquals("new key", files.readKey());
            assertFalse(Files.exists(files.certBackup));
            assertFalse(Files.exists(files.keyBackup));
            assertEquals(0, rollbacks.get());
            assertEquals(0, serveLaunches.get());
            assertEquals(HttpsCertReplaceResult.SUCCESS_PENDING_START, listenerResult.get());
            assertEquals(1, listenerCalls.get());
            assertTrue(state.wasExplicitlyStopped());
            assertFalse(state.automaticContinuationAllowed());
            assertFalse(state.isWorkflowActive());
        }
    }

    @Test
    public void crashedNativeStopDuringTransientActiveResolvesAsFailureWithoutRelaunch()
            throws IOException {
        try (CertificateFiles files = new CertificateFiles()) {
            CertificateVerificationState state = new CertificateVerificationState();
            state.beginVerification();
            state.onStarting();

            // The service can still report ACTIVE from the crashed execution until its shutdown
            // notification is processed. The crash evidence must win over that stale state.
            CertificateVerificationState.Outcome stopOutcome =
                    state.onExplicitStop(true, true);
            assertNotEquals(CertificateVerificationState.Outcome.SUCCESS, stopOutcome);
            assertEquals(CertificateVerificationState.Outcome.IGNORED, stopOutcome);
            assertTrue(state.isVerificationActive());
            assertTrue(state.wasExplicitlyStopped());
            assertFalse(state.automaticContinuationAllowed());

            // The subsequent failure transition restores the previous certificate and reports a
            // failure without relaunching Syncthing.
            assertFailureRollsBack(files, state, state.onVerificationResult(false), 0);
        }
    }

    @Test
    public void stopAfterVerificationReachedActiveKeepsSuccessfulResolution() {
        CertificateVerificationState state = new CertificateVerificationState();
        state.beginVerification();
        state.onStarting();

        assertEquals(CertificateVerificationState.Outcome.SUCCESS,
                state.onVerificationResult(true));
        assertEquals(CertificateVerificationState.Outcome.IGNORED,
                state.onExplicitStop(true, false));
        assertFalse(state.isWorkflowActive());
    }

    @Test
    public void crashedNativeStopSuppressesRelaunchButPreservesFailureRollback()
            throws IOException {
        try (CertificateFiles files = new CertificateFiles()) {
            CertificateVerificationState state = new CertificateVerificationState();
            state.beginVerification();
            state.onStarting();

            assertEquals(CertificateVerificationState.Outcome.IGNORED,
                    state.onExplicitStop(false, true));
            assertTrue(state.isVerificationActive());
            assertTrue(state.wasExplicitlyStopped());
            assertFalse(state.automaticContinuationAllowed());

            assertFailureRollsBack(
                    files,
                    state,
                    state.onVerificationResult(false),
                    0
            );
        }
    }

    @Test
    public void stopDuringFailureRecoveryKeepsRollbackButSuppressesRelaunch()
            throws IOException {
        try (CertificateFiles files = new CertificateFiles()) {
            CertificateVerificationState state = new CertificateVerificationState();
            state.beginVerification();
            state.onStarting();
            CertificateVerificationState.Outcome failure = state.onVerificationResult(false);

            assertEquals(CertificateVerificationState.Outcome.IGNORED,
                    state.onExplicitStop(false, false));
            assertTrue(state.wasExplicitlyStopped());
            assertFalse(state.automaticContinuationAllowed());

            assertFailureRollsBack(files, state, failure, 0);
        }
    }

    @Test
    public void genuineVerificationFailureRollsBackAndRetainsNormalRestartPolicy()
            throws IOException {
        try (CertificateFiles files = new CertificateFiles()) {
            CertificateVerificationState state = new CertificateVerificationState();
            state.beginVerification();
            state.onStarting();

            assertFailureRollsBack(
                    files,
                    state,
                    state.onVerificationResult(false),
                    1
            );
            assertFalse(state.wasExplicitlyStopped());
            assertTrue(state.automaticContinuationAllowed());
        }
    }

    @Test
    public void shutdownFailureTerminatesCertificateRecoveryAndNotifiesOnce() {
        CertificateVerificationState state = new CertificateVerificationState();
        state.beginVerification();
        state.onStarting();
        assertEquals(
                CertificateVerificationState.Outcome.FAILURE,
                state.onVerificationResult(false)
        );
        AtomicInteger notifications = new AtomicInteger();

        assertTrue(state.failFailureRecoveryShutdown(notifications::incrementAndGet));
        assertFalse(state.isWorkflowActive());
        assertFalse(state.automaticContinuationAllowed());
        assertEquals(1, notifications.get());

        assertFalse(state.failFailureRecoveryShutdown(notifications::incrementAndGet));
        assertEquals("terminal failure is reported only once", 1, notifications.get());
    }

    private static void assertFailureRollsBack(CertificateFiles files,
                                               CertificateVerificationState state,
                                               CertificateVerificationState.Outcome outcome,
                                               int expectedServeLaunches) {
        AtomicInteger serveLaunches = new AtomicInteger();
        AtomicInteger failureListenerCalls = new AtomicInteger();
        AtomicReference<HttpsCertReplaceResult> listenerResult = new AtomicReference<>();

        assertEquals(CertificateVerificationState.Outcome.FAILURE, outcome);
        CertificateVerificationState.dispatch(
                outcome,
                () -> { },
                () -> { },
                state.completeFailureRecovery(
                        files::restorePreviousCertificate,
                        () -> true,
                        serveLaunches::incrementAndGet,
                        () -> { },
                        () -> {
                            listenerResult.set(HttpsCertReplaceResult.FAILED);
                            failureListenerCalls.incrementAndGet();
                        }
                )
        );

        assertEquals("old certificate", files.readCertificate());
        assertEquals("old key", files.readKey());
        assertEquals(expectedServeLaunches, serveLaunches.get());
        assertEquals(HttpsCertReplaceResult.FAILED, listenerResult.get());
        assertEquals(1, failureListenerCalls.get());
        assertFalse(state.isWorkflowActive());
    }

    private static final class CertificateFiles implements AutoCloseable {
        private final Path directory;
        private final Path cert;
        private final Path key;
        private final Path certBackup;
        private final Path keyBackup;

        private CertificateFiles() throws IOException {
            this(true);
        }

        private CertificateFiles(boolean hadPreviousFiles) throws IOException {
            directory = Files.createTempDirectory("certificate-verification-");
            cert = directory.resolve("https-cert.pem");
            key = directory.resolve("https-key.pem");
            certBackup = directory.resolve("https-cert.pem.bak");
            keyBackup = directory.resolve("https-key.pem.bak");
            if (hadPreviousFiles) {
                write(certBackup, "old certificate");
                write(keyBackup, "old key");
            }
            write(cert, "new certificate");
            write(key, "new key");
        }

        private void deleteBackups() {
            delete(certBackup);
            delete(keyBackup);
        }

        private void restorePreviousCertificate() {
            move(certBackup, cert);
            move(keyBackup, key);
        }

        private void restoreOriginalState() {
            if (Files.exists(certBackup)) {
                move(certBackup, cert);
            } else {
                delete(cert);
            }
            if (Files.exists(keyBackup)) {
                move(keyBackup, key);
            } else {
                delete(key);
            }
            deleteBackups();
        }

        private String readCertificate() {
            return read(cert);
        }

        private String readKey() {
            return read(key);
        }

        private static void write(Path path, String value) {
            try {
                Files.write(path, value.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        private static String read(Path path) {
            try {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        private static void move(Path source, Path destination) {
            try {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        private static void delete(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(cert);
            Files.deleteIfExists(key);
            Files.deleteIfExists(certBackup);
            Files.deleteIfExists(keyBackup);
            Files.deleteIfExists(directory);
        }
    }
}
