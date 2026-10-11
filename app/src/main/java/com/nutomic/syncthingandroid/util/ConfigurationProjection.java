package com.nutomic.syncthingandroid.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nutomic.syncthingandroid.model.Device;
import com.nutomic.syncthingandroid.model.Folder;

import java.lang.reflect.Type;
import java.util.List;

/**
 * Process-memory display snapshot refreshed from an active REST configuration.
 *
 * <p>The projection is deliberately transient and read-only. It is never persisted, never provides
 * paths for privileged operations, and never authorizes offline configuration writes.</p>
 */
public final class ConfigurationProjection {
    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();
    private static final Type FOLDER_LIST = new TypeToken<List<Folder>>() {}.getType();
    private static final Type DEVICE_LIST = new TypeToken<List<Device>>() {}.getType();
    private static List<Folder> folders;
    private static List<Device> remoteDevices;
    private static List<Device> allDevices;

    private ConfigurationProjection() {
    }

    /** Replaces the display snapshot with copies of the current REST configuration. */
    public static void update(
            List<Folder> currentFolders,
            List<Device> currentRemoteDevices,
            List<Device> currentAllDevices
    ) {
        synchronized (LOCK) {
            folders = copy(currentFolders, FOLDER_LIST);
            remoteDevices = copy(currentRemoteDevices, DEVICE_LIST);
            allDevices = copy(currentAllDevices, DEVICE_LIST);
        }
    }

    /** Returns a copy of projected folders, or {@code null} before any active REST refresh. */
    public static List<Folder> folders() {
        synchronized (LOCK) {
            return copy(folders, FOLDER_LIST);
        }
    }

    /** Returns a copy of projected devices, or {@code null} before any active REST refresh. */
    public static List<Device> devices(boolean includeLocal) {
        synchronized (LOCK) {
            return copy(includeLocal ? allDevices : remoteDevices, DEVICE_LIST);
        }
    }

    /** Clears the transient snapshot after an offline import replaces the authoritative config. */
    public static void clear() {
        synchronized (LOCK) {
            folders = null;
            remoteDevices = null;
            allDevices = null;
        }
    }

    private static <T> T copy(T value, Type type) {
        return value == null ? null : GSON.fromJson(GSON.toJson(value, type), type);
    }
}
