package com.nutomic.syncthingandroid.service;

import android.content.SharedPreferences;

import java.util.Objects;

/** Commits and verifies the device-local execution mode preference. */
final class ExecutionModePreferenceCommit {
    private ExecutionModePreferenceCommit() {
    }

    /** Returns true only when disk commit succeeds and the committed value reads back exactly. */
    static boolean commit(SharedPreferences preferences, boolean useRoot) {
        Objects.requireNonNull(preferences, "Shared preferences are required");
        boolean hadPreviousValue = preferences.contains(Constants.PREF_USE_ROOT);
        boolean previousValue = preferences.getBoolean(Constants.PREF_USE_ROOT, false);
        boolean committed = preferences.edit()
                .putBoolean(Constants.PREF_USE_ROOT, useRoot)
                .commit()
                && preferences.getBoolean(Constants.PREF_USE_ROOT, false) == useRoot;
        if (committed) return true;

        // SharedPreferences updates its in-process map before disk I/O. Restore the previous
        // in-process selection after a failed commit so UI and other readers do not observe an
        // uncommitted backend request. The runtime stays closed if durable persistence failed.
        SharedPreferences.Editor rollback = preferences.edit();
        if (hadPreviousValue) {
            rollback.putBoolean(Constants.PREF_USE_ROOT, previousValue);
        } else {
            rollback.remove(Constants.PREF_USE_ROOT);
        }
        rollback.commit();
        return false;
    }
}
