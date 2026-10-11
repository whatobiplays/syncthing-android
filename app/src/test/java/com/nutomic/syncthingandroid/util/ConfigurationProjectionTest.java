package com.nutomic.syncthingandroid.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import com.nutomic.syncthingandroid.model.Device;
import com.nutomic.syncthingandroid.model.Folder;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;

/** Verifies the stopped-state configuration projection remains transient and display-only. */
public class ConfigurationProjectionTest {

    @After
    public void clearProjection() {
        ConfigurationProjection.clear();
    }

    @Test
    public void projectionReturnsIndependentCopiesOfActiveRestSnapshots() {
        Folder folder = new Folder();
        folder.id = "folder-id";
        Device remote = new Device();
        remote.deviceID = "remote-id";
        Device local = new Device();
        local.deviceID = "local-id";

        ConfigurationProjection.update(
                Collections.singletonList(folder),
                Collections.singletonList(remote),
                java.util.Arrays.asList(local, remote)
        );

        Folder projectedFolder = ConfigurationProjection.folders().get(0);
        Device projectedRemote = ConfigurationProjection.devices(false).get(0);
        assertNotSame(folder, projectedFolder);
        assertNotSame(remote, projectedRemote);
        assertEquals("folder-id", projectedFolder.id);
        assertEquals("remote-id", projectedRemote.deviceID);
        assertEquals(2, ConfigurationProjection.devices(true).size());
    }

    @Test
    public void processRestartOrImportCanClearTheProjectionWithoutInventingEmptyConfig() {
        assertNull(ConfigurationProjection.folders());
        assertNull(ConfigurationProjection.devices(false));

        ConfigurationProjection.update(
                Collections.<Folder>emptyList(),
                Collections.<Device>emptyList(),
                Collections.<Device>emptyList()
        );
        ConfigurationProjection.clear();

        assertNull(ConfigurationProjection.folders());
        assertNull(ConfigurationProjection.devices(false));
    }
}
