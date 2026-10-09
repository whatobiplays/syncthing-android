package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.nutomic.syncthingandroid.service.Constants;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Deterministic tests for the application-UID folder policy.
 *
 * <p>Every test runs without a device: the platform mechanics arrive through a fake, and the clock
 * of the scan budget is driven by the test, so budget behaviour is deterministic rather than
 * timing-dependent.</p>
 */
public class AppUidFolderOperationsTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final FakeFolderNativeAccess nativeAccess = new FakeFolderNativeAccess();
    private final AtomicLong clock = new AtomicLong();
    private final AppUidFolderOperations operations =
            new AppUidFolderOperations(nativeAccess, clock::get, "/bin/sh");

    @Test
    public void probeUsesTheOctalTmpfileFlagCombinationAndLeavesNoArtifact() throws Exception {
        File folder = temporaryFolder.newFolder("folder");
        nativeAccess.probeResult = FolderNativeAccess.UnnamedTemporaryResult.CREATED;

        assertEquals(
                FolderWriteability.WRITABLE,
                operations.probeWriteability(folder.getAbsolutePath())
        );

        // __O_TMPFILE is octal 020000000, which is 0x400000 and 4194304 in decimal.
        assertEquals(0x400000, AppUidFolderOperations.LINUX_O_TMPFILE);
        assertEquals(4194304, AppUidFolderOperations.LINUX_O_TMPFILE);
        // The probe combines the unnamed temporary flag with a directory and a writable mode.
        assertEquals(
                Collections.singletonList(
                        AppUidFolderOperations.LINUX_O_TMPFILE
                                | AppUidFolderOperations.LINUX_O_DIRECTORY
                                | AppUidFolderOperations.LINUX_O_WRONLY
                ),
                nativeAccess.probeFlags
        );
        assertEquals(1, AppUidFolderOperations.TMPFILE_FLAGS.length);
        // The candidate folder is unchanged: no marker, and in particular no ".stwritetest".
        try (Stream<Path> entries = Files.list(folder.toPath())) {
            assertEquals(Collections.emptyList(), entries.collect(Collectors.toList()));
        }
    }

    @Test
    public void probeFallsBackToTheAccessInferenceForAnUnsupportedFilesystem() throws Exception {
        File folder = temporaryFolder.newFolder("folder");
        nativeAccess.probeResult = FolderNativeAccess.UnnamedTemporaryResult.UNSUPPORTED;
        nativeAccess.accessInference = FolderWriteability.WRITABLE;

        assertEquals(
                FolderWriteability.WRITABLE,
                operations.probeWriteability(folder.getAbsolutePath())
        );

        // One canonical flag combination is tried; an unrecognised layout is reported as
        // unsupported, and the non-mutating access inference then decides the answer.
        assertEquals(1, AppUidFolderOperations.TMPFILE_FLAGS.length);
        assertEquals(
                Collections.singletonList(
                        AppUidFolderOperations.LINUX_O_TMPFILE
                                | AppUidFolderOperations.LINUX_O_DIRECTORY
                                | AppUidFolderOperations.LINUX_O_WRONLY
                ),
                nativeAccess.probeFlags
        );

        nativeAccess.accessInference = FolderWriteability.READ_ONLY;
        nativeAccess.probeFlags.clear();
        assertEquals(
                FolderWriteability.READ_ONLY,
                operations.probeWriteability(folder.getAbsolutePath())
        );

        // An inference that could not answer reports an undetermined verdict, never a proven
        // read-only one, so an inconclusive check cannot downgrade the folder.
        nativeAccess.accessInference = FolderWriteability.UNKNOWN;
        assertEquals(
                FolderWriteability.UNKNOWN,
                operations.probeWriteability(folder.getAbsolutePath())
        );
    }

    @Test
    public void probeReportsPermissionDenialWithoutUsingTheFallback() throws Exception {
        File folder = temporaryFolder.newFolder("folder");
        nativeAccess.probeResult = FolderNativeAccess.UnnamedTemporaryResult.PERMISSION_DENIED;
        nativeAccess.accessInference = FolderWriteability.WRITABLE;

        assertEquals(
                FolderWriteability.READ_ONLY,
                operations.probeWriteability(folder.getAbsolutePath())
        );

        // A permission verdict is conclusive, so the second flag layout is not tried.
        assertEquals(1, nativeAccess.probeFlags.size());
    }

    @Test
    public void probeReportsUnknownWhenNothingWasProven() throws Exception {
        File folder = temporaryFolder.newFolder("folder");
        nativeAccess.probeResult = FolderNativeAccess.UnnamedTemporaryResult.FAILED;

        assertEquals(
                FolderWriteability.UNKNOWN,
                operations.probeWriteability(folder.getAbsolutePath())
        );
        // An inconclusive attempt is tried once and never turns into an access-inference verdict.
        assertEquals(
                Collections.singletonList(
                        AppUidFolderOperations.LINUX_O_TMPFILE
                                | AppUidFolderOperations.LINUX_O_DIRECTORY
                                | AppUidFolderOperations.LINUX_O_WRONLY
                ),
                nativeAccess.probeFlags
        );

        assertEquals(
                FolderWriteability.UNKNOWN,
                operations.probeWriteability(
                        new File(temporaryFolder.getRoot(), "missing").getAbsolutePath()
                )
        );
    }

    @Test
    public void probeRejectsRelativeCandidatePaths() {
        FolderOperationException failure = assertThrows(
                FolderOperationException.class,
                () -> operations.probeWriteability("relative/folder")
        );
        assertEquals(FolderOperationFailure.FOLDER_ACCESS_FAILED, failure.failure());
    }

    @Test
    public void conflictScanFindsNestedConflictsAndHandlesUnusualNames() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        String conflict = ".sync-conflict-20260101-010101-ABCDEFG";
        writeFile(root, "top" + conflict, "x");
        writeFile(root, "nested/deep/lower" + conflict + ".txt", "x");
        writeFile(root, "nested/plain.txt", "x");
        writeFile(root, "odd names/'quoted' & (strange) #1" + conflict, "x");
        writeFile(root, Constants.FOLDER_NAME_STVERSIONS + "/ignored" + conflict, "x");
        File conflictDirectory = new File(root, "directory" + conflict);
        assertTrue(conflictDirectory.mkdir());

        assertEquals(
                Arrays.asList(
                        "nested/deep/lower" + conflict + ".txt",
                        "odd names/'quoted' & (strange) #1" + conflict,
                        "top" + conflict
                ),
                operations.discoverConflicts(root.getAbsolutePath()).relativePaths()
        );
    }

    @Test
    public void conflictScanMatchesConflictNamesThatSpanALineBreak() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        String conflict = ".sync-conflict-20260101-010101-ABCDEFG";
        // Syncthing derives a conflict name from the original file name, and a name on a POSIX
        // filesystem can carry a line break. The pattern therefore has to match the marker that
        // follows one, exactly like the shell pattern the privileged scan matches entries with.
        String name = "line\nbreak" + conflict + ".txt";
        writeFile(root, name, "x");

        assertEquals(
                Collections.singletonList(name),
                operations.discoverConflicts(root.getAbsolutePath()).relativePaths()
        );
    }

    @Test
    public void conflictScanNeverFollowsSymbolicLinksOrLeavesTheFolder() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        File outside = temporaryFolder.newFolder("outside");
        String conflict = ".sync-conflict-20260101-010101-ABCDEFG";
        writeFile(outside, "outside" + conflict, "x");
        Assume.assumeTrue(
                "this platform cannot create symbolic links",
                createSymbolicLink(new File(root, "escaped").toPath(), outside.toPath())
        );
        Assume.assumeTrue(
                createSymbolicLink(
                        new File(outside, "linked" + conflict).toPath(),
                        new File(outside, "outside" + conflict).toPath()
                )
        );
        writeFile(root, "inside" + conflict, "x");

        assertEquals(
                Collections.singletonList("inside" + conflict),
                operations.discoverConflicts(root.getAbsolutePath()).relativePaths()
        );
    }

    @Test
    public void conflictScanFailsWhenADirectoryIsReplacedByASymbolicLinkBeforeItDescends()
            throws Exception {
        File root = temporaryFolder.newFolder("folder");
        File outside = temporaryFolder.newFolder("outside");
        String conflict = ".sync-conflict-20260101-010101-ABCDEFG";
        writeFile(outside, "outside" + conflict, "x");
        File swapped = new File(root, "swapped");
        assertTrue(swapped.mkdir());
        Assume.assumeTrue(
                "this platform cannot create symbolic links",
                createSymbolicLink(new File(root, "probe-link").toPath(), outside.toPath())
        );
        // The first no-follow check of the entry reports it as a plain directory and then
        // replaces it, exactly like a concurrent writer racing the walk between the check that
        // accepts the directory and the descent that lists it.
        nativeAccess.swapsAfterFirstLinkCheck.put(swapped.getAbsolutePath(), () -> {
            try {
                Files.delete(swapped.toPath());
                Files.createSymbolicLink(swapped.toPath(), outside.toPath());
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });

        FolderOperationException failure = assertThrows(
                FolderOperationException.class,
                () -> operations.discoverConflicts(root.getAbsolutePath())
        );

        assertEquals(FolderOperationFailure.FOLDER_MEMBER_UNSAFE, failure.failure());
        assertTrue(
                "the writer must have replaced the directory for this test to mean anything",
                Files.isSymbolicLink(swapped.toPath())
        );
    }

    @Test
    public void conflictScanSkipsOnlyTheVersioningDirectoryOfTheFolderRoot() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        String conflict = ".sync-conflict-20260101-010101-ABCDEFG";
        // The versioning directory of the folder itself is not user content, so it is skipped.
        writeFile(root, ".stversions/versioned" + conflict, "x");
        // An ordinary nested directory that merely carries the same name still holds user files,
        // so a conflict inside it is reported like any other conflict.
        writeFile(root, "nested/.stversions/nested" + conflict, "x");

        assertEquals(
                Collections.singletonList("nested/.stversions/nested" + conflict),
                operations.discoverConflicts(root.getAbsolutePath()).relativePaths()
        );
    }

    @Test
    public void conflictScanFailsWholeScanWhenTheTimeBudgetIsExhausted() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        writeFile(root, "plain.txt", "x");
        // The first clock reading starts the scan; the second is already beyond the budget.
        clock.set(AppUidFolderOperations.SCAN_BUDGET_NANOS);
        AtomicLong readings = new AtomicLong();
        AppUidFolderOperations budgeted = new AppUidFolderOperations(
                nativeAccess,
                () -> readings.getAndAdd(AppUidFolderOperations.SCAN_BUDGET_NANOS + 1),
                "/bin/sh"
        );

        FolderOperationException failure = assertThrows(
                FolderOperationException.class,
                () -> budgeted.discoverConflicts(root.getAbsolutePath())
        );
        assertEquals(FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED, failure.failure());
    }

    @Test
    public void conflictScanEnforcesItsTimeBudgetWhileItProcessesOneWideDirectory()
            throws Exception {
        File root = temporaryFolder.newFolder("folder");
        for (int index = 0; index < 4; index++) {
            writeFile(root, "entry-" + index + ".txt", "x");
        }
        // The clock stands still inside one directory and moves on between its entries, so a walk
        // that only checks the budget once per directory would finish inside the budget.
        AtomicLong readings = new AtomicLong();
        AppUidFolderOperations budgeted = new AppUidFolderOperations(
                nativeAccess,
                () -> readings.getAndAdd(AppUidFolderOperations.SCAN_BUDGET_NANOS / 2),
                "/bin/sh"
        );

        FolderOperationException failure = assertThrows(
                FolderOperationException.class,
                () -> budgeted.discoverConflicts(root.getAbsolutePath())
        );
        assertEquals(FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED, failure.failure());
    }

    @Test
    public void conflictScanStopsWhenTheWalkIsInterrupted() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        for (int index = 0; index < 4; index++) {
            writeFile(root, "entry-" + index + ".txt", "x");
        }
        // A shutdown interrupts the worker that runs the walk while it is still inside one
        // directory, so the walk must end there instead of finishing and publishing a result.
        AtomicLong readings = new AtomicLong();
        AppUidFolderOperations interruptedScan = new AppUidFolderOperations(
                nativeAccess,
                () -> {
                    if (readings.incrementAndGet() == 2) {
                        Thread.currentThread().interrupt();
                    }
                    return 0L;
                },
                "/bin/sh"
        );
        try {
            FolderOperationException failure = assertThrows(
                    FolderOperationException.class,
                    () -> interruptedScan.discoverConflicts(root.getAbsolutePath())
            );
            assertEquals(FolderOperationFailure.FOLDER_OPERATION_CANCELLED, failure.failure());
        } finally {
            // The interrupted status belongs to the thread, so the test thread is left running
            // normally for the tests that follow.
            Thread.interrupted();
        }
    }

    @Test
    public void conflictScanFailsWholeScanWhenTheMatchBudgetIsExhausted() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        String conflict = ".sync-conflict-20260101-010101-ABCDEFG";
        for (int index = 0; index <= AppUidFolderOperations.SCAN_MAX_MATCHES; index++) {
            writeFile(root, "file" + index + conflict, "x");
        }

        FolderOperationException failure = assertThrows(
                FolderOperationException.class,
                () -> operations.discoverConflicts(root.getAbsolutePath())
        );
        assertEquals(FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED, failure.failure());
    }

    @Test
    public void conflictScanReportsAnUnreadableFolderInsteadOfAnEmptyResult() throws Exception {
        File missing = new File(temporaryFolder.getRoot(), "missing");
        FolderOperationException failure = assertThrows(
                FolderOperationException.class,
                () -> operations.discoverConflicts(missing.getAbsolutePath())
        );
        assertEquals(FolderOperationFailure.FOLDER_ACCESS_FAILED, failure.failure());
    }

    @Test
    public void ignoreListReadDistinguishesAnAbsentMemberFromAFailure() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        assertNull(
                "a folder without an ignore list reports absence, not an empty list",
                operations.readIgnoreList(root.getAbsolutePath()).lines()
        );

        writeFile(root, Constants.FILENAME_STIGNORE, "*.tmp\n*.bak");
        assertArrayEquals(
                new String[] {"*.tmp", "*.bak"},
                operations.readIgnoreList(root.getAbsolutePath()).lines()
        );

        File directoryMember = temporaryFolder.newFolder("wrong-kind");
        assertTrue(new File(directoryMember, Constants.FILENAME_STIGNORE).mkdir());
        FolderOperationException unsafe = assertThrows(
                FolderOperationException.class,
                () -> operations.readIgnoreList(directoryMember.getAbsolutePath())
        );
        assertEquals(FolderOperationFailure.FOLDER_MEMBER_UNSAFE, unsafe.failure());

        File oversizedRoot = temporaryFolder.newFolder("oversized");
        byte[] oversized = new byte[AppUidFolderOperations.IGNORE_LIST_MAX_BYTES + 1];
        Arrays.fill(oversized, (byte) 'a');
        Files.write(
                new File(oversizedRoot, Constants.FILENAME_STIGNORE).toPath(),
                oversized
        );
        FolderOperationException tooLarge = assertThrows(
                FolderOperationException.class,
                () -> operations.readIgnoreList(oversizedRoot.getAbsolutePath())
        );
        assertEquals(
                FolderOperationFailure.FOLDER_OPERATION_LIMIT_EXCEEDED,
                tooLarge.failure()
        );
    }

    @Test
    public void ignoreListReadReportsAnUnreachableFolderAsAFailure() throws Exception {
        File missing = new File(temporaryFolder.getRoot(), "not-configured");
        FolderOperationException missingFailure = assertThrows(
                FolderOperationException.class,
                () -> operations.readIgnoreList(missing.getAbsolutePath())
        );
        assertEquals(
                "a folder that is not there is a typed failure, not a folder without a list",
                FolderOperationFailure.FOLDER_ACCESS_FAILED,
                missingFailure.failure()
        );

        File notAFolder = temporaryFolder.newFile("not-a-folder");
        FolderOperationException kindFailure = assertThrows(
                FolderOperationException.class,
                () -> operations.readIgnoreList(notAFolder.getAbsolutePath())
        );
        assertEquals(
                "a path that is no longer a directory is a typed failure as well",
                FolderOperationFailure.FOLDER_ACCESS_FAILED,
                kindFailure.failure()
        );
    }

    @Test
    public void ignoreListReadTreatsAnUnsearchableFolderAsAFailure() throws Exception {
        File root = temporaryFolder.newFolder("unsearchable");
        Files.setPosixFilePermissions(
                root.toPath(),
                PosixFilePermissions.fromString("---------")
        );
        try {
            Assume.assumeTrue(
                    "this platform does not enforce directory search permissions",
                    root.list() == null
            );

            FolderOperationException failure = assertThrows(
                    FolderOperationException.class,
                    () -> operations.readIgnoreList(root.getAbsolutePath())
            );
            assertEquals(
                    "a folder that cannot be searched never looks like a folder without a list",
                    FolderOperationFailure.FOLDER_ACCESS_FAILED,
                    failure.failure()
            );
        } finally {
            Files.setPosixFilePermissions(
                    root.toPath(),
                    PosixFilePermissions.fromString("rwx------")
            );
        }
    }

    @Test
    public void ignoreListReadRefusesASymbolicLink() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        File outside = temporaryFolder.newFile("outside-ignore");
        Files.write(outside.toPath(), "*.tmp".getBytes(StandardCharsets.UTF_8));
        Assume.assumeTrue(
                "this platform cannot create symbolic links",
                createSymbolicLink(
                        new File(root, Constants.FILENAME_STIGNORE).toPath(),
                        outside.toPath()
                )
        );

        FolderOperationException failure = assertThrows(
                FolderOperationException.class,
                () -> operations.readIgnoreList(root.getAbsolutePath())
        );
        assertEquals(FolderOperationFailure.FOLDER_MEMBER_UNSAFE, failure.failure());
    }

    @Test
    public void ignoreListWriteReplacesTheMemberAtomically() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        writeFile(root, Constants.FILENAME_STIGNORE, "*.old");

        operations.writeIgnoreList(root.getAbsolutePath(), new String[] {"*.tmp", "*.bak"});

        assertEquals(
                "*.tmp\n*.bak",
                new String(
                        Files.readAllBytes(
                                new File(root, Constants.FILENAME_STIGNORE).toPath()
                        ),
                        StandardCharsets.UTF_8
                )
        );
        assertEquals(1, nativeAccess.replacements.size());
        assertTrue(
                nativeAccess.replacements.get(0).endsWith(
                        " -> " + new File(root, Constants.FILENAME_STIGNORE).getAbsolutePath()
                )
        );
        // The operation-owned temporary file is gone: only the member remains.
        assertEquals(
                Collections.singletonList(Constants.FILENAME_STIGNORE),
                listNames(root)
        );
    }

    @Test
    public void ignoreListWriteRefusesASymbolicLinkMember() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        File outside = temporaryFolder.newFile("outside-ignore-write");
        Assume.assumeTrue(
                "this platform cannot create symbolic links",
                createSymbolicLink(
                        new File(root, Constants.FILENAME_STIGNORE).toPath(),
                        outside.toPath()
                )
        );

        FolderOperationException failure = assertThrows(
                FolderOperationException.class,
                () -> operations.writeIgnoreList(root.getAbsolutePath(), new String[] {"*.tmp"})
        );
        assertEquals(FolderOperationFailure.FOLDER_MEMBER_UNSAFE, failure.failure());
        assertEquals(0, nativeAccess.replacements.size());
    }

    @Test
    public void scriptDispatchRunsOnlyRegularShellScriptsWithTheEventArgument() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        File marker = new File(root, Constants.FILENAME_STFOLDER);
        assertTrue(marker.mkdir());
        File record = new File(root, "record");
        writeExecutableScript(
                new File(marker, "10-first.sh"),
                "printf '%s|%s|%s' \"$0\" \"$1\" \"$(pwd)\" > \"" + record.getAbsolutePath() + "\""
        );
        writeFile(marker, "notes.txt", "ignored");
        assertTrue(new File(marker, "directory.sh").mkdir());
        Assume.assumeTrue(
                "this platform cannot create symbolic links",
                createSymbolicLink(
                        new File(marker, "linked.sh").toPath(),
                        new File(marker, "10-first.sh").toPath()
                )
        );

        List<FolderScriptOutcome> outcomes = operations.runScriptSet(
                root.getAbsolutePath(),
                FolderEvent.SYNC_COMPLETE.argument()
        );

        assertEquals(
                Collections.singletonList(FolderScriptOutcome.of("10-first.sh", 0)),
                outcomes
        );
        String[] recorded = new String(
                Files.readAllBytes(record.toPath()),
                StandardCharsets.UTF_8
        ).split("\\|", -1);
        assertEquals(new File(marker, "10-first.sh").getAbsolutePath(), recorded[0]);
        assertEquals("sync_complete", recorded[1]);
        // The script runs with the folder root as its working directory.
        assertEquals(root.getCanonicalPath(), new File(recorded[2]).getCanonicalPath());
    }

    @Test
    public void scriptDispatchIsANoOpWithoutTheMarkerDirectory() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        assertEquals(
                Collections.emptyList(),
                operations.runScriptSet(root.getAbsolutePath(), FolderEvent.SYNC_COMPLETE.argument())
        );
    }

    @Test
    public void scriptDispatchReportsANonZeroStatusInsteadOfRaisingIt() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        File marker = new File(root, Constants.FILENAME_STFOLDER);
        assertTrue(marker.mkdir());
        writeExecutableScript(new File(marker, "failing.sh"), "exit 7");

        assertEquals(
                Collections.singletonList(FolderScriptOutcome.of("failing.sh", 7)),
                operations.runScriptSet(root.getAbsolutePath(), "sync_complete")
        );
    }

    @Test
    public void scriptDispatchDiscardsScriptOutput() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        File marker = new File(root, Constants.FILENAME_STFOLDER);
        assertTrue(marker.mkdir());
        writeExecutableScript(
                new File(marker, "noisy.sh"),
                "i=0; while [ \"$i\" -lt 5000 ]; do echo line-$i; i=$((i+1)); done"
        );

        assertEquals(
                Collections.singletonList(FolderScriptOutcome.of("noisy.sh", 0)),
                operations.runScriptSet(root.getAbsolutePath(), "sync_complete")
        );
        // The command text sends the script's own output away from the parent's pipes.
        assertTrue(operations.scriptRedirectCommand().endsWith(">/dev/null 2>&1"));
        assertFalse(operations.scriptRedirectCommand().contains("sync_complete"));
    }

    @Test
    public void scriptDispatchRefusesASymbolicLinkMarkerDirectory() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        File outside = temporaryFolder.newFolder("outside");
        writeExecutableScript(new File(outside, "outside.sh"), "exit 0");
        Assume.assumeTrue(
                "this platform cannot create symbolic links",
                createSymbolicLink(
                        new File(root, Constants.FILENAME_STFOLDER).toPath(),
                        outside.toPath()
                )
        );

        FolderOperationException failure = assertThrows(
                FolderOperationException.class,
                () -> operations.runScriptSet(
                        root.getAbsolutePath(),
                        FolderEvent.SYNC_COMPLETE.argument()
                )
        );

        assertEquals(FolderOperationFailure.FOLDER_MEMBER_UNSAFE, failure.failure());
    }

    @Test
    public void scriptDispatchRunsNothingInsideASymbolicLinkMarkerDirectory() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        File outside = temporaryFolder.newFolder("outside");
        writeExecutableScript(new File(outside, "outside.sh"), "echo ran > executed.txt");
        Assume.assumeTrue(
                "this platform cannot create symbolic links",
                createSymbolicLink(
                        new File(root, Constants.FILENAME_STFOLDER).toPath(),
                        outside.toPath()
                )
        );

        assertThrows(
                FolderOperationException.class,
                () -> operations.runScriptSet(
                        root.getAbsolutePath(),
                        FolderEvent.SYNC_COMPLETE.argument()
                )
        );

        assertFalse(
                "no script outside the configured folder may run",
                new File(root, "executed.txt").exists()
        );
    }

    @Test
    public void ignoreListWriteKeepsThePermissionsOfTheMemberItReplaces() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        File member = new File(root, Constants.FILENAME_STIGNORE);
        Files.write(member.toPath(), "previous".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(
                member.toPath(),
                PosixFilePermissions.fromString("rw-r-----")
        );

        operations.writeIgnoreList(root.getAbsolutePath(), new String[] {"new"});

        assertEquals(
                "the replacement carries the permissions of the member it replaced",
                PosixFilePermissions.fromString("rw-r-----"),
                Files.getPosixFilePermissions(member.toPath())
        );
        assertEquals(
                "the permissions are copied exactly once",
                1,
                nativeAccess.permissionCopies.size()
        );
        assertEquals("the member is replaced exactly once", 1, nativeAccess.replacements.size());
        String copy = nativeAccess.permissionCopies.get(0);
        assertEquals(
                "the permissions are copied onto the file that then replaces the member",
                nativeAccess.replacements.get(0),
                copy.substring(copy.indexOf(" -> ") + 4) + " -> " + member.getAbsolutePath()
        );
    }

    @Test
    public void ignoreListWriteCreatesTheMemberWhenTheFolderHasNone() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        assertFalse(
                "the folder starts without an ignore list",
                new File(root, Constants.FILENAME_STIGNORE).exists()
        );

        operations.writeIgnoreList(root.getAbsolutePath(), new String[] {"*.tmp", "*.bak"});

        File member = new File(root, Constants.FILENAME_STIGNORE);
        assertEquals(
                "the first save writes the patterns the user entered",
                "*.tmp\n*.bak",
                readText(member)
        );
        assertTrue(
                "a member that does not exist yet has no attributes to copy",
                nativeAccess.permissionCopies.isEmpty()
        );
        assertEquals("the member is replaced exactly once", 1, nativeAccess.replacements.size());
    }

    @Test
    public void scriptDispatchGivesEveryScriptTheEndOfInputInsteadOfAWaitingPrompt()
            throws Exception {
        File root = temporaryFolder.newFolder("folder");
        File marker = new File(root, Constants.FILENAME_STFOLDER);
        assertTrue("the marker directory is created", marker.mkdir());
        writeExecutableScript(
                new File(marker, "reads-input.sh"),
                "if read standroid_input; then exit 1; fi\n"
                        + "exit 0"
        );

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<List<FolderScriptOutcome>> dispatch = worker.submit(() -> operations.runScriptSet(
                    root.getAbsolutePath(),
                    FolderEvent.SYNC_COMPLETE.argument()
            ));

            assertEquals(
                    "a script that reads standard input sees the end of the file and finishes",
                    Collections.singletonList(FolderScriptOutcome.of("reads-input.sh", 0)),
                    dispatch.get(10, TimeUnit.SECONDS)
            );
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void cancellingADispatchStopsTheScriptItStarted() throws Exception {
        File root = temporaryFolder.newFolder("folder");
        File marker = new File(root, Constants.FILENAME_STFOLDER);
        assertTrue("the marker directory is created", marker.mkdir());
        File progress = new File(root, "progress.log");
        writeExecutableScript(
                new File(marker, "slow.sh"),
                "echo started >> progress.log\n"
                        + "sleep 30\n"
                        + "echo finished >> progress.log"
        );

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<FolderOperationException> outcome = worker.submit(() -> {
                try {
                    operations.runScriptSet(
                            root.getAbsolutePath(),
                            FolderEvent.SYNC_COMPLETE.argument()
                    );
                    return null;
                } catch (FolderOperationException failure) {
                    return failure;
                }
            });
            awaitText(progress, "started");

            long cancelledAt = System.nanoTime();
            worker.shutdownNow();
            FolderOperationException failure = outcome.get(10, TimeUnit.SECONDS);
            long cancelledMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt);

            assertNotNull("a cancelled dispatch reports a typed failure", failure);
            assertEquals(
                    FolderOperationFailure.FOLDER_OPERATION_CANCELLED,
                    failure.failure()
            );
            assertTrue(
                    "cancellation returns before the script would have finished on its own; it took "
                            + cancelledMillis + " ms",
                    cancelledMillis < 5_000
            );
            Thread.sleep(300);
            assertFalse(
                    "the cancelled script never reaches its next statement",
                    readText(progress).contains("finished")
            );
        } finally {
            worker.shutdownNow();
        }
    }

    /** Waits until one file holds the expected text, or fails the test. */
    private static void awaitText(File file, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (file.isFile() && readText(file).contains(expected)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("The script never reported " + expected);
    }

    /** Reads one text file this test class owns. */
    private static String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    /** Writes a shell script and marks it executable. */
    private static void writeExecutableScript(File script, String body) throws IOException {
        Files.write(
                script.toPath(),
                ("#!/bin/sh\n" + body + "\n").getBytes(StandardCharsets.UTF_8)
        );
        assertTrue(
                "could not mark the test script executable",
                script.setExecutable(true)
        );
    }

    /** Writes one text file, creating its parent directories. */
    private static void writeFile(File root, String relativePath, String content)
            throws IOException {
        File file = new File(root, relativePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException unsupported) {
            return false;
        }
    }

    private static List<String> listNames(File directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory.toPath())) {
            return entries.map(path -> path.getFileName().toString()).collect(Collectors.toList());
        }
    }
}
