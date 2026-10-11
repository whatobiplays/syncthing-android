package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.model.Config;
import com.nutomic.syncthingandroid.model.Device;
import com.nutomic.syncthingandroid.model.Folder;

import org.junit.Test;

import java.util.Arrays;

/** Verifies deferred Run Condition changes remain pending until authoritative read-back. */
public class CustomRunConditionConfigUpdateTest {

    private static final String FOLDER_KEY = Constants.PREF_OBJECT_PREFIX_FOLDER + "folder-id";
    private static final String DEVICE_KEY = Constants.PREF_OBJECT_PREFIX_DEVICE + "device-id";

    @Test
    public void candidateChangesDoNotMutateTheAuthoritativeSnapshot() {
        Config authoritative = configuration(false, false);

        CustomRunConditionConfigUpdate.Result result =
                CustomRunConditionConfigUpdate.createCandidate(authoritative, policy());

        assertTrue(result.isReady());
        assertTrue(result.changed());
        assertNotSame(authoritative, result.candidate());
        assertFalse(authoritative.folders.get(0).paused);
        assertFalse(authoritative.devices.get(0).paused);
        assertTrue(result.candidate().folders.get(0).paused);
        assertTrue(result.candidate().devices.get(0).paused);
    }

    @Test
    public void failedApplicationAndProcessRestartRecomputeTheSamePendingCandidate() {
        Config authoritative = configuration(false, false);

        CustomRunConditionConfigUpdate.Result firstAttempt =
                CustomRunConditionConfigUpdate.createCandidate(authoritative, policy());
        CustomRunConditionConfigUpdate.Result afterFailure =
                CustomRunConditionConfigUpdate.createCandidate(authoritative, policy());

        assertTrue(firstAttempt.changed());
        assertTrue(afterFailure.changed());
        assertEquals(firstAttempt.candidate().folders.get(0).paused,
                afterFailure.candidate().folders.get(0).paused);
        assertEquals(firstAttempt.candidate().devices.get(0).paused,
                afterFailure.candidate().devices.get(0).paused);
    }

    @Test
    public void verifiedAuthoritativeReadBackClearsThePendingChange() {
        Config verified = configuration(true, true);

        CustomRunConditionConfigUpdate.Result result =
                CustomRunConditionConfigUpdate.createCandidate(verified, policy());

        assertTrue(result.isReady());
        assertFalse(result.changed());
    }

    @Test
    public void readBackMustContainEveryRequestedPauseValue() {
        Config authoritative = configuration(false, false);
        CustomRunConditionConfigUpdate.Result update =
                CustomRunConditionConfigUpdate.createCandidate(authoritative, policy());

        assertTrue(update.matchesAuthoritativeReadBack(configuration(true, true)));
        assertFalse(update.matchesAuthoritativeReadBack(configuration(false, true)));
        assertFalse(update.matchesAuthoritativeReadBack(null));
    }

    @Test
    public void onlyEnabledObjectsArePartOfTheRequestedReadBack() {
        Config authoritative = configuration(false, false);
        CustomRunConditionConfigUpdate.Result update =
                CustomRunConditionConfigUpdate.createCandidate(
                        authoritative,
                        new CustomRunConditionConfigUpdate.Policy() {
                            @Override
                            public boolean isEnabled(String objectKey) {
                                return FOLDER_KEY.equals(objectKey);
                            }

                            @Override
                            public boolean conditionsAreMet(String objectKey) {
                                return false;
                            }
                        }
                );

        assertTrue(update.matchesAuthoritativeReadBack(configuration(true, false)));
        assertFalse(update.matchesAuthoritativeReadBack(configuration(false, false)));
    }

    @Test
    public void incompleteAuthoritativeConfigurationCannotProduceAWriteCandidate() {
        Config authoritative = configuration(false, false);
        authoritative.devices = null;

        CustomRunConditionConfigUpdate.Result result =
                CustomRunConditionConfigUpdate.createCandidate(authoritative, policy());

        assertFalse(result.isReady());
        assertFalse(result.changed());
    }

    private static Config configuration(boolean folderPaused, boolean devicePaused) {
        Config config = new Config();
        Folder folder = new Folder();
        folder.id = "folder-id";
        folder.paused = folderPaused;
        Device device = new Device();
        device.deviceID = "device-id";
        device.paused = devicePaused;
        config.folders = Arrays.asList(folder);
        config.devices = Arrays.asList(device);
        return config;
    }

    private static CustomRunConditionConfigUpdate.Policy policy() {
        return new CustomRunConditionConfigUpdate.Policy() {
            @Override
            public boolean isEnabled(String objectKey) {
                return FOLDER_KEY.equals(objectKey) || DEVICE_KEY.equals(objectKey);
            }

            @Override
            public boolean conditionsAreMet(String objectKey) {
                return false;
            }
        };
    }
}
