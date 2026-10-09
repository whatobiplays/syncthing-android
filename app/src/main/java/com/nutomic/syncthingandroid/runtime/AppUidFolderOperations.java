package com.nutomic.syncthingandroid.runtime;

import com.nutomic.syncthingandroid.service.Constants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Folder operations that run as the application UID, in Normal Mode.
 *
 * <p>These operations never need root: the configured folder is readable and writable by the
 * application, and every step either proves that or reports a typed failure. The class holds the
 * policy - which probe strategy applies, which names count as conflicts, and which budgets bound a
 * scan - while {@link FolderNativeAccess} holds the platform mechanics, so the policy can be
 * exercised without a device.</p>
 */
final class AppUidFolderOperations {
    /**
     * Linux {@code __O_TMPFILE}, in octal {@code 020000000}.
     *
     * <p>The value is written in hexadecimal because Java source has no octal escape that reads
     * clearly here; the equivalent octal value is noted for anyone comparing it with the kernel
     * headers.</p>
     */
    static final int LINUX_O_TMPFILE = 0x400000;
    /**
     * Linux {@code O_DIRECTORY} from the generic flag layout.
     *
     * <p>Only the generic layout is named here. A kernel that lays the flag out differently
     * reports the unrecognised combination as an unsupported probe, and the caller then falls
     * back to the non-mutating access inference, so no device depends on a guessed layout.</p>
     */
    static final int LINUX_O_DIRECTORY = 0x10000;
    /** {@code O_WRONLY} access mode, which makes the probe a write attempt. */
    static final int LINUX_O_WRONLY = 0x1;
    /**
     * Flags of the unnamed temporary probe.
     *
     * <p>An unnamed temporary file needs {@code __O_TMPFILE}, an {@code O_DIRECTORY} value, and a
     * writable access mode. A kernel that does not recognise the combination, or a filesystem that
     * does not implement unnamed temporary files, reports the probe as unsupported rather than as
     * a permission failure.</p>
     */
    static final int[] TMPFILE_FLAGS = {
            LINUX_O_TMPFILE | LINUX_O_DIRECTORY | LINUX_O_WRONLY
    };
    /** Entry budget for one conflict scan. */
    static final int SCAN_MAX_ENTRIES = 200_000;
    /** Match budget for one conflict scan. */
    static final int SCAN_MAX_MATCHES = 1_000;
    /** Output budget in characters for one conflict scan. */
    static final int SCAN_MAX_OUTPUT_CHARS = 1_048_576;
    /** Time budget in nanoseconds for one conflict scan. */
    static final long SCAN_BUDGET_NANOS = 10_000_000_000L;
    /** Byte budget for the ignore-list member one operation reads or writes. */
    static final int IGNORE_LIST_MAX_BYTES = 1_048_576;
    /** Shell that runs the approved folder scripts on a device. */
    static final String SCRIPT_SHELL = "/system/bin/sh";
    /** Milliseconds one cancelled script dispatch waits for its process to exit. */
    static final long CANCEL_WAIT_MILLIS = 2_000;
    /** Milliseconds between the exit checks of one cancelled script dispatch. */
    static final long CANCEL_POLL_MILLIS = 20;
    /**
     * Names Syncthing gives to the conflict copies it keeps next to a file.
     *
     * <p>Syncthing keeps the extension of the original file after the device identifier, so a
     * conflict copy of {@code notes.txt} is named {@code notes.sync-conflict-<date>-<time>-<id>.txt}.
     * The pattern therefore ends with the device identifier and then accepts whatever suffix the
     * original name carried. {@link Pattern#DOTALL} keeps the search working for a name that
     * carries a line break, which a POSIX file name may, so a conflict copy is found no matter
     * where the marker sits inside its name and both modes match the same entries.</p>
     */
    static final Pattern CONFLICT_NAME = Pattern.compile(
            ".*\\.sync-conflict-[0-9]{8}-[0-9]{6}-[0-9A-Za-z]{7}.*",
            Pattern.DOTALL
    );

    private final FolderNativeAccess nativeAccess;
    private final NanoTimeSource nanoTime;
    /** Absolute path of the shell that runs approved folder scripts. */
    private final String scriptShell;

    AppUidFolderOperations(FolderNativeAccess nativeAccess, NanoTimeSource nanoTime) {
        this(nativeAccess, nanoTime, SCRIPT_SHELL);
    }

    AppUidFolderOperations(
            FolderNativeAccess nativeAccess,
            NanoTimeSource nanoTime,
            String scriptShell
    ) {
        this.nativeAccess = Objects.requireNonNull(nativeAccess);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.scriptShell = Objects.requireNonNull(scriptShell);
    }

