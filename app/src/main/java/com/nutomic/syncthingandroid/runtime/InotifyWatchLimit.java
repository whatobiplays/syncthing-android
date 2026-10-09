package com.nutomic.syncthingandroid.runtime;

/**
 * The system-wide inotify watch limit this application asks for when the user enables the tuning.
 *
 * <p>The figure carries over from the legacy implementation, which raised the kernel default of
 * {@code fs.inotify.max_user_watches} so that large folders could still be watched. It is a target
 * and not a maximum: a system that already allows at least this many watches is left unchanged.</p>
 *
 * <p>Both backends apply the same target, and neither applies it implicitly: the value is only
 * used by an explicit request for the optional tuning.</p>
 */
final class InotifyWatchLimit {
    /** Requested minimum number of inotify watches per user. */
    static final int TARGET = 131072;

    private InotifyWatchLimit() {
    }
}
