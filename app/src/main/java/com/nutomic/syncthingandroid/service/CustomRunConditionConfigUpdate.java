package com.nutomic.syncthingandroid.service;

import com.google.gson.Gson;
import com.nutomic.syncthingandroid.model.Config;
import com.nutomic.syncthingandroid.model.Device;
import com.nutomic.syncthingandroid.model.Folder;

import java.util.HashMap;
import java.util.Map;

/** Builds a candidate Run Condition update without changing its authoritative input. */
public final class CustomRunConditionConfigUpdate {
    private static final Gson GSON = new Gson();

    /** Supplies saved per-object choices and the current condition result. */
    public interface Policy {
        boolean isEnabled(String objectKey);

        boolean conditionsAreMet(String objectKey);
    }

    /** Result of comparing saved conditions with one authoritative configuration snapshot. */
    public static final class Result {
        private final Config candidate;
        private final boolean ready;
        private final boolean changed;
        private final Map<String, Boolean> expectedPauseStates;

        private Result(
                Config candidate,
                boolean ready,
                boolean changed,
                Map<String, Boolean> expectedPauseStates
        ) {
            this.candidate = candidate;
            this.ready = ready;
            this.changed = changed;
            this.expectedPauseStates = new HashMap<>(expectedPauseStates);
        }

        public Config candidate() {
            return candidate;
        }

        public boolean isReady() {
            return ready;
        }

        public boolean changed() {
            return changed;
        }

        /** Returns whether an authoritative read-back contains every requested pause value. */
        public boolean matchesAuthoritativeReadBack(Config readBack) {
            if (!ready || readBack == null || readBack.folders == null
                    || readBack.devices == null) {
                return false;
            }
            for (Map.Entry<String, Boolean> expected : expectedPauseStates.entrySet()) {
                String key = expected.getKey();
                if (key.startsWith(Constants.PREF_OBJECT_PREFIX_FOLDER)) {
                    String id = key.substring(Constants.PREF_OBJECT_PREFIX_FOLDER.length());
                    Folder folder = findFolder(readBack, id);
                    if (folder == null || folder.paused != expected.getValue()) return false;
                } else if (key.startsWith(Constants.PREF_OBJECT_PREFIX_DEVICE)) {
                    String id = key.substring(Constants.PREF_OBJECT_PREFIX_DEVICE.length());
                    Device device = findDevice(readBack, id);
                    if (device == null || device.paused != expected.getValue()) return false;
                }
            }
            return true;
        }
    }

    private CustomRunConditionConfigUpdate() {
    }

    /**
     * Returns the desired configuration as a copy of the authoritative snapshot.
     *
     * <p>Callers may send the candidate to Syncthing, but must retain the input as authoritative
     * until a later configuration read confirms that the server applied it. Recomputing from the
     * same unchanged input after a failed write therefore leaves the desired change pending.</p>
     */
    public static Result createCandidate(Config authoritative, Policy policy) {
        if (authoritative == null || authoritative.folders == null
                || authoritative.devices == null) {
            return new Result(null, false, false, new HashMap<>());
        }

        Config candidate = GSON.fromJson(GSON.toJson(authoritative), Config.class);
        boolean changed = false;
        Map<String, Boolean> expectedPauseStates = new HashMap<>();
        for (Folder folder : candidate.folders) {
            String objectKey = Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id;
            if (policy.isEnabled(objectKey)) {
                boolean shouldPause = !policy.conditionsAreMet(objectKey);
                expectedPauseStates.put(objectKey, shouldPause);
                if (folder.paused != shouldPause) {
                    folder.paused = shouldPause;
                    changed = true;
                }
            }
        }
        for (Device device : candidate.devices) {
            String objectKey = Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID;
            if (policy.isEnabled(objectKey)) {
                boolean shouldPause = !policy.conditionsAreMet(objectKey);
                expectedPauseStates.put(objectKey, shouldPause);
                if (device.paused != shouldPause) {
                    device.paused = shouldPause;
                    changed = true;
                }
            }
        }
        return new Result(candidate, true, changed, expectedPauseStates);
    }

    private static Folder findFolder(Config config, String id) {
        for (Folder folder : config.folders) {
            if (folder != null && id.equals(folder.id)) return folder;
        }
        return null;
    }

    private static Device findDevice(Config config, String id) {
        for (Device device : config.devices) {
            if (device != null && id.equals(device.deviceID)) return device;
        }
        return null;
    }
}
