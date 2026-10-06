package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class LibsuRootShellTest {
    @Test
    public void parsesPidExecutableAndStartTime() {
        List<RootShell.ProcessEntry> entries = LibsuRootShell.parseProcessList(lines(
                LibsuRootShell.PID_MARKER + "41",
                "/data/app/lib/libsyncthingnative.so",
                LibsuRootShell.STAT_MARKER,
                statLine(41, "syncthing", 9001)
        ));

        assertEquals(1, entries.size());
        assertEquals(41, entries.get(0).pid());
        assertEquals("/data/app/lib/libsyncthingnative.so", entries.get(0).executablePath());
        assertEquals(9001, entries.get(0).processStartTimeTicks());
    }

    @Test
    public void reportsAnUnreadableExecutableAsNull() {
        List<RootShell.ProcessEntry> entries = LibsuRootShell.parseProcessList(lines(
                LibsuRootShell.PID_MARKER + "7",
                "",
                LibsuRootShell.STAT_MARKER,
                statLine(7, "kthreadd", 120)
        ));

        assertEquals(1, entries.size());
        assertNull(entries.get(0).executablePath());
        assertEquals(120, entries.get(0).processStartTimeTicks());
    }

    @Test
    public void reportsAMissingStatLineAsZeroStartTime() {
        List<RootShell.ProcessEntry> entries = LibsuRootShell.parseProcessList(lines(
                LibsuRootShell.PID_MARKER + "9",
                "/data/app/lib/libsyncthingnative.so",
                LibsuRootShell.STAT_MARKER
        ));

        assertEquals(1, entries.size());
        assertEquals(0, entries.get(0).processStartTimeTicks());
    }

    @Test
    public void startTimeUsesTheLastClosingParenthesis() {
        assertEquals(9001, LibsuRootShell.parseStartTimeTicks(
                statLine(41, "we)ird (name)", 9001)));
        assertEquals(0, LibsuRootShell.parseStartTimeTicks("not a stat line"));
        assertEquals(0, LibsuRootShell.parseStartTimeTicks(null));
    }

    @Test
    public void findsTheRunTokenInTheNulSeparatedEnvironment() {
        assertEquals("token-a", LibsuRootShell.parseRunToken(Arrays.asList(
                "HOME=/data/user/0/app",
                LibsuRootShell.RUN_TOKEN_ENVIRONMENT + "=token-a",
                "PATH=/system/bin"
        )));
        assertNull(LibsuRootShell.parseRunToken(Arrays.asList("HOME=/data/user/0/app")));
    }

    @Test
    public void runTokenScriptReadsTheProcessEnvironment() {
        assertEquals(
                "tr '\\0' '\\n' < /proc/41/environ 2>/dev/null",
                LibsuRootShell.runTokenScript(41)
        );
    }

    @Test
    public void processListScriptHasNoMountMasterAndListsProcEntries() {
        String script = LibsuRootShell.processListScript();

        assertTrue(script.contains("for standroid_proc in /proc/[0-9]*"));
        assertTrue(script.contains("readlink \"$standroid_proc/exe\""));
        assertTrue(script.contains("cat \"$standroid_proc/stat\""));
        assertFalse(script.contains("--mount-master"));
    }

    private static List<String> lines(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static String statLine(int pid, String name, long startTicks) {
        return pid + " (" + name + ") R 1 " + pid + " " + pid + " 0 -1 4194624 100 0 0 0 1 2 0 0"
                + " 20 0 3 0 " + startTicks + " 12345";
    }

    @Test
    public void missingStatLineDoesNotConsumeTheFollowingEntry() {
        List<RootShell.ProcessEntry> entries = LibsuRootShell.parseProcessList(lines(
                LibsuRootShell.PID_MARKER + "4001",
                "/data/app/lib/libsyncthingnative.so",
                LibsuRootShell.STAT_MARKER,
                LibsuRootShell.PID_MARKER + "4002",
                "/data/app/lib/libsyncthingnative.so",
                LibsuRootShell.STAT_MARKER,
                statLine(4002, "syncthing", 9500)
        ));

        assertEquals("both entries must be reported", 2, entries.size());
        assertEquals(4001, entries.get(0).pid());
        assertEquals(
                "the entry whose stat line could not be read reports an unknown start time",
                0,
                entries.get(0).processStartTimeTicks()
        );
        assertEquals(4002, entries.get(1).pid());
        assertEquals(
                "/data/app/lib/libsyncthingnative.so",
                entries.get(1).executablePath()
        );
        assertEquals(
                "the following bundled candidate must keep its real start time",
                9500,
                entries.get(1).processStartTimeTicks()
        );
    }
}
