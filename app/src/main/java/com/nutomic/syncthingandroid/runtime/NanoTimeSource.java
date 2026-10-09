package com.nutomic.syncthingandroid.runtime;

/**
 * Reads a monotonic clock value in nanoseconds.
 *
 * <p>The shape exists so a folder operation can bound its work with a clock that a test controls.
 * It is declared here instead of using {@code java.util.function}, because that package is not
 * available on the oldest Android version this application supports.</p>
 */
@FunctionalInterface
interface NanoTimeSource {
    /** Returns the current monotonic clock value in nanoseconds. */
    long readNanos();
}
