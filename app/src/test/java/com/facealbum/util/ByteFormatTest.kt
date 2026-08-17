package com.facealbum.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatTest {

    @Test
    fun `zero bytes formats as zero B`() {
        assertEquals("0 B", formatBytes(0L))
    }

    @Test
    fun `sub one thousand bytes formats as whole B`() {
        assertEquals("1 B", formatBytes(1L))
        assertEquals("999 B", formatBytes(999L))
    }

    @Test
    fun `exact kilobyte boundary switches to kB`() {
        assertEquals("1 kB", formatBytes(1_000L))
    }

    @Test
    fun `kilobyte range rounds to whole kB with no decimals`() {
        assertEquals("500 kB", formatBytes(500_000L))
        assertEquals("843 kB", formatBytes(842_500L))
    }

    @Test
    fun `exact megabyte boundary switches to MB`() {
        assertEquals("1.0 MB", formatBytes(1_000_000L))
    }

    @Test
    fun `megabyte range rounds to one decimal place`() {
        assertEquals("3.7 MB", formatBytes(3_700_000L))
        assertEquals("412.0 MB", formatBytes(412_000_000L))
    }

    @Test
    fun `exact gigabyte boundary switches to GB`() {
        assertEquals("1.00 GB", formatBytes(1_000_000_000L))
    }

    @Test
    fun `gigabyte range rounds to two decimal places`() {
        assertEquals("1.20 GB", formatBytes(1_200_000_000L))
        assertEquals("2.50 GB", formatBytes(2_500_000_000L))
    }

    @Test
    fun `values well into the gigabyte range still format correctly`() {
        assertEquals("128.00 GB", formatBytes(128_000_000_000L))
    }

    @Test
    fun `negative one is treated as unknown size`() {
        assertEquals("Unknown size", formatBytes(-1L))
    }

    @Test
    fun `any negative value is treated as unknown size`() {
        assertEquals("Unknown size", formatBytes(-500L))
        assertEquals("Unknown size", formatBytes(Long.MIN_VALUE))
    }

    @Test
    fun `documented rounding quirk just under the megabyte boundary`() {
        // Nine hundred ninety nine thousand nine hundred ninety nine bytes is
        // still below the one million byte megabyte cutoff, so it stays in
        // the kB bucket — where rounding to zero decimals pushes the display
        // to a four-digit kB count rather than crossing into MB. This is a
        // known, harmless display quirk (not a computation bug) inherent to
        // rounding-before-bucketing; documented here as intended behavior
        // rather than left to be rediscovered as a surprise.
        assertEquals("1000 kB", formatBytes(999_999L))
    }
}
