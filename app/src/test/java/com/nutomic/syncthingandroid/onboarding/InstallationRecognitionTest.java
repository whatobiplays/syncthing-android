package com.nutomic.syncthingandroid.onboarding;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Verifies onboarding does not replace an existing installation with a new identity. */
public class InstallationRecognitionTest {

    @Test
    public void markerRecognizesAnInstallationWithoutReadableConfiguration() {
        assertTrue(InstallationRecognition.shouldSkipKeyGeneration(true, false, false));
    }

    @Test
    public void selectedRootModeRecognizesAnInstallationWithoutPromptingForConfiguration() {
        assertTrue(InstallationRecognition.shouldSkipKeyGeneration(false, true, false));
        assertTrue(InstallationRecognition.shouldRecordMarker(true, false));
    }

    @Test
    public void existingButUnreadableConfigurationPreventsFreshIdentityGeneration() {
        assertTrue(InstallationRecognition.shouldSkipKeyGeneration(false, false, true));
        assertFalse(InstallationRecognition.shouldRecordMarker(false, false));
    }

    @Test
    public void firstRunWithoutAnyInstallationEvidenceMayGenerateConfiguration() {
        assertFalse(InstallationRecognition.shouldSkipKeyGeneration(false, false, false));
        assertFalse(InstallationRecognition.shouldRecordMarker(false, false));
    }

    @Test
    public void parseableNormalConfigurationRecordsTheInstallationMarker() {
        assertTrue(InstallationRecognition.shouldSkipKeyGeneration(false, false, true));
        assertTrue(InstallationRecognition.shouldRecordMarker(false, true));
    }

    @Test
    public void freshNormalModeWithoutGeneratedConfigurationDoesNotRequireRepair() {
        assertFalse(InstallationRecognition.shouldRequireStateRepair(
                false, false, false, false
        ));
    }

    @Test
    public void inaccessibleRecognizedInstallationRequiresRepairInNormalMode() {
        assertTrue(InstallationRecognition.shouldRequireStateRepair(
                false, true, false, false
        ));
        assertTrue(InstallationRecognition.shouldRequireStateRepair(
                false, false, false, true
        ));
    }

    @Test
    public void rootSelectedModeDoesNotMisclassifyRootOwnedStateAsRepairRequired() {
        assertFalse(InstallationRecognition.shouldRequireStateRepair(
                false, true, true, true
        ));
    }
}
