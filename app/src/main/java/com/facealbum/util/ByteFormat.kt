package com.facealbum.util

import java.util.Locale

/**
 * Formats a byte count into a short, human-readable string such as
 * `"842 kB"`, `"3.7 MB"`, or `"1.20 GB"`.
 *
 * **Unit convention: decimal (SI), not binary.** 1 kB = 1000 B, 1 MB =
 * 1000 kB, and so on — matching `android.text.format.Formatter.formatFileSize`,
 * which is what the system file manager, Settings > Storage, and share
 * sheets use. Matching that convention means a size shown in FaceAlbum
 * reads the same as the same file's size everywhere else on the device,
 * rather than disagreeing by ~7% the way a binary (1024-based) KiB/MiB
 * reading would. Capitalization follows the same framework convention:
 * lowercase `"kB"` (the correct SI symbol for kilo), uppercase `"MB"`/`"GB"`.
 *
 * This function is deliberately a pure, dependency-free Kotlin function
 * rather than a thin wrapper around `Formatter.formatFileSize` — that call
 * needs an Android `Context` and can't run on the plain JVM, which would
 * force every caller (and every test of a caller) onto Robolectric just to
 * format a number.
 *
 * Precision is 0 decimal places in the kB range, 1 in the MB range, and 2 in
 * the GB range — more digits as the unit gets coarser, since a whole kB is
 * negligible but a whole GB is not. Formatting is pinned to [Locale.US] so
 * output (in particular the decimal separator) is deterministic regardless
 * of the device's locale — this app does not localize numeric file sizes.
 *
 * @param bytes byte count. `0` returns `"0 B"`. Negative values (including
 * the `-1` MediaStore itself returns for a size it doesn't know) are treated
 * as "unknown" and return a fixed placeholder rather than a nonsensical
 * negative size.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "Unknown size"
    if (bytes == 0L) return "0 B"
    return when {
        bytes < 1_000L -> "$bytes B"
        bytes < 1_000_000L -> "%.0f kB".format(Locale.US, bytes / 1_000.0)
        bytes < 1_000_000_000L -> "%.1f MB".format(Locale.US, bytes / 1_000_000.0)
        else -> "%.2f GB".format(Locale.US, bytes / 1_000_000_000.0)
    }
}
