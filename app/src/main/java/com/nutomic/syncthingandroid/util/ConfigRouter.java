package com.nutomic.syncthingandroid.util;

import android.content.Context;
import android.util.Log;
import androidx.preference.PreferenceManager;

import com.nutomic.syncthingandroid.model.Device;
import com.nutomic.syncthingandroid.model.Folder;
import com.nutomic.syncthingandroid.model.FolderIgnoreList;
import com.nutomic.syncthingandroid.runtime.FolderOperationException;
import com.nutomic.syncthingandroid.model.Gui;
import com.nutomic.syncthingandroid.model.Options;
import com.nutomic.syncthingandroid.service.RestApi;
import com.nutomic.syncthingandroid.util.ConfigXml;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides a transparent access to the config if ...
 * a) Syncthing is running and REST API is available.
 * b) Syncthing is NOT running and config.xml is accessed.
 */
public class ConfigRouter {

    private static final String TAG = "ConfigRouter";

    public interface OnResultListener1<T> {
        void onResult(T t);
    }

    private final Context mContext;
    private final boolean mAllowStoppedRootAccess;

    private ConfigXml configXml;

    public ConfigRouter(Context context) {
        this(context, false);
    }

    /**
     * @param allowStoppedRootAccess true only for an explicit edit flow that needs authoritative
     *                               stopped-state config before it can expose editing controls
     */
    public ConfigRouter(Context context, boolean allowStoppedRootAccess) {
        mContext = context;
        mAllowStoppedRootAccess = allowStoppedRootAccess;
        configXml = new ConfigXml(mContext);
    }

    public List<Folder> getFolders(RestApi restApi) {
        if (restApi == null || !restApi.isConfigLoaded()) {
            // Syncthing is not running or REST API is not (yet) available.
            if (isRootModeSelected() && !mAllowStoppedRootAccess) {
                List<Folder> projection = ConfigurationProjection.folders();
                if (projection == null) throw unavailable();
                return projection;
            }
            loadStoppedAuthoritativeConfig();
            return configXml.getFolders();
        }

        // Syncthing is running and REST API is available.
        List<Folder> folders = restApi.getFolders();
        ConfigurationProjection.update(
                folders, restApi.getDevices(false), restApi.getDevices(true)
        );
        return folders;
    }

    public List<Folder> getSharedFolders(String deviceID) {
        List<Folder> folders = getFolders(null);
        List<Folder> sharedFolders = new ArrayList<>();

        for (Folder folder : folders) {
            if (folder.getDevice(deviceID) != null) {
                // "device" is sharing "folder".
                sharedFolders.add(folder);
            }
        }

        return sharedFolders;
    }

    public void addFolder(RestApi restApi, Folder folder) {
        if (restApi == null || !restApi.isConfigLoaded()) {
            // Syncthing is not running or REST API is not (yet) available.
            loadStoppedAuthoritativeConfig();
            configXml.addFolder(folder);
            configXml.saveChanges();
            return;
        }

        // Syncthing is running and REST API is available.
        restApi.addFolder(folder);       // This will send the config afterwards.
    }

    public void ignoreFolder(RestApi restApi,
                                    final String deviceId,
                                    final String folderId,
                                    final String folderLabel) {
        if (restApi == null || !restApi.isConfigLoaded()) {
            Log.e(TAG, "ignoreFolder failed, Syncthing is not running or REST API is not (yet) available.");
            return;
        }

        // Syncthing is running and REST API is available.
        restApi.ignoreFolder(
                deviceId,
                folderId,
                folderLabel
        );       // This will send the config afterwards.
    }

    public void updateFolder(RestApi restApi, final Folder folder) {
        if (restApi == null || !restApi.isConfigLoaded()) {
            // Syncthing is not running or REST API is not (yet) available.
            loadStoppedAuthoritativeConfig();
            configXml.updateFolder(folder);
            configXml.saveChanges();
            return;
        }

        // Syncthing is running and REST API is available.
        restApi.updateFolder(folder);       // This will send the config afterwards.
    }

    public void removeFolder(RestApi restApi, final String folderId) {
        if (restApi == null || !restApi.isConfigLoaded()) {
            // Syncthing is not running or REST API is not (yet) available.
            loadStoppedAuthoritativeConfig();
            configXml.removeFolder(folderId);
            configXml.saveChanges();
            return;
        }

        // Syncthing is running and REST API is available.
        restApi.removeFolder(folderId);       // This will send the config afterwards.
    }

    /**
     * Gets the ignore list of one configured folder from the authoritative source.
     *
     * <p>When Syncthing is not reachable the list is read through the selected backend, which
     * resolves the authoritative path of the folder itself. A read that fails raises instead of
     * delivering an empty list, so a read failure is never shown as an empty ignore list.</p>
     *
     * @throws FolderOperationException when the ignore list exists but cannot be read
     */
    public void getFolderIgnoreList(RestApi restApi, Folder folder, OnResultListener1<FolderIgnoreList> listener)
            throws FolderOperationException {
        if (restApi == null || !restApi.isConfigLoaded()) {
            // Syncthing is not running or REST API is not (yet) available.
            loadStoppedAuthoritativeConfig();
            configXml.getFolderIgnoreList(folder, folderIgnoreList -> listener.onResult(folderIgnoreList));
            return;
        }

        // Syncthing is running and REST API is available.
        restApi.getFolderIgnoreList(folder.id, folderIgnoreList -> listener.onResult(folderIgnoreList));
    }

