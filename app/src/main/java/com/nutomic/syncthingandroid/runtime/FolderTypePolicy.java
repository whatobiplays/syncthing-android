package com.nutomic.syncthingandroid.runtime;

import com.nutomic.syncthingandroid.service.Constants;

/**
 * Decides which folder type one writeability verdict allows.
 *
 * <p>The folder editor keeps the folder type the user configured, except for a folder that is
 * proven read-only: such a folder can only be configured send-only. A verdict that could not be
 * determined, for example because a privileged probe failed, is not proof of read-only access, so
 * it never changes the configured type and no save can persist a downgrade the user never asked
 * for.</p>
 *
 * <p>The rule is kept apart from the editor and uses no Android type, so it can be verified in
 * plain JVM tests.</p>
 */
public final class FolderTypePolicy {
    private FolderTypePolicy() {
    }

    /**
     * Returns the folder type to keep for one writeability verdict.
     *
     * @param verdict the writeability verdict the selected backend reported
     * @param configuredType the folder type the user currently has configured
     * @return the send-only type for a proven read-only folder, otherwise the configured type
     */
    public static String typeFor(FolderWriteability verdict, String configuredType) {
        if (verdict == FolderWriteability.READ_ONLY) {
            return Constants.FOLDER_TYPE_SEND_ONLY;
        }
        return configuredType;
    }

    /**
     * Returns whether one writeability verdict proved read-only access to the folder.
     *
     * <p>Only a proven read-only verdict may present itself as read-only access. A verdict that
     * could not be determined proves neither access mode, so the editor states an undetermined
     * result for it instead of claiming read-only access the probe never established.</p>
     *
     * @param verdict the writeability verdict the selected backend reported
     * @return {@code true} only for a verdict that proved read-only access
     */
    public static boolean provesReadOnlyAccess(FolderWriteability verdict) {
        return verdict == FolderWriteability.READ_ONLY;
    }
}
