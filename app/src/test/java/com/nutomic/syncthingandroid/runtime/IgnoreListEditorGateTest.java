package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Deterministic tests for the rule that protects the ignore-list editor from a late read.
 *
 * <p>The rule exists because a read can answer long after it was asked for: the answer may arrive
 * after the editor was left, after it moved to another folder, or after the user typed new ignore
 * patterns. Each of those cases has its own verdict, so a caller can tell why an answer was
 * dropped.</p>
 */
public class IgnoreListEditorGateTest {
    @Test
    public void anAnswerForACurrentEditorIsApplied() {
        assertEquals(
                IgnoreListEditorGate.Verdict.APPLY,
                IgnoreListEditorGate.verdict(true, true, false)
        );
    }

    @Test
    public void anAnswerIsDroppedWhenTheEditorIsGone() {
        assertEquals(
                IgnoreListEditorGate.Verdict.EDITOR_GONE,
                IgnoreListEditorGate.verdict(false, true, false)
        );
        assertEquals(
                "a destroyed editor is reported before the folder is even compared",
                IgnoreListEditorGate.Verdict.EDITOR_GONE,
                IgnoreListEditorGate.verdict(false, false, true)
        );
    }

    @Test
    public void anAnswerIsDroppedWhenTheEditorShowsAnotherFolder() {
        assertEquals(
                IgnoreListEditorGate.Verdict.FOLDER_CHANGED,
                IgnoreListEditorGate.verdict(true, false, false)
        );
        assertEquals(
                "the folder verdict is decided before the unsaved-edits verdict",
                IgnoreListEditorGate.Verdict.FOLDER_CHANGED,
                IgnoreListEditorGate.verdict(true, false, true)
        );
    }

    @Test
    public void anAnswerIsDroppedWhenTheUserEditedTheIgnoreList() {
        assertEquals(
                IgnoreListEditorGate.Verdict.UNSAVED_EDITS,
                IgnoreListEditorGate.verdict(true, true, true)
        );
    }

    @Test
    public void theEditorAcceptsEditsOnlyForADeliveredListOfAWritableFolder() {
        assertTrue(IgnoreListEditorGate.acceptsEdits(true, true));
        assertFalse(
                "a list that was never delivered must not be editable",
                IgnoreListEditorGate.acceptsEdits(false, true)
        );
        assertFalse(
                "a failed read must not leave an editable list behind",
                IgnoreListEditorGate.acceptsEdits(false, false)
        );
        assertFalse(
                "a folder that was not proven writable keeps the editor closed",
                IgnoreListEditorGate.acceptsEdits(true, false)
        );
    }

    @Test
    public void theIgnoreListIsReadAgainOnlyForAPathThatWasNeverRead() {
        assertTrue(
                "a path chosen after the read of the replaced path was cancelled is read",
                IgnoreListEditorGate.needsRead(false, false, "/sdcard/new", null)
        );
        assertTrue(
                "a path another read ran for is read again",
                IgnoreListEditorGate.needsRead(false, false, "/sdcard/new", "/sdcard/old")
        );
        assertFalse(
                "the path a read is already running for is not read twice",
                IgnoreListEditorGate.needsRead(false, false, "/sdcard/old", "/sdcard/old")
        );
        assertFalse(
                "an editor that holds its list keeps the list it shows",
                IgnoreListEditorGate.needsRead(false, true, "/sdcard/new", "/sdcard/old")
        );
        assertFalse(
                "a folder that is still being created has no list to read yet",
                IgnoreListEditorGate.needsRead(true, false, "/sdcard/new", null)
        );
    }