    /**
     * Stores the ignore list of one configured folder in the authoritative source.
     *
     * @throws FolderOperationException when the ignore list cannot be written
     */
    public void postFolderIgnoreList(RestApi restApi, Folder folder, String[] ignore)
            throws FolderOperationException {
        if (restApi == null || !restApi.isConfigLoaded()) {
            // Syncthing is not running or REST API is not (yet) available.
            loadStoppedAuthoritativeConfig();
            configXml.postFolderIgnoreList(folder, ignore);
            return;
        }

        // Syncthing is running and REST API is available.
        restApi.postFolderIgnoreList(folder.id, ignore);
    }

    public List<Device> getDevices(RestApi restApi, Boolean includeLocal) {
        List<Device> devices;
        List<Folder> folders;

        if (restApi == null || !restApi.isConfigLoaded()) {
            // Syncthing is not running or REST API is not (yet) available.
            if (isRootModeSelected() && !mAllowStoppedRootAccess) {
                devices = ConfigurationProjection.devices(includeLocal);
                if (devices == null) throw unavailable();
            } else {
                loadStoppedAuthoritativeConfig();
                devices = configXml.getDevices(includeLocal);
            }
        } else {
            // Syncthing is running and REST API is available.
            devices = restApi.getDevices(includeLocal);
            ConfigurationProjection.update(
                    restApi.getFolders(), restApi.getDevices(false), restApi.getDevices(true)
            );
        }

        return devices;
    }

    public void updateDevice(RestApi restApi, final Device device) {
        if (restApi == null || !restApi.isConfigLoaded()) {
            // Syncthing is not running or REST API is not (yet) available.
            loadStoppedAuthoritativeConfig();
            configXml.updateDevice(device);
            configXml.saveChanges();
            return;
        }

        // Syncthing is running and REST API is available.
        restApi.updateDevice(device);       // This will send the config afterwards.
    }

    public void removeDevice(RestApi restApi, final String deviceID) {
        if (restApi == null || !restApi.isConfigLoaded()) {
            // Syncthing is not running or REST API is not (yet) available.
            loadStoppedAuthoritativeConfig();
            configXml.removeDevice(deviceID);
            configXml.saveChanges();
            return;
        }

        // Syncthing is running and REST API is available.
        restApi.removeDevice(deviceID);       // This will send the config afterwards.
    }

    public void ignoreDevice(RestApi restApi,
                                    final String deviceID,
                                    final String deviceName,
                                    final String deviceAddress) {
        if (restApi == null || !restApi.isConfigLoaded()) {
            Log.e(TAG, "ignoreDevice failed, Syncthing is not running or REST API is not (yet) available.");
            return;
        }

        // Syncthing is running and REST API is available.
        restApi.ignoreDevice(
                deviceID,
                deviceName,
                deviceAddress
        );       // This will send the config afterwards.
    }

    public Gui getGui(RestApi restApi) {
        if (restApi == null || !restApi.isConfigLoaded()) {
            // Syncthing is not running or REST API is not (yet) available.
            loadStoppedAuthoritativeConfig();
            return configXml.getGui();
        }

        // Syncthing is running and REST API is available.
        return restApi.getGui();
    }

    public void updateGui(RestApi restApi, final Gui gui) {
        if (restApi == null || !restApi.isConfigLoaded()) {
            // Syncthing is not running or REST API is not (yet) available.
            loadStoppedAuthoritativeConfig();
            configXml.updateGui(gui);
            configXml.saveChanges();
            return;
        }

        // Syncthing is running and REST API is available.
        restApi.updateGui(gui);       // This will send the config afterwards.
    }

    public Options getOptions(RestApi restApi) {
        if (restApi == null || !restApi.isConfigLoaded()) {
            // Syncthing is not running or REST API is not (yet) available.
            loadStoppedAuthoritativeConfig();
            return configXml.getOptions();
        }

        // Syncthing is running and REST API is available.
        return restApi.getOptions();
    }

    private void loadStoppedAuthoritativeConfig() throws ConfigXml.OpenConfigException {
        if (isRootModeSelected() && !mAllowStoppedRootAccess) throw unavailable();
        configXml.loadConfig();
    }

    private boolean isRootModeSelected() {
        return PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean(
                com.nutomic.syncthingandroid.service.Constants.PREF_USE_ROOT, false
        );
    }

    private static ConfigurationUnavailableException unavailable() {
        return new ConfigurationUnavailableException(
                "The authoritative config is unavailable while Superuser Mode is stopped. "
                        + "Open an explicit edit action to authorize access."
        );
    }

}
