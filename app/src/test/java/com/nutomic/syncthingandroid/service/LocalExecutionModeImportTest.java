package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Verifies that backup preferences cannot replace the device-local execution mode. */
public class LocalExecutionModeImportTest {

    @Test
    public void archiveExecutionModePreferenceIsClassifiedAsDeviceLocal() {
        assertTrue(SyncthingService.isDeviceLocalPreference(Constants.PREF_USE_ROOT));
        assertTrue(SyncthingService.isDeviceLocalPreference(Constants.PREF_INSTALLATION_MARKER));
        assertTrue(SyncthingService.isDeviceLocalPreference(Constants.PREF_INOTIFY_WATCH_LIMIT));
        assertTrue(SyncthingService.isDeviceLocalPreference(
                Constants.PREF_ROOT_RUN_SCRIPT_PROMPT_POLICY));
        assertFalse(SyncthingService.isDeviceLocalPreference("backup_password"));
    }

    @Test
    public void successfulImportKeepsLocalModeAndTuningAndRecordsInstallationLineage() {
        Map<String, Object> restored = new HashMap<>();
        SharedPreferences.Editor editor = recordingEditor(restored);

        assertTrue(SyncthingService.restoreLocalDevicePreferences(
                editor, true, Boolean.TRUE, true
        ));

        assertEquals(Boolean.TRUE, restored.get(Constants.PREF_USE_ROOT));
        assertEquals(Boolean.TRUE, restored.get(Constants.PREF_INSTALLATION_MARKER));
        assertEquals(Boolean.TRUE, restored.get(Constants.PREF_INOTIFY_WATCH_LIMIT));
    }

    @Test
    public void restorePreservesThePriorValueAndLeavesAnAbsentPreferenceAbsent() {
        Map<String, Object> restored = new HashMap<>();
        SharedPreferences.Editor editor = recordingEditor(restored);

        assertTrue(SyncthingService.restoreLocalExecutionModePreference(editor, true, true));
        assertEquals(Boolean.TRUE, restored.get(Constants.PREF_USE_ROOT));

        restored.clear();
        assertTrue(SyncthingService.restoreLocalExecutionModePreference(editor, false, null));
        assertTrue(restored.isEmpty());
    }

    @Test
    public void oppositeArchivedModeCannotReplaceEitherDeviceLocalValue() {
        for (boolean localValue : new boolean[] { true, false }) {
            Map<String, Object> archived = new HashMap<>();
            archived.put(Constants.PREF_USE_ROOT, !localValue);
            Map<String, Object> restored = new HashMap<>();
            for (Map.Entry<String, Object> entry : archived.entrySet()) {
                if (!SyncthingService.isDeviceLocalPreference(entry.getKey())) {
                    restored.put(entry.getKey(), entry.getValue());
                }
            }
            SharedPreferences.Editor editor = recordingEditor(restored);

            assertTrue(SyncthingService.restoreLocalExecutionModePreference(
                    editor, true, localValue
            ));

            assertEquals(localValue, restored.get(Constants.PREF_USE_ROOT));
        }
    }

    @Test
    public void absentLocalModeRemainsAbsentDespiteAnArchivedValue() {
        Map<String, Object> restored = new HashMap<>();
        Map<String, Object> archived = new HashMap<>();
        archived.put(Constants.PREF_USE_ROOT, true);
        for (Map.Entry<String, Object> entry : archived.entrySet()) {
            if (!SyncthingService.isDeviceLocalPreference(entry.getKey())) {
                restored.put(entry.getKey(), entry.getValue());
            }
        }

        assertTrue(SyncthingService.restoreLocalExecutionModePreference(
                recordingEditor(restored), false, null
        ));

        assertFalse(restored.containsKey(Constants.PREF_USE_ROOT));
    }

    @Test
    public void importedRootScriptConsentCannotEnableAutomaticPrompts() {
        Map<String, Object> archived = new HashMap<>();
        archived.put(Constants.PREF_ROOT_RUN_SCRIPT_PROMPT_POLICY, "ARMED");
        archived.put("ordinary_preference", true);
        Map<String, Object> restored = new HashMap<>();
        for (Map.Entry<String, Object> entry : archived.entrySet()) {
            if (!SyncthingService.isDeviceLocalPreference(entry.getKey())) {
                restored.put(entry.getKey(), entry.getValue());
            }
        }
        assertFalse(restored.containsKey(Constants.PREF_ROOT_RUN_SCRIPT_PROMPT_POLICY));
        assertEquals(true, restored.get("ordinary_preference"));
    }

    @Test
    public void backupDoesNotSerializeDeviceLocalRootScriptConsent() {
        Map<String, Object> live = new HashMap<>();
        live.put(Constants.PREF_ROOT_RUN_SCRIPT_PROMPT_POLICY, "SUSPENDED:DENIED");
        live.put("ordinary_preference", true);

        Map<String, ?> exported = SyncthingService.preferencesForBackup(live);

        assertFalse(exported.containsKey(Constants.PREF_ROOT_RUN_SCRIPT_PROMPT_POLICY));
        assertEquals(true, exported.get("ordinary_preference"));
        assertEquals("SUSPENDED:DENIED",
                live.get(Constants.PREF_ROOT_RUN_SCRIPT_PROMPT_POLICY));
    }

    @Test
    public void malformedPresentSharedPreferencesAreRejected() throws Exception {
        File malformed = Files.createTempFile("malformed-shared-preferences-", ".dat").toFile();
        try (ObjectOutputStream output = new ObjectOutputStream(
                Files.newOutputStream(malformed.toPath())
        )) {
            output.writeObject("not a shared-preferences map");
        }

        assertThrows(
                IOException.class,
                () -> SyncthingBackupArchive.readSharedPreferencesMap(malformed)
        );
    }

    private static SharedPreferences.Editor recordingEditor(Map<String, Object> values) {
        return (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.Editor.class},
                (proxy, method, arguments) -> {
                    if ("putBoolean".equals(method.getName())) {
                        values.put((String) arguments[0], arguments[1]);
                        return proxy;
                    }
                    return proxy;
                }
        );
    }
}
