package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;

/**
 * Root-side folder operation that ended with a stable, caller-visible reason.
 *
 * <p>The root transport reports these outcomes as a typed failure instead of a partial result, so
 * the backend can turn them into a {@link FolderOperationException} without parsing shell output
 * or guessing from a generic transport error.</p>
 */
final class RootFolderOperationException extends IOException {
    private static final long serialVersionUID = 1L;

    private final FolderOperationFailure failure;

    RootFolderOperationException(FolderOperationFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    RootFolderOperationException(FolderOperationFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    FolderOperationFailure failure() {
        return failure;
    }
}
