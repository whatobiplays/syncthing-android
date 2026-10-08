package com.nutomic.syncthingandroid.runtime;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * One member of the closed Syncthing Managed State manifest.
 *
 * <p>Managed State is the bounded set of files and directories that make up one Syncthing
 * installation's identity and configuration. It is deliberately closed: privileged
 * operations may act on these members and on nothing else, so no caller may add a member or
 * supply a path of its own. Every privileged filesystem operation is expressed in terms of this
 * vocabulary rather than in terms of a path.</p>
 *
 * <p>The member names are the names the bundled Syncthing binary uses inside its configuration
 * directory. {@link com.nutomic.syncthingandroid.service.Constants} exposes the same names as
 * individual constants, and a unit test pins the two definitions together so they cannot drift
 * apart.</p>
 */
public enum ManagedStateMember {
    /** The Syncthing configuration document. */
    CONFIG("config.xml", Kind.FILE),
    /** The device certificate of this installation. */
    CERT("cert.pem", Kind.FILE),
    /** The device private key of this installation. */
    KEY("key.pem", Kind.FILE),
    /** Optional user-supplied Web GUI certificate. */
    HTTPS_CERT("https-cert.pem", Kind.FILE),
    /** Optional user-supplied Web GUI private key. */
    HTTPS_KEY("https-key.pem", Kind.FILE),
    /** The Syncthing database directory. */
    INDEX("index-v2", Kind.DIRECTORY);

    /** Expected filesystem kind of one member. */
    public enum Kind {
        FILE,
        DIRECTORY
    }

    private static final Set<ManagedStateMember> FILE_MEMBERS =
            Collections.unmodifiableSet(EnumSet.of(CONFIG, CERT, KEY, HTTPS_CERT, HTTPS_KEY));

    private final String fileName;
    private final Kind kind;

    ManagedStateMember(String fileName, Kind kind) {
        this.fileName = fileName;
        this.kind = kind;
    }

    /** Returns the fixed name of this member inside the Managed State root. */
    public String fileName() {
        return fileName;
    }

    /** Returns the filesystem kind this member must have. */
    public Kind kind() {
        return kind;
    }

    /** Returns whether this member is a directory. */
    public boolean isDirectory() {
        return kind == Kind.DIRECTORY;
    }

    /** Returns whether this member is a regular file. */
    public boolean isFile() {
        return kind == Kind.FILE;
    }

    /** Returns every member that is a regular file. */
    public static Set<ManagedStateMember> fileMembers() {
        return FILE_MEMBERS;
    }

    /** Returns the member with the given fixed file name, or {@code null} when it is unknown. */
    public static ManagedStateMember forFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        for (ManagedStateMember member : values()) {
            if (member.fileName.equals(fileName)) {
                return member;
            }
        }
        return null;
    }
}
