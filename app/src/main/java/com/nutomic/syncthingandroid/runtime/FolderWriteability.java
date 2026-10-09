package com.nutomic.syncthingandroid.runtime;

/**
 * Result of checking whether a folder is writable by the selected backend.
 *
 * <p>{@link #UNKNOWN} exists so a probe that could not reach a verdict - because the path is
 * missing, unreadable, or only partially checkable - is never reported as a proven negative.
 * Callers that must decide whether a folder may be used treat only {@link #WRITABLE} as
 * permission to continue.</p>
 */
public enum FolderWriteability {
    WRITABLE,
    READ_ONLY,
    UNKNOWN
}
