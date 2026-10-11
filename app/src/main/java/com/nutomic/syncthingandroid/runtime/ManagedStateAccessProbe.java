package com.nutomic.syncthingandroid.runtime;

import java.io.File;
import java.util.ArrayDeque;

/** Read-only check of application-UID access to the closed Managed State manifest. */
final class ManagedStateAccessProbe {
    private static final int MAX_INDEX_ENTRIES = 100_000;
    private ManagedStateAccessProbe() {
    }

    static boolean isAccessible(ManagedStateLocations locations) {
        File root = locations.stateRoot();
        if (ManagedStateStaging.isSymbolicLink(root) || !root.isDirectory()
                || !root.canRead() || !root.canWrite() || !root.canExecute()) {
            return false;
        }
        for (ManagedStateMember member : ManagedStateMember.values()) {
            File path = locations.member(member);
            if (ManagedStateStaging.isSymbolicLink(path)) return false;
            if (!path.exists()) {
                if (member == ManagedStateMember.CONFIG) return false;
                continue;
            }
            if (member.isDirectory()) {
                if (!directoryTreeAccessible(path)) return false;
            } else if (!path.isFile() || !path.canRead() || !path.canWrite()) {
                return false;
            }
        }
        return true;
    }

    /** Checks actual descendants because a writable index root can contain root-owned files. */
    private static boolean directoryTreeAccessible(File directory) {
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(directory);
        int examined = 0;
        while (!pending.isEmpty()) {
            File entry = pending.removeFirst();
            if (++examined > MAX_INDEX_ENTRIES || ManagedStateStaging.isSymbolicLink(entry)) {
                return false;
            }
            if (entry.isDirectory()) {
                if (!entry.canRead() || !entry.canWrite() || !entry.canExecute()) return false;
                File[] children = entry.listFiles();
                if (children == null) return false;
                if (children.length > MAX_INDEX_ENTRIES - examined - pending.size()) return false;
                for (File child : children) pending.addLast(child);
            } else if (!entry.isFile() || !entry.canRead() || !entry.canWrite()) {
                return false;
            }
        }
        return true;
    }
}
