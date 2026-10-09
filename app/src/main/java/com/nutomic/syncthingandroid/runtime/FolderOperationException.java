package com.nutomic.syncthingandroid.runtime;

import java.util.Objects;

/**
 * Typed failure of one configured-folder operation.
 *
 * <p>Folder operations cross the privilege boundary, so a caller cannot inspect the filesystem
 * itself to find out what went wrong. This exception carries the stable reason a caller can act
 * on, while the message keeps the backend-specific detail for logging.</p>
 */
public final class FolderOperationException extends Exception {
    private static final long serialVersionUID = 1L;

    private final FolderOperationFailure failure;

    public FolderOperationException(FolderOperationFailure failure, String message) {
        super(Objects.requireNonNull(message, "The failure message is required"));
        this.failure = Objects.requireNonNull(failure, "The failure reason is required");
    }

    public FolderOperationException(
            FolderOperationFailure failure,
            String message,
            Throwable cause
    ) {
        super(
                Objects.requireNonNull(message, "The failure message is required"),
                Objects.requireNonNull(cause, "The failure cause is required")
        );
        this.failure = Objects.requireNonNull(failure, "The failure reason is required");
    }

    /** Returns the stable reason this operation failed. */
    public FolderOperationFailure failure() {
        return failure;
    }
}
