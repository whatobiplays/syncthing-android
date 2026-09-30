package com.nutomic.syncthingandroid.util;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.nutomic.syncthingandroid.runtime.ExecutionAdmissionException;
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
}