    /**
     * Reports whether one candidate folder can be written by the application UID.
     *
     * <p>The preferred probe creates no name and leaves no entry behind. Only when the filesystem
     * does not support that probe does the check fall back to a non-mutating access inference, so
     * no probe ever leaves a marker file inside the folder. The inference answers with a verdict,
     * so a check the platform cannot answer is reported as undetermined rather than as proven
     * read-only access.</p>
     */
    FolderWriteability probeWriteability(String candidatePath) throws FolderOperationException {
        requireAbsolutePath(candidatePath, "candidate folder");
        File candidate = new File(candidatePath);
        if (!candidate.exists() || !candidate.isDirectory()) {
            return FolderWriteability.UNKNOWN;
        }
        for (int flags : TMPFILE_FLAGS) {
            FolderNativeAccess.UnnamedTemporaryResult result =
                    nativeAccess.createUnnamedTemporary(candidatePath, flags);
            switch (result) {
                case CREATED:
                    return FolderWriteability.WRITABLE;
                case PERMISSION_DENIED:
                    return FolderWriteability.READ_ONLY;
                case UNSUPPORTED:
                    break;
                default:
                    return FolderWriteability.UNKNOWN;
            }
        }
        return nativeAccess.inferWriteability(candidatePath);
    }

    /**
     * Lists the conflict files below one configured folder.
     *
     * <p>The walk never follows a symbolic link and never leaves the folder. The versioning
     * directory of the configured folder itself is skipped, and the entry, match, output, and
     * time budgets fail the whole scan instead of returning a shorter list. A directory accepted
     * as a plain directory is checked again with no-follow semantics directly before the walk
     * lists it, so a path a concurrent writer exchanged for a symbolic link fails the scan
     * instead of leading it out of the folder.</p>
     */
    ConflictDiscoveryResult discoverConflicts(String folderRoot) throws FolderOperationException {
        requireAbsolutePath(folderRoot, "configured folder");
        File root = new File(folderRoot);
        if (!root.isDirectory()) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_ACCESS_FAILED,
                    "The configured folder is not a directory: " + folderRoot
            );
        }
        // The budget is measured as elapsed time, because a monotonic reading is only meaningful
        // as a difference and an added budget could wrap around and expire the scan immediately.
        long scanStartedNanos = nanoTime.readNanos();
        List<String> relativePaths = new ArrayList<>();
        int entries = 0;
        int outputCharacters = 0;
        Deque<File> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            if (nanoTime.readNanos() - scanStartedNanos > SCAN_BUDGET_NANOS) {
                throw limitFailure("time", folderRoot);
            }
        File directory = pending.removeLast();
        // The folder root is the path the caller configured, so it is used as given. Every other
        // directory was queued after passing a no-follow check, and that check runs again here,
        // directly before the listing, because a concurrent writer can put a symbolic link where
        // the checked directory was and the walk would then list outside the folder.
        if (!directory.equals(root)
                && nativeAccess.isSymbolicLink(directory.getAbsolutePath())) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_MEMBER_UNSAFE,
                    "The configured folder changed while it was listed: " + directory
            );
        }
        // java.io has no incremental directory enumeration at this API level, so one
        // directory is always materialized. Holding only its names and building each child
        // path while it is inspected keeps that allocation at the smaller of the two forms.
        // The entry budget is applied to that one list, so what the walk reports is bounded, but the
        // peak memory and the duration of one directory's listing are set by the directory itself.
        // Those two are the traversal's documented limit rather than a bound: bounding them needs an
        // incremental enumeration, which this API level does not have, because java.nio.file starts
        // at API 26 and android.system.Os exposes no directory reading call below it. The narrowest
        // viable alternative for reaching one is NIO core-library desugaring, which the desugaring
        // library documents as making java.nio.file available below API 26; enabling it is a build
        // configuration change and therefore outside this contract, so the limit is reported here
        // instead of a bound being claimed.
            String[] childNames = directory.list();
            if (childNames == null) {
                throw new FolderOperationException(
                        FolderOperationFailure.FOLDER_ACCESS_FAILED,
                        "The configured folder could not be listed: " + directory
                );
            }
            for (String childName : childNames) {
                // The budget is checked for every entry, so one wide directory cannot outrun
                // the deadline and still report success.
                // An interrupted walk belongs to a shutdown that no longer wants its result, so
                // it stops here instead of finishing the directory and publishing afterwards.
                if (Thread.currentThread().isInterrupted()) {
                    throw scanCancelledFailure(folderRoot);
                }
                if (nanoTime.readNanos() - scanStartedNanos > SCAN_BUDGET_NANOS) {
                    throw limitFailure("time", folderRoot);
                }
                File child = new File(directory, childName);
                entries++;
                if (entries > SCAN_MAX_ENTRIES) {
                    throw limitFailure("entry", folderRoot);
                }
                if (nativeAccess.isSymbolicLink(child.getAbsolutePath())) {
                    continue;
                }
                if (child.isDirectory()) {
                    // Only the versioning directory of the configured folder itself is skipped. A
                    // nested directory that merely carries the same name still holds user files, so
                    // a conflict inside it is reported like any other conflict.
                    if (!directory.equals(root)
                            || !child.getName().equals(Constants.FOLDER_NAME_STVERSIONS)) {
                        pending.add(child);
                    }
                    continue;
                }
                if (!child.isFile() || !CONFLICT_NAME.matcher(child.getName()).matches()) {
                    continue;
                }
                if (relativePaths.size() >= SCAN_MAX_MATCHES) {
                    throw limitFailure("match", folderRoot);
                }
                String relativePath = relativePathOf(root, child);
                outputCharacters += relativePath.length();
                if (outputCharacters > SCAN_MAX_OUTPUT_CHARS) {
                    throw limitFailure("output", folderRoot);
                }
                relativePaths.add(relativePath);
            }
        }
        Collections.sort(relativePaths);
        return ConflictDiscoveryResult.of(relativePaths);
    }

     /**
      * Reads the ignore list of one configured folder.
      *
      * <p>A folder that is not a directory fails the operation, so a folder that vanished or can no
      * longer be reached is never reported as a folder without an ignore list. Inside a folder that
      * exists, an absent member reports an absent result, while a member that exists but cannot be
      * read fails the operation, so a failed read is never shown to the user as an empty or missing
      * list.</p>
      */
     FolderIgnoreResult readIgnoreList(String folderRoot) throws FolderOperationException {
         requireAbsolutePath(folderRoot, "configured folder");
         File folder = new File(folderRoot);
         if (!folder.isDirectory()) {
             throw new FolderOperationException(
                     FolderOperationFailure.FOLDER_ACCESS_FAILED,
                     "The configured folder is not a directory: " + folderRoot
             );
         }
        File member = new File(folder, Constants.FILENAME_STIGNORE);
        // The member is inspected through its own path, so an absent ignore list is reported
        // without listing the folder and holding every one of its names in memory.
        FolderNativeAccess.MemberPresence presence =
                nativeAccess.inspectMember(member.getAbsolutePath());
        if (presence == FolderNativeAccess.MemberPresence.ABSENT) {
            // An absent member is reported only while its folder can still be inspected: a folder
            // that vanished or cannot be searched reports an access failure instead of a missing
            // ignore list.
            if (nativeAccess.inspectMember(folderRoot)
                    != FolderNativeAccess.MemberPresence.PRESENT) {
                throw new FolderOperationException(
                        FolderOperationFailure.FOLDER_ACCESS_FAILED,
                        "The configured folder could not be searched: " + folderRoot
                );
            }
            return FolderIgnoreResult.of(null);
        }
        if (presence == FolderNativeAccess.MemberPresence.UNINSPECTABLE) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_ACCESS_FAILED,
                    "The folder ignore list could not be inspected: " + member
            );
        }
        boolean symbolicLink = nativeAccess.isSymbolicLink(member.getAbsolutePath());
        if (symbolicLink || !member.isFile()) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_MEMBER_UNSAFE,
                    "The folder ignore list is not a regular file: " + member
            );
        }
        if (member.length() > IGNORE_LIST_MAX_BYTES) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                    "The folder ignore list exceeds the byte budget"
            );
        }
        try {
            byte[] content = readBoundedBytes(member);
            if (content.length > IGNORE_LIST_MAX_BYTES) {
                throw new FolderOperationException(
                        FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                        "The folder ignore list exceeds the byte budget"
                );
            }
            return FolderIgnoreResult.of(new String(content, StandardCharsets.UTF_8).split("\\n"));
        } catch (IOException failure) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_ACCESS_FAILED,
                    "The folder ignore list could not be read: " + member,
                    failure
            );
        }
    }

    /**
     * Reads one ignore-list member without ever holding more than its byte budget in memory.
     *
     * <p>The read stops as soon as the member is one byte over its budget, so a member that grows
     * while it is being read cannot decide how much memory the application holds. The caller then
     * reports the oversize member as a limit failure.</p>
     */
    private static byte[] readBoundedBytes(File member) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        try (InputStream input = new FileInputStream(member)) {
            while (collected.size() <= IGNORE_LIST_MAX_BYTES) {
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                collected.write(buffer, 0, count);
            }
        }
        return collected.toByteArray();
    }

    /**
     * Replaces the ignore list of one configured folder through one atomic rename.
     *
     * <p>The new content is written into an operation-owned temporary file in the same directory
     * and renamed over the member, so a reader either sees the previous list or the complete new
     * one.</p>
     *
     * <p>The replacement keeps the permission bits of the member it replaces, so replacing an
     * ignore list never changes what the file allows. Ownership needs no copy in Normal Mode: the
     * replacement is written by the application UID, inside the same directory as the member, which
     * is also what fixes the SELinux label of the replacement.</p>
     *
     * <p>Permission bits are copied from an existing member only. A folder that has no ignore list
     * yet has no member to copy from, and the replacement then keeps the mode the application
     * creates it with, which is what makes the first save of a folder work.</p>
     */
    void writeIgnoreList(String folderRoot, String[] ignore) throws FolderOperationException {
        Objects.requireNonNull(ignore, "The ignore list is required");
        requireAbsolutePath(folderRoot, "configured folder");
        File folder = new File(folderRoot);
        if (!folder.isDirectory()) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_ACCESS_FAILED,
                    "The configured folder is not a directory: " + folderRoot
            );
        }
        File member = new File(folder, Constants.FILENAME_STIGNORE);
        if (nativeAccess.isSymbolicLink(member.getAbsolutePath())) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_MEMBER_UNSAFE,
                    "The folder ignore list is a symbolic link: " + member
            );
        }
        // The member content is decided in one place, so an application-UID write and a
        // privileged write of the same list produce the same bytes.
        byte[] bytes = IgnoreListEncoding.encode(ignore);
        if (bytes.length > IGNORE_LIST_MAX_BYTES) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                    "The folder ignore list exceeds the byte budget"
            );
        }
        File temporary = new File(
                folder,
                Constants.FILENAME_STIGNORE + ".standroid-" + UUID.randomUUID()
        );
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.flush();
            }
            // Permission bits come from the member that is replaced. A folder that has no ignore
            // list yet has nothing to copy from, and the replacement keeps the mode the application
            // creates it with.
            if (member.exists()) {
                nativeAccess.preservePermissions(
                        member.getAbsolutePath(),
                        temporary.getAbsolutePath()
                );
            }
            nativeAccess.replaceAtomically(temporary.getAbsolutePath(), member.getAbsolutePath());
        } catch (IOException failure) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_ACCESS_FAILED,
                    "The folder ignore list could not be replaced: " + member,
                    failure
            );
        } finally {
            if (temporary.exists()) {
                temporary.delete();
            }
        }
    }

    /**
     * Runs every approved sync-completion script of one configured folder.
     *
     * <p>Only regular {@code .sh} files directly inside the marker directory run; a symbolic link
     * is never executed. Each script starts through {@code /system/bin/sh} with the folder root as
     * its working directory and the event name as its only argument. A missing marker directory is
     * the documented no-op case: the folder simply has no scripts.</p>
     *
     * <p>A marker directory that is itself a symbolic link is refused, because running the scripts
     * it points at would execute code from outside the configured folder.</p>
     */
    List<FolderScriptOutcome> runScriptSet(String folderRoot, String eventArgument)
            throws FolderOperationException {
        requireAbsolutePath(folderRoot, "configured folder");
        Objects.requireNonNull(eventArgument, "The folder event argument is required");
        File marker = new File(folderRoot, Constants.FILENAME_STFOLDER);
        if (nativeAccess.isSymbolicLink(marker.getAbsolutePath())) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_MEMBER_UNSAFE,
                    "The script directory is a symbolic link: " + marker
            );
        }
        if (!marker.isDirectory()) {
            return Collections.emptyList();
        }
        File[] entries = marker.listFiles();
        if (entries == null) {
            throw new FolderOperationException(
                    FolderOperationFailure.SCRIPT_DISPATCH_FAILED,
                    "The script directory could not be listed: " + marker
            );
        }
        List<File> scripts = new ArrayList<>();
        for (File entry : entries) {
            if (nativeAccess.isSymbolicLink(entry.getAbsolutePath()) || !entry.isFile()) {
                continue;
            }
            if (entry.getName().toLowerCase(Locale.ROOT).endsWith(".sh")) {
                scripts.add(entry);
            }
        }
        Collections.sort(scripts);
        List<FolderScriptOutcome> outcomes = new ArrayList<>();
        for (File script : scripts) {
            outcomes.add(runScript(folderRoot, script, eventArgument));
        }
        return outcomes;
    }

    /**
     * Returns the command text one approved script runs through the configured shell.
     *
     * <p>The parent passes the script path and the event name as positional arguments of the shell
     * invocation, so neither value is ever parsed as command text. Inside the command, {@code "$0"}
     * is the script path and {@code "$1"} is the event name. The script's own output goes to the
     * null device because no part of the application consumes it, and buffering it would let a
     * script decide how much memory the application holds.</p>
     */
    String scriptRedirectCommand() {
        return "exec " + scriptShell + " \"$0\" \"$1\" >/dev/null 2>&1";
    }

    /** Runs one approved script and reports its exit status. */
    private FolderScriptOutcome runScript(String folderRoot, File script, String eventArgument)
            throws FolderOperationException {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    scriptShell,
                    "-c",
                    scriptRedirectCommand(),
                    script.getAbsolutePath(),
                    eventArgument
            );
            builder.directory(new File(folderRoot));
            process = builder.start();
            // The script receives no input at all: its standard input is closed immediately, so a
            // script that reads it sees the end of the file instead of waiting forever for input
            // that is never written. The command already sends the script's output to the null
            // device.
            closeQuietly(process.getOutputStream());
            int exitStatus = process.waitFor();
            return FolderScriptOutcome.of(script.getName(), exitStatus);
        } catch (IOException failure) {
            throw new FolderOperationException(
                    FolderOperationFailure.SCRIPT_DISPATCH_FAILED,
                    "The folder script could not be started: " + script,
                    failure
            );
        } catch (InterruptedException interrupted) {
            cancelScript(process);
            Thread.currentThread().interrupt();
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_OPERATION_CANCELLED,
                    "The folder script dispatch was interrupted: " + script,
                    interrupted
            );
        } finally {
            if (process != null) {
                closeQuietly(process);
            }
        }
    }

    /**
     * Terminates one cancelled script dispatch and waits a bounded moment for it to exit.
     *
     * <p>The command that starts a script ends in {@code exec}, so the launched process becomes the
     * script shell itself. Only that process is terminated: processes the script started on its own
     * keep running, because Normal Mode holds no ownership evidence for them and they are not
     * Syncthing Owned Executions. The wait is bounded, because a script that ignores the
     * termination signal must not be able to hold the caller, and the oldest supported Android
     * version offers no forced kill, so such a script is left to exit on its own.</p>
     */
    private static void cancelScript(Process process) {
        if (process == null) {
            return;
        }
        closeQuietly(process);
        process.destroy();
        boolean interrupted = Thread.interrupted();
        try {
            long cancelStartedNanos = System.nanoTime();
            while (true) {
                try {
                    process.exitValue();
                    return;
                } catch (IllegalThreadStateException stillRunning) {
                    if (System.nanoTime() - cancelStartedNanos
                            >= CANCEL_WAIT_MILLIS * 1_000_000L) {
                        return;
                    }
                    try {
                        Thread.sleep(CANCEL_POLL_MILLIS);
                    } catch (InterruptedException interruptedWhileWaiting) {
                        interrupted = true;
                        return;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
    /** Releases the pipes of one finished script process. */
    private static void closeQuietly(Process process) {
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The script result is already decided; a failed close changes nothing.
        }
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The script result is already decided; a failed close changes nothing.
        }
    }

    /** Returns the path of one entry relative to the scanned folder. */
    private static String relativePathOf(File root, File entry) {
        String rootPath = root.getAbsolutePath();
        String entryPath = entry.getAbsolutePath();
        // A configured folder of "/" already ends in the separator, so the prefix is not doubled.
        String prefix = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
        if (entryPath.startsWith(prefix)) {
            return entryPath.substring(prefix.length());
        }
        return entryPath;
    }

    private static FolderOperationException limitFailure(String budget, String folderRoot) {
        return new FolderOperationException(
                FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                "The conflict scan of " + folderRoot + " exceeded its " + budget + " budget"
        );
    }

    private static FolderOperationException scanCancelledFailure(String folderRoot) {
        return new FolderOperationException(
                FolderOperationFailure.FOLDER_OPERATION_CANCELLED,
                "The conflict scan of " + folderRoot + " was cancelled"
        );
    }

    private static void requireAbsolutePath(String path, String description)
            throws FolderOperationException {
        Objects.requireNonNull(path, "The folder path is required");
        if (path.isEmpty() || path.charAt(0) != '/') {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_ACCESS_FAILED,
                    "The " + description + " path must be absolute"
            );
        }
    }
}
