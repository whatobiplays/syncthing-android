package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Covers the deterministic classification helpers of the libsu factory.
 *
 * <p>The test uses the numeric shell statuses that libsu defines: {@code NON_ROOT_SHELL} is
 * {@code 0}, {@code ROOT_SHELL} is {@code 1}, and an unverified shell reports {@code -1}.</p>
 */
public class LibsuRootShellFactoryTest {
    private static final int NON_ROOT_SHELL = 0;
    private static final int ROOT_SHELL = 1;
    private static final int UNKNOWN_SHELL = -1;

    @Test
    public void internalTimeoutStaysBelowTheActivationDeadline() {
        long timeout = LibsuRootShellFactory.internalShellTimeoutSeconds(60_000);

        assertEquals(40, timeout);
        assertTrue(
                "libsu shell timeout plus margin must fit inside the activation deadline",
                timeout * 1000 + LibsuRootShellFactory.INTERNAL_TIMEOUT_MARGIN_MILLIS <= 60_000);
    }

    @Test
    public void internalTimeoutIsAtLeastOneSecondForShortDeadlines() {
        assertEquals(1, LibsuRootShellFactory.internalShellTimeoutSeconds(1));
        assertEquals(1, LibsuRootShellFactory.internalShellTimeoutSeconds(10_000));
        assertEquals(3, LibsuRootShellFactory.internalShellTimeoutSeconds(23_000));
    }

    @Test
    public void classifiesLibsuTimeoutAsActivationTimeout() {
        RuntimeException failure = LibsuRootShellFactory.classifyBuildFailure(
                "Shell check timeout Unable to create a shell!");

        assertTrue(failure instanceof RootTransportException);
        assertEquals(
                RootFailure.ROOT_ACTIVATION_TIMEOUT,
                ((RootTransportException) failure).failure()
        );
    }

    @Test
    public void classifiesTerminatedTransportAsDenied() {
        assertEquals(
                RootFailure.ROOT_DENIED,
                failureOf(LibsuRootShellFactory.classifyBuildFailure(
                        "Created process has terminated Unable to create a shell! su: access denied"))
        );
    }

    @Test
    public void classifiesPermissionErrorsAsDenied() {
        assertEquals(
                RootFailure.ROOT_DENIED,
                failureOf(LibsuRootShellFactory.classifyBuildFailure(
                        "su: permission denied"))
        );
        assertEquals(
                RootFailure.ROOT_DENIED,
                failureOf(LibsuRootShellFactory.classifyBuildFailure(
                        "Created process is not a shell"))
        );
    }

    @Test
    public void classifiesUnknownBuildFailuresAsTransportFailures() {
        assertEquals(
                RootFailure.ROOT_TRANSPORT_FAILED,
                failureOf(LibsuRootShellFactory.classifyBuildFailure("something unexpected"))
        );
        assertEquals(
                RootFailure.ROOT_TRANSPORT_FAILED,
                failureOf(LibsuRootShellFactory.classifyBuildFailure(null))
        );
    }

    @Test
    public void acceptsAVerifiedRootShell() {
        LibsuRootShellFactory.requireRootShellStatus(ROOT_SHELL, ROOT_SHELL, NON_ROOT_SHELL);
    }

    @Test
    public void rejectsTheNonRootFallback() {
        try {
            LibsuRootShellFactory.requireRootShellStatus(NON_ROOT_SHELL, ROOT_SHELL, NON_ROOT_SHELL);
            fail("Expected the non-root fallback to be rejected");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.ROOT_DENIED, expected.failure());
            assertTrue(expected.getMessage().contains("non-root"));
        }
    }

    @Test
    public void rejectsAnUnverifiedShellStatus() {
        try {
            LibsuRootShellFactory.requireRootShellStatus(
                    UNKNOWN_SHELL, ROOT_SHELL, NON_ROOT_SHELL);
            fail("Expected the unverified shell status to be rejected");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.ROOT_DENIED, expected.failure());
        }
    }

    @Test
    public void acceptsOnlyUserZero() {
        LibsuRootShellFactory.requireRootUser("0");

        try {
            LibsuRootShellFactory.requireRootUser("10134");
            fail("Expected a non-zero user id to be rejected");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.UID_VERIFICATION_FAILED, expected.failure());
        }

        try {
            LibsuRootShellFactory.requireRootUser("");
            fail("Expected an empty user id to be rejected");
        } catch (RootTransportException expected) {
            assertEquals(RootFailure.UID_VERIFICATION_FAILED, expected.failure());
        }
    }

    private static RootFailure failureOf(RuntimeException failure) {
        assertTrue(failure instanceof RootTransportException);
        return ((RootTransportException) failure).failure();
    }
}
