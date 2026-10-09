package com.nutomic.syncthingandroid.runtime;

/**
 * Decides whether the late answer of one ignore-list read may still replace the editor contents.
 *
 * <p>Reading one folder's ignore list is never instant: in Superuser Mode the read first acquires a
 * bounded helper session and answers through a callback afterwards. The user keeps working while
 * that runs, so the answer may arrive after the editor was left, after the editor moved to another
 * folder, or after the user typed new ignore patterns. Replacing the editor contents in any of
 * those cases would discard input the user can still see, so the answer is dropped instead.</p>
 *
 * <p>The rule is held apart from the editor, and it uses no Android type, so it can be verified in
 * plain JVM tests. The request generation is deliberately not part of this rule: whoever hands out
 * the answer already decides whether the request it belongs to is still wanted.</p>
 *
 * <p>The same rules also guard the folder path an ignore-list read and a save may work on. A read
 * resolves the folder's path from the authoritative configuration instead of from the path the
 * editor shows, so a read is only requested while the editor shows the configured path, and a save
 * only writes the ignore patterns the editor holds for the path it shows.</p>
 */
public final class IgnoreListEditorGate {
    /** Outcome of one late ignore-list answer. */
    public enum Verdict {
        /** The answer may be applied to the editor. */
        APPLY,
        /** The editor is finishing or destroyed, so nothing may touch its views. */
        EDITOR_GONE,
        /** The editor shows another folder than the one that was read. */
        FOLDER_CHANGED,
        /** The editor holds ignore patterns the user changed and has not saved. */
        UNSAVED_EDITS
    }

    private IgnoreListEditorGate() {
    }

    /**
     * Decides one late answer.
     *
     * @param editorAlive          whether the editor still exists and is neither finishing nor
     *                             destroyed
     * @param showsReadFolder      whether the editor still shows the folder whose list was read
     * @param hasUnsavedIgnoreList whether the editor holds ignore patterns the user changed
     * @return the verdict, which is {@link Verdict#APPLY} only while the answer cannot overwrite
     *         anything newer than itself
     */
    public static Verdict verdict(
            boolean editorAlive,
            boolean showsReadFolder,
            boolean hasUnsavedIgnoreList
    ) {
        if (!editorAlive) {
            return Verdict.EDITOR_GONE;
        }
        if (!showsReadFolder) {
            return Verdict.FOLDER_CHANGED;
        }
        if (hasUnsavedIgnoreList) {
            return Verdict.UNSAVED_EDITS;
        }
        return Verdict.APPLY;
    }

    /**
     * Decides whether the ignore-list editor may accept edits.
     *
     * <p>The editor opens for edits only while it holds the list that was read and the folder was
     * proven writable. A read that has not answered yet keeps the editor closed, because the user
     * would otherwise rewrite rules that were never shown, and a read that failed keeps it closed
     * for good, so a later writeability verdict cannot reopen an editor whose list is missing.</p>
     *
     * @param listDelivered whether the editor holds the list that was read for its folder
     * @param writeabilityProven whether the selected backend proved the folder writable
     * @return whether edits may be accepted
     */
    public static boolean acceptsEdits(boolean listDelivered, boolean writeabilityProven) {
        return listDelivered && writeabilityProven;
    }

    /**
     * Decides whether one ignore list still has to be read for the path the user chose.
     *
     * <p>A read that is still in flight while the user replaces the folder path is discarded, and
     * the editor stays closed to edits until a list for the current path arrives. The list of a
     * folder confirmed for another path therefore has to be read again, or its ignore list could
     * never be edited in that editor session.</p>
     *
     * @param createMode whether the editor creates a new folder, whose list cannot be read yet
     * @param listDelivered whether the editor already holds a list read for its folder
     * @param chosenPath path the user chose for the folder
     * @param lastReadPath path the ignore list was last requested for, or {@code null} while none
     *     was requested
     * @return whether the ignore list has to be read for the chosen path
     */
    public static boolean needsRead(
            boolean createMode,
            boolean listDelivered,
            String chosenPath,
            String lastReadPath
    ) {
        if (createMode || listDelivered) {
            return false;
        }
        return !chosenPath.equals(lastReadPath);
    }

