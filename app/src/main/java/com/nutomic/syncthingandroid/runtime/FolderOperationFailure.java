package com.nutomic.syncthingandroid.runtime;

/**
 * Stable reasons a configured-folder operation can fail.
 *
 * <p>Callers use these values to decide how to react - for example whether to retry, whether to
 * show a user-visible message, or whether to keep previously displayed data unchanged - without
 * depending on exception messages or on the details of the selected privilege backend.</p>
 */
public enum FolderOperationFailure {
    /** The authoritative configuration contains no folder with the requested identifier. */
    FOLDER_NOT_CONFIGURED,
    /** The authoritative configuration could not be read or parsed. */
    FOLDER_CONFIGURATION_UNREADABLE,
    /** The configured folder path could not be used for the requested operation. */
    FOLDER_ACCESS_FAILED,
    /** A bounded operation stopped because one of its entry, match, output, or time budgets ran out. */
    FOLDER_OPERATION_LIMIT_EXCEEDED,
    /** The operation was cancelled, interrupted, or lost its privileged session before it finished. */
    FOLDER_OPERATION_CANCELLED,
    /** A folder member needed by the operation has an unsafe kind, such as a symbolic link. */
    FOLDER_MEMBER_UNSAFE,
    /** The approved sync-completion script set could not be dispatched. */
    SCRIPT_DISPATCH_FAILED,
    /** Explicit root-script authorization was denied by the root manager. */
    ROOT_SCRIPT_AUTH_DENIED,
    /** A previously available root-script authorization was lost or was not UID 0. */
    ROOT_SCRIPT_AUTH_LOST,
    /** The bounded root-script authorization attempt timed out. */
    ROOT_SCRIPT_AUTH_TIMEOUT,
    /** Root-script authorization or transport was unavailable. */
    ROOT_SCRIPT_AUTH_UNAVAILABLE,
    /** The service closed backend operations while changing execution mode. */
    EXECUTION_MODE_TRANSITION_BUSY
}
