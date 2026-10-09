package com.nutomic.syncthingandroid.runtime;

import java.util.Objects;

/**
 * Identifies an existing configured folder for backend-owned folder operations.
 *
 * <p>The reference deliberately carries only the folder identifier. The folder path is part of
 * the authoritative configuration, which only the selected backend may resolve at operation
 * time: a caller-provided path could be forged, stale, or memory-only projection state, and must
 * never decide which files a privileged operation touches.</p>
 */
public final class ConfiguredFolderReference {
    private final String id;

    private ConfiguredFolderReference(String id) {
        this.id = Objects.requireNonNull(id, "The folder identifier is required");
    }

    /** Creates a reference to the configured folder with the given identifier. */
    public static ConfiguredFolderReference of(String id) {
        return new ConfiguredFolderReference(id);
    }

    /** Returns the configured folder identifier this reference names. */
    public String id() {
        return id;
    }
}
