package com.nutomic.syncthingandroid.runtime;

import android.content.Context;

import java.io.File;
import java.util.Objects;

/**
 * The fixed Managed State directories and the application's own Linux identity.
 *
 * <p>Managed State lives in the application's files directory and transfers live in one dedicated
 * staging base below the application's cache directory. Privileged operations create private
 * scratch directories inside the Managed State directory so atomic replacements remain on the
 * same filesystem and encryption policy as their live destinations. The application locations are
 * derived from the application context once and then passed explicitly, so the state logic itself
 * never needs a {@code Context} and stays testable on a plain JVM with temporary directories.</p>
 *
 * <p>The application identity is carried here because a privileged snapshot has to hand staged
 * copies back to this process. The uid and gid of an Android application are the same numeric
 * value, which is why both accessors return {@code ApplicationInfo.uid}.</p>
 */
public final class ManagedStateLocations {
    /** Name of the staging base below the application cache directory. */
    static final String STAGING_DIRECTORY = "managed-state-transfer";
    private final File stateRoot;
    private final File stagingBase;
    private final int applicationUid;
    private final int applicationGid;

    /** Derives the fixed locations of one application context. */
    public static ManagedStateLocations forApplication(Context context) {
        Context applicationContext = context.getApplicationContext();
        int uid = applicationContext.getApplicationInfo().uid;
        return new ManagedStateLocations(
                applicationContext.getFilesDir(),
                new File(applicationContext.getCacheDir(), STAGING_DIRECTORY),
                uid,
                uid
        );
    }

    public ManagedStateLocations(
            File stateRoot,
            File stagingBase,
            int applicationUid,
            int applicationGid
    ) {
        this.stateRoot = Objects.requireNonNull(stateRoot).getAbsoluteFile();
        this.stagingBase = Objects.requireNonNull(stagingBase).getAbsoluteFile();
        this.applicationUid = applicationUid;
        this.applicationGid = applicationGid;
    }

    /** Returns the directory that holds Managed State. */
    public File stateRoot() {
        return stateRoot;
    }

    /** Rejects a missing, non-directory, or redirected Managed State root before file access. */
    void requireSafeStateRoot(ManagedStateFailure failure) throws ManagedStateException {
        if (ManagedStateStaging.isSymbolicLink(stateRoot) || !stateRoot.isDirectory()) {
            throw new ManagedStateException(
                    failure,
                    "The Managed State root is not a real directory"
            );
        }
    }

    /** Returns the fixed path of one approved member inside Managed State. */
    public File member(ManagedStateMember member) {
        return new File(stateRoot, member.fileName());
    }

    /** Returns the base directory that holds operation-owned transfer directories. */
    public File stagingBase() {
        return stagingBase;
    }

    /** Returns the Linux uid of this application. */
    public int applicationUid() {
        return applicationUid;
    }

    /** Returns the Linux gid of this application. */
    public int applicationGid() {
        return applicationGid;
    }

    /** Creates or verifies the shared staging base from the application process. */
    void prepareStagingBase() throws ManagedStateException {
        ManagedStateStaging.prepareBase(stagingBase);
    }

    /** Creates one fresh operation-owned transfer directory below the staging base. */
    public ManagedStateStaging newStaging() throws ManagedStateException {
        return ManagedStateStaging.create(stagingBase);
    }
}
