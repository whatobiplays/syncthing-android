package com.nutomic.syncthingandroid.util;

/** Reports that authoritative configuration is unavailable without an explicit edit action. */
public final class ConfigurationUnavailableException extends IllegalStateException {
    public ConfigurationUnavailableException(String detail) {
        super(detail);
    }
}
