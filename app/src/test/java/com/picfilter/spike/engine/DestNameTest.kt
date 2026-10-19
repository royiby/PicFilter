package com.picfilter.spike.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Promotion has to be safe to re-run. The promotion table is PixKin's memory of
 * what it copied and the folder is the truth, and the two drift apart whenever
 * app data is cleared — so these pin the rule that reads the folder instead.
 */
class DestNameTest {

    private val folder = mapOf(
        "IMG-20230902-WA0005.jpg" to 1_234_567L,
        "IMG-20171226-WA0001.jpg" to 90_000L,
    )

    @Test
    fun `a photo the folder does not have keeps its name`() {
        assertEquals(
            "IMG-20240101-WA0009.jpg",
            Promoter.destName("IMG-20240101-WA0009.jpg", 7L, 500L, folder),
        )
    }

    @Test
    fun `a photo already there is skipped`() {
        // The case that produced duplicates: same file, re-run after a wipe.
        assertNull(Promoter.destName("IMG-20230902-WA0005.jpg", 7L, 1_234_567L, folder))
    }

    @Test
    fun `the same name with a different size is a different photo`() {
        // One filename, two pictures: the second must not be dropped, and must
        // not land as MediaStore's "(1)" copy either, or the recorded name lies.
        assertEquals(
            "IMG-20230902-WA0005_42.jpg",
            Promoter.destName("IMG-20230902-WA0005.jpg", 42L, 999L, folder),
        )
    }

    @Test
    fun `a nameless item gets one built from its id`() {
        assertEquals("picfilter_42.jpg", Promoter.destName(null, 42L, 10L, folder))
        assertEquals("picfilter_42.jpg", Promoter.destName("", 42L, 10L, folder))
    }

    @Test
    fun `a name with no extension still gets a distinct variant`() {
        assertEquals(
            "photo_42",
            Promoter.destName("photo", 42L, 999L, mapOf("photo" to 1L)),
        )
    }

    @Test
    fun `an empty folder takes everything`() {
        assertEquals("a.jpg", Promoter.destName("a.jpg", 1L, 5L, emptyMap()))
    }
}
