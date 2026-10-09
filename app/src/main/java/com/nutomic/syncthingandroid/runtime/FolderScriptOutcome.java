package com.nutomic.syncthingandroid.runtime;

import java.util.Objects;

/**
 * Result of running one approved script from a folder's {@code .stfolder} directory.
 *
 * <p>Scripts are user-provided programs that run outside Syncthing's own process ownership, so a
 * non-zero status is reported instead of being raised: sync-completion notifications must still
 * reach the user when a script fails, and the failure reason stays attributable to one named
 * script.</p>
 */
public final class FolderScriptOutcome {
    private final String scriptName;
    private final int exitStatus;

    private FolderScriptOutcome(String scriptName, int exitStatus) {
        this.scriptName = Objects.requireNonNull(scriptName, "The script name is required");
        this.exitStatus = exitStatus;
    }

    public static FolderScriptOutcome of(String scriptName, int exitStatus) {
        return new FolderScriptOutcome(scriptName, exitStatus);
    }

    /** Returns the file name of the script, without its directory. */
    public String scriptName() {
        return scriptName;
    }

    /** Returns the script process exit status; {@code 0} means the script reported success. */
    public int exitStatus() {
        return exitStatus;
    }

    /** Returns whether the script reported success. */
    public boolean succeeded() {
        return exitStatus == 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FolderScriptOutcome)) {
            return false;
        }
        FolderScriptOutcome that = (FolderScriptOutcome) other;
        return exitStatus == that.exitStatus && scriptName.equals(that.scriptName);
    }

    @Override
    public int hashCode() {
        return 31 * scriptName.hashCode() + exitStatus;
    }
}
