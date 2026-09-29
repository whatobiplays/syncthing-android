package com.nutomic.syncthingandroid.util;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.nutomic.syncthingandroid.runtime.ExecutionAdmissionException;

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
}
