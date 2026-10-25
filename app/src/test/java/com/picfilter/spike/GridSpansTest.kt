package com.picfilter.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many columns the photo grid gets for a given window width.
 *
 * The bug: the count was the constant 3, derived from how a phone looks in
 * portrait. Rotating gave an 892dp window the same three columns, so each
 * tile came out ~770px and a single cropped row filled the screen while the
 * rest of the width went to nothing.
 *
 * The floor of 3 is not arbitrary and is the part most likely to be broken by
 * a later tweak to the target width: every phone in portrait must keep the
 * three columns it has always had.
 */
class GridSpansTest {

    @Test fun every_common_phone_portrait_width_still_gets_three() {
        // 360 is the narrowest width in common use; 393 and 411 are the usual
        // mid-size and large phones.
        for (dp in listOf(320f, 360f, 384f, 393f, 411f, 432f)) {
            assertEquals("portrait $dp dp", 3, spansFor(dp))
        }
    }

    @Test fun landscape_earns_more_columns() {
        // The window this was found in: a 1080x2340 phone at density 480,
        // which is 360dp portrait and 780dp landscape. The old fixed 3 put
        // 770px tiles in that 780dp window -- one row, cropped.
        assertEquals(3, spansFor(360f))
        assertEquals(6, spansFor(780f))
        assertEquals("a wider landscape window earns one more", 7, spansFor(892f))
        assertTrue("landscape must beat portrait", spansFor(780f) > spansFor(360f))
    }

    @Test fun a_tablet_is_capped_rather_than_shredded() {
        assertEquals("a very wide window stops at 8", 8, spansFor(4000f))
        assertEquals(8, spansFor(1280f))
    }

    @Test fun an_absurdly_narrow_window_does_not_go_below_three() {
        assertEquals(3, spansFor(1f))
        assertEquals("zero must not divide into something negative", 3, spansFor(0f))
    }

    /** A tile is never wider than the window, whatever the count. */
    @Test fun the_count_always_divides_the_width_sensibly() {
        for (dp in listOf(320f, 360f, 411f, 600f, 892f, 1280f)) {
            val n = spansFor(dp)
            assertTrue("$dp dp -> $n spans", n in 3..8)
        }
    }
}
