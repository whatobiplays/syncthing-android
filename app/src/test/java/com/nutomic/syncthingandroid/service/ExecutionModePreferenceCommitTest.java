package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Verifies that a mode commit requires both durable commit success and matching readback. */
public class ExecutionModePreferenceCommitTest {

    @Test
    public void successfulCommitAndReadbackAcceptTheRequestedMode() {
        Map<String, Object> values = new HashMap<>();
        SharedPreferences preferences = preferences(values, true, true);

        assertTrue(ExecutionModePreferenceCommit.commit(preferences, true));
        assertTrue(Boolean.TRUE.equals(values.get(Constants.PREF_USE_ROOT)));
    }

    @Test
    public void failedDurableCommitIsRejectedEvenWhenTheInMemoryValueChanged() {
        Map<String, Object> values = new HashMap<>();
        SharedPreferences preferences = preferences(values, false, true);

        assertFalse(ExecutionModePreferenceCommit.commit(preferences, true));
    }

    @Test
    public void mismatchedReadbackIsRejectedAfterEditorReportsSuccess() {
        Map<String, Object> values = new HashMap<>();
        SharedPreferences preferences = preferences(values, true, false);

        assertFalse(ExecutionModePreferenceCommit.commit(preferences, true));
    }

    private static SharedPreferences preferences(
            Map<String, Object> values,
            boolean commitResult,
            boolean applyPendingValues
    ) {
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if ("getBoolean".equals(method.getName())) {
                        Object value = values.get(arguments[0]);
                        return value instanceof Boolean ? value : arguments[1];
                    }
                    if ("contains".equals(method.getName())) {
                        return values.containsKey(arguments[0]);
                    }
                    if ("edit".equals(method.getName())) {
                        Map<String, Object> pending = new HashMap<>();
                        Set<String> removed = new HashSet<>();
                        return Proxy.newProxyInstance(
                                SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[] {SharedPreferences.Editor.class},
                                (editor, editorMethod, editorArguments) -> {
                                    if ("putBoolean".equals(editorMethod.getName())) {
                                        pending.put((String) editorArguments[0], editorArguments[1]);
                                        removed.remove(editorArguments[0]);
                                        return editor;
                                    }
                                    if ("remove".equals(editorMethod.getName())) {
                                        pending.remove(editorArguments[0]);
                                        removed.add((String) editorArguments[0]);
                                        return editor;
                                    }
                                    if ("commit".equals(editorMethod.getName())) {
                                        if (applyPendingValues) {
                                            for (String key : removed) values.remove(key);
                                            values.putAll(pending);
                                        }
                                        return commitResult;
                                    }
                                    return editor;
                                }
                        );
                    }
                    return null;
                }
        );
    }
}
