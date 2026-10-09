package com.nutomic.syncthingandroid.runtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Deterministic tests for the member content one ignore list describes.
 *
 * <p>Both backends write the same folder member, so the exact bytes matter: a pattern list is
 * stored one pattern per line, and the encoding must stay identical across application-UID and
 * privileged writes.</p>
 */
public class IgnoreListEncodingTest {
    @Test
    public void joinsOneLinePerPatternWithASingleLineFeed() {
        assertArrayEquals(
                "one\nline\nper pattern".getBytes(StandardCharsets.UTF_8),
                IgnoreListEncoding.encode(new String[] {"one", "line", "per pattern"})
        );
    }

    @Test
    public void encodesNoBytesForAListWithoutPatterns() {
        assertEquals(0, IgnoreListEncoding.encode(new String[0]).length);
    }

    @Test
    public void keepsEmptyPatternsInTheirPosition() {
        assertArrayEquals(
                "a\n\nb".getBytes(StandardCharsets.UTF_8),
                IgnoreListEncoding.encode(new String[] {"a", "", "b"})
        );
    }

    @Test
    public void encodesNonAsciiPatternsAsUtf8() {
        assertArrayEquals(
                "\u00fc/notes".getBytes(StandardCharsets.UTF_8),
                IgnoreListEncoding.encode(new String[] {"\u00fc/notes"})
        );
    }
}
