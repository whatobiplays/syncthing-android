package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.service.Constants;

import org.junit.Test;

/**
 * Deterministic tests for the folder type one writeability verdict allows.
 *
 * <p>The rule exists because a probe can fail for reasons that say nothing about the folder, such
 * as a privileged operation that timed out. Only a proven read-only folder may lose its
 * send/receive type; every other verdict keeps what the user configured.</p>
 */
public class FolderTypePolicyTest {
    @Test
    public void aProvenReadOnlyFolderIsForcedToSendOnly() {
        assertEquals(
                Constants.FOLDER_TYPE_SEND_ONLY,
                FolderTypePolicy.typeFor(
                        FolderWriteability.READ_ONLY,
                        Constants.FOLDER_TYPE_SEND_RECEIVE
                )
        );
    }

    @Test
    public void anUndeterminedVerdictKeepsTheConfiguredType() {
        assertEquals(
                "a failed probe is never proof of read-only access",
                Constants.FOLDER_TYPE_SEND_RECEIVE,
                FolderTypePolicy.typeFor(
                        FolderWriteability.UNKNOWN,
                        Constants.FOLDER_TYPE_SEND_RECEIVE
                )
        );
        assertEquals(
                "a send-only folder stays send-only until a verdict proves otherwise",
                Constants.FOLDER_TYPE_SEND_ONLY,
                FolderTypePolicy.typeFor(
                        FolderWriteability.UNKNOWN,
                        Constants.FOLDER_TYPE_SEND_ONLY
                )
        );
    }

    @Test
    public void aWritableFolderKeepsTheConfiguredType() {
        assertEquals(
                Constants.FOLDER_TYPE_SEND_ONLY,
                FolderTypePolicy.typeFor(
                        FolderWriteability.WRITABLE,
                        Constants.FOLDER_TYPE_SEND_ONLY
                )
        );
    }

    @Test
    public void onlyAProvenReadOnlyVerdictMayClaimReadOnlyAccess() {
        assertTrue("a proven read-only verdict may claim read-only access",
                FolderTypePolicy.provesReadOnlyAccess(FolderWriteability.READ_ONLY));
        assertFalse("an undetermined verdict proves neither access mode",
                FolderTypePolicy.provesReadOnlyAccess(FolderWriteability.UNKNOWN));
        assertFalse("a writable verdict never claims read-only access",
                FolderTypePolicy.provesReadOnlyAccess(FolderWriteability.WRITABLE));
    }
}
