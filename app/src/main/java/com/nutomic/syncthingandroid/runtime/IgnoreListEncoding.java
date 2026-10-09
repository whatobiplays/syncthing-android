package com.nutomic.syncthingandroid.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Encodes one ignore list into the exact bytes a folder member holds.
 *
 * <p>Both backends must produce byte-identical member content for the same list, so the encoding
 * lives in one place instead of once per backend. It is written by hand rather than through
 * {@code String.join}, because that method only exists from Android API 26 while this application
 * supports older releases.</p>
 */
final class IgnoreListEncoding {
    private IgnoreListEncoding() {
    }

    /**
     * Returns the member content one ignore list describes.
     *
     * <p>Every element becomes one line and the lines are joined by single line feeds, so an
     * element the user left empty stays an empty line in its original position. The result carries
     * no trailing line break and is encoded as UTF-8. An empty list therefore encodes to no bytes
     * at all.</p>
     *
     * @param lines ignore patterns in the order the list holds them
     * @return UTF-8 bytes of the member content
     */
    static byte[] encode(String[] lines) {
        Objects.requireNonNull(lines, "The ignore list is required");
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                content.append('\n');
            }
            content.append(lines[index]);
        }
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }
}
