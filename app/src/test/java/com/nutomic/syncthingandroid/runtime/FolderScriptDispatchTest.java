package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Deterministic tests for the sync-completion script dispatch.
 *
 * <p>The tests model the caller exactly as the event processor uses it: the announcement of the
 * finished folder is the statement after the dispatch, so every test proves that the announcement
 * still runs after a script failed, after the dispatch failed, and after the dispatch was refused
 * by an orderly teardown.</p>
 */
public class FolderScriptDispatchTest {
    private static final ConfiguredFolderReference FOLDER = ConfiguredFolderReference.of("folder-1");

    @Test
    public void aScriptThatExitsNonZeroIsReportedAndTheCompletionIsStillAnnounced() {
        FolderScriptAdmission admission = new FolderScriptAdmission();
        FolderScriptDispatch dispatch = new FolderScriptDispatch(
                admission,
                (folder, event) -> Arrays.asList(
                        FolderScriptOutcome.of("backup.sh", 0),
                        FolderScriptOutcome.of("cleanup.sh", 3)
                )
        );
        List<String> reports = new ArrayList<>();
        AtomicInteger announcements = new AtomicInteger();

        assertTrue(dispatch.dispatch(FOLDER, FolderEvent.SYNC_COMPLETE, reports::add));
        announcements.incrementAndGet();

        assertEquals(1, announcements.get());
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("cleanup.sh"));
        assertTrue(reports.get(0).contains("3"));
        assertTrue("the admission is free again", admission.tryAdmit());
        admission.leave();
    }

    @Test
    public void aFailedDispatchIsReportedAndTheCompletionIsStillAnnounced() {
        FolderScriptAdmission admission = new FolderScriptAdmission();
        FolderScriptDispatch dispatch = new FolderScriptDispatch(admission, (folder, event) -> {
            throw new FolderOperationException(
                    FolderOperationFailure.SCRIPT_DISPATCH_FAILED,
                    "the configured folder is not available"
            );
        });
        List<String> reports = new ArrayList<>();
        AtomicInteger announcements = new AtomicInteger();

        assertTrue(dispatch.dispatch(FOLDER, FolderEvent.SYNC_COMPLETE, reports::add));
        announcements.incrementAndGet();

        assertEquals(1, announcements.get());
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("not available"));
        assertTrue("a failed dispatch never blocks the next one", admission.tryAdmit());
        admission.leave();
    }

    @Test
    public void anUnexpectedDispatchFailureIsAlsoReportedInsteadOfRaised() {
        FolderScriptAdmission admission = new FolderScriptAdmission();
        FolderScriptDispatch dispatch = new FolderScriptDispatch(admission, (folder, event) -> {
            throw new IllegalStateException("the runtime is not available");
        });
        List<String> reports = new ArrayList<>();
        AtomicInteger announcements = new AtomicInteger();

        dispatch.dispatch(FOLDER, FolderEvent.SYNC_COMPLETE, reports::add);
        announcements.incrementAndGet();

        assertEquals(1, announcements.get());
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("not available"));
    }

    @Test
    public void everyDispatchOfAWorkingFolderIsAnnounced() {
        FolderScriptAdmission admission = new FolderScriptAdmission();
        FolderScriptDispatch dispatch = new FolderScriptDispatch(
                admission,
                (folder, event) -> Collections.singletonList(FolderScriptOutcome.of("ok.sh", 0))
        );
        List<String> reports = new ArrayList<>();
        AtomicInteger announcements = new AtomicInteger();

        for (int attempt = 0; attempt < 3; attempt++) {
            dispatch.dispatch(FOLDER, FolderEvent.SYNC_COMPLETE, reports::add);
            announcements.incrementAndGet();
        }

        assertEquals(3, announcements.get());
        assertTrue(reports.isEmpty());
    }

    @Test
    public void aClosedAdmissionStartsNoDispatchAtAll() throws Exception {
        FolderScriptAdmission admission = new FolderScriptAdmission();
        AtomicInteger runs = new AtomicInteger();
        FolderScriptDispatch dispatch = new FolderScriptDispatch(admission, (folder, event) -> {
            runs.incrementAndGet();
            return Collections.emptyList();
        });
        List<String> reports = new ArrayList<>();
        AtomicInteger announcements = new AtomicInteger();

        assertTrue(admission.closeAndWait(0));

        assertFalse(dispatch.dispatch(FOLDER, FolderEvent.SYNC_COMPLETE, reports::add));
        announcements.incrementAndGet();

        assertEquals("the teardown prevents the dispatch from starting", 0, runs.get());
        assertTrue(reports.isEmpty());
        assertEquals(1, announcements.get());
    }
}
