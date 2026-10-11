package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;

import java.net.URL;

import org.junit.Test;

/** Verifies the values delivered from configuration workers are immutable service data. */
public class StartupConfigurationTest {

    @Test
    public void retainsOnlyWebEndpointValuesNeededByServiceStartup() throws Exception {
        URL webGuiUrl = new URL("https://127.0.0.1:8384/");
        StartupConfiguration configuration = new StartupConfiguration(webGuiUrl, "api-key", 8384);

        assertEquals(webGuiUrl, configuration.webGuiUrl());
        assertEquals("api-key", configuration.apiKey());
        assertEquals(8384, configuration.webGuiBindPort());
    }
}
