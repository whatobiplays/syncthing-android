package com.nutomic.syncthingandroid.service;

import com.nutomic.syncthingandroid.util.ConfigXml;

import java.net.URL;
import java.util.Objects;

/** Immutable connection settings captured from authoritative Syncthing configuration. */
final class StartupConfiguration {
    private final URL webGuiUrl;
    private final String apiKey;
    private final int webGuiBindPort;

    StartupConfiguration(URL webGuiUrl, String apiKey, int webGuiBindPort) {
        this.webGuiUrl = Objects.requireNonNull(webGuiUrl);
        this.apiKey = Objects.requireNonNull(apiKey);
        this.webGuiBindPort = webGuiBindPort;
    }

    /** Captures only immutable service startup values from a worker-owned ConfigXml instance. */
    static StartupConfiguration from(ConfigXml config) {
        return new StartupConfiguration(
                config.getWebGuiUrl(),
                config.getApiKey(),
                config.getWebGuiBindPort()
        );
    }

    URL webGuiUrl() {
        return webGuiUrl;
    }

    String apiKey() {
        return apiKey;
    }

    int webGuiBindPort() {
        return webGuiBindPort;
    }
}