    /**
     * Whether one ignore-list read still belongs to the folder the editor shows.
     *
     * <p>A read is bound to both the identifier of the folder and the path it was requested for.
     * The identifier alone is not enough: it stays the same while the user replaces the folder
     * path, and an answer for the replaced path would then install the rules of a folder the user
     * no longer edits into the editor, which a save could write to the path shown now.</p>
     *
     * @param readFolderId identifier of the folder whose ignore list was read
     * @param readPath path the ignore list was read for
     * @param shownFolderId identifier of the folder the editor shows, or {@code null} while none is
     *     shown
     * @param shownPath path of the folder the editor shows, or {@code null} while none is shown
     * @return whether the answer still describes the folder the editor shows
     */
    public static boolean readBelongsToShownFolder(
            String readFolderId,
            String readPath,
            String shownFolderId,
            String shownPath
    ) {
        return shownFolderId != null
                && shownFolderId.equals(readFolderId)
                && shownPath != null
                && shownPath.equals(readPath);
    }

    /**
     * Whether the list the editor holds belongs to a path the user has replaced.
     *
     * <p>The editor keeps a delivered list only while it belongs to the path the editor shows.
     * Once the chosen path is a different one, that list may no longer be edited towards a save,
     * because the save would write the rules of the replaced path to the path chosen now. A list
     * counts as another path's list only while the path it was read for is known, so a list
     * restored without a recorded read path is never treated as one.</p>
     *
     * @param listDelivered whether the editor holds a list that was read
     * @param chosenPath path the editor shows now
     * @param lastReadPath path the held list was read for, or {@code null} while the read path is
     *     unknown
     * @return whether the held list belongs to another path
     */
    public static boolean deliveredListIsForAnotherPath(
            boolean listDelivered,
            String chosenPath,
            String lastReadPath
    ) {
        return listDelivered && lastReadPath != null && !lastReadPath.equals(chosenPath);
    }

    /**
     * Whether one ignore-list read may be requested for the path the editor shows.
     *
     * <p>An ignore-list read resolves the folder's path from the authoritative configuration, not
     * from the path the editor shows. While the two differ, which happens after the user chose
     * another folder path but before the folder is saved, such a read can only deliver another
     * path's rules, and that answer, tagged with the path it was requested for, would pass as the
     * shown path's list afterwards. The read is therefore not requested at all until the
     * configuration holds the shown path.</p>
     *
     * @param configuredPath path the authoritative configuration holds for the edited folder, or
     *     {@code null} while no configured path is known
     * @param shownPath path the editor shows for the folder
     * @return whether the ignore list may be read for the shown path
     */
    public static boolean mayReadListForShownPath(String configuredPath, String shownPath) {
        return configuredPath != null && configuredPath.equals(shownPath);
    }

    /**
     * Whether one save may write the ignore-list rules the editor holds.
     *
     * <p>A save writes the rules to the folder path the configuration holds, so it may only carry
     * rules that were read for the path the editor shows. Patterns the user typed while another
     * path was configured stay in the editor and are reported as the part of the save that was not
     * written, because writing them would replace the rules of a folder whose ignore list the user
     * never saw.</p>
     *
     * @param listDelivered whether the editor holds a list that was read for its folder
     * @param hasUnsavedIgnoreList whether the editor holds ignore patterns the user changed
     * @return whether the save may write the ignore list
     */
    public static boolean saveWritesIgnoreList(boolean listDelivered, boolean hasUnsavedIgnoreList) {
        return listDelivered && hasUnsavedIgnoreList;
    }

    /**
     * Whether the editor may keep editing the list it already holds for the path it shows.
     *
     * <p>Replacing the folder path closes the editor to edits, but the editor keeps the list content
     * it read. A path that is chosen back to the path that content was read for may keep editing
     * that content, so a discarded path does not cost the user the list. The content is only kept
     * while the configuration holds the shown path, because a later save writes it to the configured
     * path.</p>
     *
     * @param heldPath path the list the editor holds was read for, or {@code null} while the editor
     *     holds no read list
     * @param configuredPath path the authoritative configuration holds for the edited folder, or
     *     {@code null} while no configured path is known
     * @param shownPath path the editor shows for the folder
     * @return whether the editor may keep editing the list it holds
     */
    public static boolean heldListBelongsToShownPath(
            String heldPath,
            String configuredPath,
            String shownPath
    ) {
        return heldPath != null
                && heldPath.equals(shownPath)
                && configuredPath != null
                && configuredPath.equals(shownPath);
    }
}
