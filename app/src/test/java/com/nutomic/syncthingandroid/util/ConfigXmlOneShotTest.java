package com.nutomic.syncthingandroid.util;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.nutomic.syncthingandroid.runtime.ExecutionAdmissionException;
import com.nutomic.syncthingandroid.runtime.ExecutionIdentityUnavailableException;
import com.nutomic.syncthingandroid.runtime.RecoveryAssessmentFixture;
import com.nutomic.syncthingandroid.runtime.ExecutionRecoveryException;
import com.nutomic.syncthingandroid.runtime.ExecutableNotFoundException;

import org.junit.Test;


public class ConfigXmlOneShotTest {

    @Test
    public void admissionRejectionMapsToOpenConfigFailure() throws Exception {
        ExecutionAdmissionException admissionFailure = new ExecutionAdmissionException();

        try {
            ConfigXml.runOneShot(() -> {
                throw admissionFailure;
            });
            fail("Expected ConfigXml.OpenConfigException");
        } catch (ConfigXml.OpenConfigException e) {
            assertSame(admissionFailure, e.getCause());
        }
    }

    @Test
    public void missingExecutionIdentityMapsToOpenConfigFailure() {
        ExecutionIdentityUnavailableException identityFailure =
                new ExecutionIdentityUnavailableException();

        ConfigXml.OpenConfigException thrown = assertThrows(
                ConfigXml.OpenConfigException.class,
                () -> ConfigXml.runOneShot(() -> {
                    throw identityFailure;
                })
        );

        assertSame(identityFailure, thrown.getCause());
    }

    @Test
    public void recoveryRejectionMapsToOpenConfigFailure() {
        ExecutionRecoveryException recoveryFailure = new ExecutionRecoveryException(
                RecoveryAssessmentFixture.ambiguousMissingRecord()
        );
        org.junit.Assert.assertFalse(recoveryFailure.assessment().mayLaunch());

        ConfigXml.OpenConfigException thrown = assertThrows(
                ConfigXml.OpenConfigException.class,
                () -> ConfigXml.runOneShot(() -> {
                    throw recoveryFailure;
                })
        );

        assertSame(recoveryFailure, thrown.getCause());
    }

    @Test
    public void executableNotFoundPassesThroughUnchanged() {
        ExecutableNotFoundException executableNotFound =
                new ExecutableNotFoundException("missing executable");

        ExecutableNotFoundException thrown = assertThrows(
                ExecutableNotFoundException.class,
                () -> ConfigXml.runOneShot(() -> {
                    throw executableNotFound;
                })
        );

        assertSame(executableNotFound, thrown);
    }

    @Test
    public void unrelatedRuntimeFailurePassesThroughUnchanged() {
        IllegalStateException runtimeFailure = new IllegalStateException("unexpected failure");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ConfigXml.runOneShot(() -> {
                    throw runtimeFailure;
                })
        );

        assertSame(runtimeFailure, thrown);
    }
}
