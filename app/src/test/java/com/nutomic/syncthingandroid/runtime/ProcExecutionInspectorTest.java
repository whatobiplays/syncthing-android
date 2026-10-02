package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class ProcExecutionInspectorTest {
    @Test
    public void parsesStartTimeAfterACommandNameContainingParentheses() throws Exception {
        StringBuilder stat = new StringBuilder("123 (syncthing (worker)) S");
        for (int fieldIndex = 1; fieldIndex < 20; fieldIndex++) {
            stat.append(' ').append(fieldIndex == 19 ? "98765" : "0");
        }

        assertEquals(
                98765L,
                ProcExecutionInspector.readStartTimeTicks(
                        stat.toString().getBytes(StandardCharsets.UTF_8)
                )
        );
    }

    @Test
    public void rejectsMalformedOrNonpositiveStartTime() {
        IOException malformed = assertThrows(IOException.class,
                () -> ProcExecutionInspector.readStartTimeTicks(
                "123 no-closing-parenthesis".getBytes(StandardCharsets.UTF_8)
        ));
        assertEquals("Malformed proc stat record", malformed.getMessage());

        StringBuilder nonpositive = new StringBuilder("123 (syncthing) S");
        for (int fieldIndex = 0; fieldIndex < 20; fieldIndex++) {
            nonpositive.append(" 0");
        }
        IOException invalidStart = assertThrows(IOException.class,
                () -> ProcExecutionInspector.readStartTimeTicks(
                        nonpositive.toString().getBytes(StandardCharsets.UTF_8)
                ));
        assertEquals("Invalid proc start time", invalidStart.getMessage());
    }

    @Test
    public void bootIdParsingRejectsEmptyAndWhitespaceOnlyValues() {
        assertThrows(IOException.class,
                () -> ProcExecutionInspector.parseBootId(new byte[0]));
        assertThrows(IOException.class,
                () -> ProcExecutionInspector.parseBootId(" \n\t ".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void bootIdParsingTrimsValidProcfsValue() throws Exception {
        assertEquals("boot-a", ProcExecutionInspector.parseBootId(
                " boot-a\n".getBytes(StandardCharsets.UTF_8)
        ));
    }

    @Test
    public void recognizesBundledExecutableAcrossInstallPathsAndDeletedProcTargets() {
        String currentPath = "/data/app/current/lib/libsyncthingnative.so";

        assertTrue(ProcExecutionInspector.isBundledExecutableCandidate(
                "/data/app/previous/lib/libsyncthingnative.so", currentPath
        ));
        assertTrue(ProcExecutionInspector.isBundledExecutableCandidate(
                "/data/app/previous/lib/libsyncthingnative.so (deleted)", currentPath
        ));
        assertFalse(ProcExecutionInspector.isBundledExecutableCandidate(
                "/data/app/previous/lib/libother.so", currentPath
        ));
        assertTrue(ProcExecutionInspector.hasExactExecutablePath(currentPath, currentPath));
        assertFalse(ProcExecutionInspector.hasExactExecutablePath(
                "/data/app/previous/lib/libsyncthingnative.so", currentPath
        ));
        assertFalse(ProcExecutionInspector.hasExactExecutablePath(
                "/data/app/previous/lib/libsyncthingnative.so (deleted)", currentPath
        ));
        assertTrue(ProcExecutionInspector.hasExactExecutablePath(
                currentPath + " (deleted)", currentPath
        ));
    }
}