    @Test
    public void anAnswerForAPathTheEditorReplacedIsDropped() {
        assertTrue(
                "an answer for the folder and path the editor shows belongs to it",
                IgnoreListEditorGate.readBelongsToShownFolder(
                        "folder-1", "/sdcard/old", "folder-1", "/sdcard/old"
                )
        );
        assertFalse(
                "the same folder identifier does not make the replaced path's answer current",
                IgnoreListEditorGate.readBelongsToShownFolder(
                        "folder-1", "/sdcard/old", "folder-1", "/sdcard/new"
                )
        );
        assertFalse(
                "an answer for another folder does not belong to the editor",
                IgnoreListEditorGate.readBelongsToShownFolder(
                        "folder-1", "/sdcard/old", "folder-2", "/sdcard/old"
                )
        );
        assertFalse(
                "an answer that arrives with no folder shown belongs to no one",
                IgnoreListEditorGate.readBelongsToShownFolder(
                        "folder-1", "/sdcard/old", null, null
                )
        );
    }

    @Test
    public void aDeliveredListOfAReplacedPathNoLongerAcceptsEdits() {
        assertTrue(
                "a delivered list of the replaced path belongs to another path",
                IgnoreListEditorGate.deliveredListIsForAnotherPath(true, "/sdcard/new", "/sdcard/old")
        );
        assertFalse(
                "a delivered list of the path shown now keeps accepting edits",
                IgnoreListEditorGate.deliveredListIsForAnotherPath(true, "/sdcard/old", "/sdcard/old")
        );
        assertFalse(
                "an editor that holds no delivered list has nothing to close",
                IgnoreListEditorGate.deliveredListIsForAnotherPath(false, "/sdcard/new", "/sdcard/old")
        );
        assertFalse(
                "a list whose read path is unknown is not treated as another path's list",
                IgnoreListEditorGate.deliveredListIsForAnotherPath(true, "/sdcard/new", null)
        );
    }

    @Test
    public void theIgnoreListIsReadOnlyForAPathTheConfigurationHolds() {
        assertTrue(
                "a folder path the configuration holds is read",
                IgnoreListEditorGate.mayReadListForShownPath("/sdcard/here", "/sdcard/here")
        );
        assertFalse(
                "a path the user chose but did not save is not read, because the read resolves the configured path instead and its answer would be tagged with the chosen one",
                IgnoreListEditorGate.mayReadListForShownPath("/sdcard/here", "/sdcard/chosen")
        );
        assertFalse(
                "an editor that knows no configured path reads nothing",
                IgnoreListEditorGate.mayReadListForShownPath(null, "/sdcard/chosen")
        );
    }

    @Test
    public void ignorePatternsAreWrittenOnlyWhileTheEditorHoldsTheShownPathList() {
        assertTrue(
                "patterns the user changed in a held list are written",
                IgnoreListEditorGate.saveWritesIgnoreList(true, true)
        );
        assertFalse(
                "a save without changed patterns writes no list",
                IgnoreListEditorGate.saveWritesIgnoreList(true, false)
        );
        assertFalse(
                "patterns typed before the folder path was replaced are not written to the path shown now",
                IgnoreListEditorGate.saveWritesIgnoreList(false, true)
        );
    }

    @Test
    public void aHeldListIsKeptOnlyForTheConfiguredPathItWasReadFor() {
        assertTrue(
                "a list held for the shown path the configuration holds is kept",
                IgnoreListEditorGate.heldListBelongsToShownPath(
                        "/sdcard/here", "/sdcard/here", "/sdcard/here"
                )
        );
        assertFalse(
                "a list held for a path the editor replaced is not kept",
                IgnoreListEditorGate.heldListBelongsToShownPath(
                        "/sdcard/here", "/sdcard/chosen", "/sdcard/chosen"
                )
        );
        assertFalse(
                "a held list is not kept once the configuration holds another path",
                IgnoreListEditorGate.heldListBelongsToShownPath(
                        "/sdcard/here", "/sdcard/other", "/sdcard/here"
                )
        );
        assertFalse(
                "an editor that holds no read list keeps none",
                IgnoreListEditorGate.heldListBelongsToShownPath(null, "/sdcard/here", "/sdcard/here")
        );
    }
}
