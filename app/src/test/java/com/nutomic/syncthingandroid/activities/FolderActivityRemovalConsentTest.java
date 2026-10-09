package com.nutomic.syncthingandroid.activities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.service.Constants;

import org.junit.Test;

/**
 * Deterministic tests for the consent a finished folder removal withdraws.
 *
 * <p>Removing the "Syncthing Camera" folder withdraws the user's consent to that feature. The
 * removal runs away from the main thread, so the editor that asked for it can be destroyed before
 * the removal finishes; the rule below therefore has to hold for the removal result alone, and it
 * must stay false for every other result so a failed removal never drops the consent.</p>
 */
public class FolderActivityRemovalConsentTest {
    @Test
    public void consentIsWithdrawnOnlyForAConfirmedRemovalOfTheCameraFolder() {
        assertTrue(FolderActivity.withdrawsSyncthingCameraConsent(
                Boolean.TRUE,
                Constants.syncthingCameraFolderId
        ));
    }

    @Test
    public void aFailedRemovalKeepsTheConsentAndEveryOtherFolderIsUntouched() {
        assertFalse("a removal that did not happen keeps the feature enabled",
                FolderActivity.withdrawsSyncthingCameraConsent(
                        Boolean.FALSE,
                        Constants.syncthingCameraFolderId
                ));
        assertFalse("a removal that failed unexpectedly keeps the feature enabled",
                FolderActivity.withdrawsSyncthingCameraConsent(
                        null,
                        Constants.syncthingCameraFolderId
                ));
        assertFalse("removing any other folder says nothing about the feature",
                FolderActivity.withdrawsSyncthingCameraConsent(Boolean.TRUE, "another-folder"));
    }
}
