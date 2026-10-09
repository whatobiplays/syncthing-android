package com.nutomic.syncthingandroid.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic stand-in for the platform folder mechanics.
 *
 * <p>It records what the policy asked the platform to do, and performs the replacements on the real
 * temporary files the policy created, so a test can inspect the resulting member content without a
 * device.</p>
 */
final class FakeFolderNativeAccess implements FolderNativeAccess {
    /** Flags of every unnamed-temporary attempt, in order. */
    final List<Integer> probeFlags = new ArrayList<>();
    /** Result every unnamed-temporary attempt reports. */
    UnnamedTemporaryResult probeResult = UnnamedTemporaryResult.FAILED;
    /** Verdict every access inference reports. */
    FolderWriteability accessInference = FolderWriteability.UNKNOWN;
    /** Source and target of every replacement, as {@code "source -> target"}. */
    final List<String> replacements = new ArrayList<>();
    /** Source and replacement of the permission copy, {@code "source -> replacement"}. */
    final List<String> permissionCopies = new ArrayList<>();
    /**
     * Swaps performed by the first no-follow check of one path, keyed by absolute path.
     *
     * <p>A registered swap stands in for a concurrent writer that replaces an entry between the
     * check that accepts it and the next use of that path.</p>
     */
    final Map<String, Runnable> swapsAfterFirstLinkCheck = new HashMap<>();

    @Override
    public UnnamedTemporaryResult createUnnamedTemporary(String directory, int flags) {
        probeFlags.add(flags);
        return probeResult;
    }

    @Override
    public FolderWriteability inferWriteability(String path) {
        return accessInference;
    }

    @Override
    public boolean isSymbolicLink(String path) {
        Runnable swap = swapsAfterFirstLinkCheck.remove(path);
        if (swap != null) {
            // The caller checks the entry with no-follow semantics and then uses it; running the
            // swap here reproduces a writer that changes the entry inside exactly that window.
            swap.run();
            return false;
        }
        return Files.isSymbolicLink(Path.of(path));
    }

    @Override
    public void preservePermissions(String memberPath, String replacementPath) throws IOException {
        permissionCopies.add(memberPath + " -> " + replacementPath);
        Files.setPosixFilePermissions(
                Path.of(replacementPath),
                Files.getPosixFilePermissions(Path.of(memberPath))
        );
    }
    @Override
    public void replaceAtomically(String temporaryPath, String targetPath) throws IOException {
        replacements.add(temporaryPath + " -> " + targetPath);
        Files.move(
                Path.of(temporaryPath),
                Path.of(targetPath),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
        );
    }

    @Override
    public MemberPresence inspectMember(String path) {
        try {
            Files.readAttributes(
                    Path.of(path),
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            return MemberPresence.PRESENT;
        } catch (NoSuchFileException absent) {
            return MemberPresence.ABSENT;
        } catch (IOException uninspectable) {
            return MemberPresence.UNINSPECTABLE;
        }
    }
}
