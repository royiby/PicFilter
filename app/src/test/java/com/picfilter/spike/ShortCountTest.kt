package com.picfilter.spike

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Counts have to fit a stat cell, which is a third of the screen.
 *
 * Probed on the device before this existed: six digits reached the cell's
 * padding with nothing to spare, and "148912" is not a number anyone reads at
 * a glance. Four characters always fit and always read.
 */
class ShortCountTest {

    private fun s(n: Int) = Ui.shortCount(n)

    @Test fun `small counts are exact, because they are the ones that matter`() {
        // How many photos PixKin rescued is a figure someone checks, not skims.
        assertEquals("0", s(0))
        assertEquals("113", s(113))
        assertEquals("999", s(999))
    }

    @Test fun `a thousand is where shortening starts`() {
        assertEquals("1K", s(1_000))
        assertEquals("1.1K", s(1_134))
        assertEquals("9.9K", s(9_900))
    }

    @Test fun `the decimal is dropped once it stops earning its place`() {
        assertEquals("10K", s(9_999))
        assertEquals("10K", s(10_000))
        // Rounded, not truncated: 148,912 is nearer 149 thousand than 148.
        assertEquals("149K", s(148_912))
        assertEquals("999K", s(999_400))
    }

    @Test fun `the rounding does not invent a thousand-K`() {
        // 999,999 / 1000 rounds to 1000, which is wrong and five wide.
        assertEquals("1M", s(999_999))
        assertEquals("1M", s(999_500))
        assertEquals("999K", s(999_499))
    }

    @Test fun `millions, which this will not see`() {
        assertEquals("1M", s(1_000_000))
        assertEquals("1.5M", s(1_500_000))
        assertEquals("12M", s(12_300_000))
    }

    @Test fun `nothing is ever wider than four characters`() {
        for (n in intArrayOf(0, 1, 999, 1_000, 9_999, 10_000, 148_912, 999_999,
                             1_000_000, 9_900_000, 123_456_789)) {
            assertEquals("$n -> ${s(n)} is too wide", true, s(n).length <= 4)
        }
    }
}
