package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.Test;

/**
 * Covers root-capable process inspection used by recovery and by pre-signal re-verification.
 *
 * <p>The tests run against the deterministic fake device, so ambiguity, unreadable metadata, and
 * duplicate run tokens are reproduced exactly instead of by racing a real {@code /proc}.</p>
 */
public class RootExecutionInspectorTest {

    private static final String BINARY = "/data/user/0/app/files/libsyncthingnative.so";

    @Test
    public void candidateDiscoveryMatchesBundledBasenameAndSkipsUnreadableLinks() throws IOException {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        device.addBundledCandidate(null);
        device.addProcess(null, 9001, null);
        RootExecutionInspector inspector = inspector(device);

        List<ExecutionIdentity> candidates = inspector.findBundledCandidates(BINARY);

        assertEquals(1, candidates.size());
        assertEquals(FakeRootTransport.Device.BUNDLED_PATH, candidates.get(0).executablePath());
        assertEquals("", candidates.get(0).runToken());
    }

    @Test
    public void candidateWithUnreadableStartTimeFailsClosed() {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        device.addProcess(FakeRootTransport.Device.BUNDLED_PATH, 0, null);
        RootExecutionInspector inspector = inspector(device);

        assertThrows(IOException.class, () -> inspector.findBundledCandidates(BINARY));
    }

    @Test
    public void candidateDiscoveryFailureSurfacesAsIoFailure() {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        device.failProcessListing = true;
        RootExecutionInspector inspector = inspector(device);

        assertThrows(IOException.class, () -> inspector.findBundledCandidates(BINARY));
    }

    @Test
    public void launchLookupNeedsExactPathAndRunToken() throws IOException {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        device.addProcess(FakeRootTransport.Device.BUNDLED_PATH, 9001, "token-a");
        RootExecutionInspector inspector = inspector(device);

        assertNull(
                "a candidate that only shares the basename is not this launch",
                inspector.findLaunchedProcess(BINARY, "token-a")
        );

        FakeRootTransport.Entry launched = device.addProcess(BINARY, 9002, "token-a");
        ExecutionIdentity found = inspector.findLaunchedProcess(BINARY, "token-a");

        assertEquals(launched.pid, found.pid());
        assertEquals(9002, found.processStartTimeTicks());
        assertEquals("token-a", found.runToken());
        assertNull(inspector.findLaunchedProcess(BINARY, "token-b"));
    }

    @Test
    public void duplicateRunTokenIsReportedInsteadOfPicked() {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        device.addProcess(BINARY, 9001, "token-a");
        device.addProcess(BINARY, 9002, "token-a");
        RootExecutionInspector inspector = inspector(device);

        assertThrows(IOException.class, () -> inspector.findLaunchedProcess(BINARY, "token-a"));
    }

    @Test
    public void launchedProcessWithUnreadableStartTimeFailsClosed() {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        device.addProcess(BINARY, 0, "token-a");
        RootExecutionInspector inspector = inspector(device);

        assertThrows(IOException.class, () -> inspector.findLaunchedProcess(BINARY, "token-a"));
    }

    @Test
    public void inspectionReportsAbsentAndUnknownStates() throws IOException {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        RootExecutionInspector inspector = inspector(device);

        assertEquals(
                ExecutionInspector.InspectionResult.Status.PROCESS_ABSENT,
                inspector.inspect(4711).status()
        );

        FakeRootTransport.Entry unreadable = device.addProcess(null, 9001, null);
        assertEquals(
                ExecutionInspector.InspectionResult.Status.UNKNOWN,
                inspector.inspect(unreadable.pid).status()
        );

        FakeRootTransport.Entry live = device.addBundledCandidate("token-a");
        ExecutionInspector.InspectionResult result = inspector.inspect(live.pid);
        assertEquals(ExecutionInspector.InspectionResult.Status.LIVE, result.status());
        assertEquals("token-a", result.identity().runToken());
        assertEquals(FakeRootTransport.Device.BOOT_ID, result.identity().bootId());
    }

    @Test
    public void bootIdentifierFailureSurfacesAsIoFailure() {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        device.failBootIdRead = true;
        RootExecutionInspector inspector = inspector(device);

        assertThrows(IOException.class, inspector::currentBootId);
    }

    @Test
    public void everyInspectionUsesOneOperationScopedShell() throws IOException {
        FakeRootTransport.Device device = new FakeRootTransport.Device();
        device.addBundledCandidate(null);
        RootExecutionInspector inspector = inspector(device);

        inspector.findBundledCandidates(BINARY);
        inspector.currentBootId();
        inspector.inspect(1);

        assertEquals("helper shells must be closed when the operation ends", 3, device.shellCloses);
    }

    private static RootExecutionInspector inspector(FakeRootTransport.Device device) {
        FakeRootTransport.Factory factory = new FakeRootTransport.Factory(device);
        return new RootExecutionInspector(() -> factory.acquire(60_000));
    }
}

