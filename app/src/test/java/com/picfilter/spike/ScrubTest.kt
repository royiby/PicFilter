package com.picfilter.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a crash report may not contain.
 *
 * The app's pitch, on its own first screen, is "Nothing leaves this phone".
 * A reporter is the one thing that contradicts it, so what it sends has to be
 * worth defending line by line -- and the names in this database belong to
 * someone's children.
 */
class ScrubTest {

    private val names = listOf("גבע", "אבי", "אמא", "Dan", "Daniel")

    @Test fun a_name_goes() {
        assertEquals(
            "no face for <name> in this photo",
            Scrub.text("no face for גבע in this photo", names))
    }

    /**
     * The one that bites: a shorter name inside a longer one.
     * Replacing "Dan" first leaves "<name>iel" behind, which still reads as
     * the name. Longest first is not a tidiness preference.
     */
    @Test fun the_longer_name_wins() {
        assertEquals("<name> and <name>", Scrub.text("Daniel and Dan", names))
    }

    @Test fun a_whatsapp_filename_goes() {
        assertEquals(
            "promote failed <photo>",
            Scrub.text("promote failed IMG-20261024-WA0017.jpg", names))
        // The collision suffix this app adds must not survive either.
        assertEquals("<photo>", Scrub.text("IMG-20261024-WA0002_42048.jpg", names))
    }

    @Test fun a_media_id_goes_but_the_shape_stays() {
        assertEquals(
            "no access to content://media/external/images/media/<id>",
            Scrub.text("no access to content://media/external/images/media/42057", names))
    }

    @Test fun a_crop_filename_goes() {
        assertEquals(
            "/data/user/<n>/<pkg>/files/face_thumbs/<crop>",
            Scrub.text("/data/user/0/com.pixkin.app/files/face_thumbs/41409_17.jpg", names))
    }

    @Test fun nothing_in_means_nothing_out() {
        assertEquals("", Scrub.text(null, names))
        assertEquals("", Scrub.text("", names))
    }

    /** A user with nobody enrolled still gets photo names redacted. */
    @Test fun it_works_with_no_names_at_all() {
        assertEquals("<photo>", Scrub.text("IMG-20261024-WA0017.jpg", emptyList()))
    }

    @Test fun a_blank_name_does_not_redact_the_whole_string() {
        assertEquals("hello", Scrub.text("hello", listOf("", "   ")))
    }

    /** The real shape: a message that carries two kinds of secret at once. */
    @Test fun a_realistic_message_is_clean() {
        val out = Scrub.text(
            "RELINK גבע has no crop left: /data/user/0/com.pixkin.app/files/41409_17.jpg " +
                "for content://media/external/images/media/42057 (IMG-20261024-WA0017.jpg)",
            names)
        assertFalse("no name survives", out.contains("גבע"))
        assertFalse("no crop filename survives", out.contains("41409_17"))
        assertFalse("no media id survives", out.contains("42057"))
        assertFalse("no photo filename survives", out.contains("WA0017"))
        assertTrue("the shape is still readable", out.startsWith("RELINK <name> has no crop left"))
    }

    // ---------- throwables ----------

    @Test fun a_throwable_keeps_its_stack_and_loses_its_message() {
        val original = IllegalStateException("no face for גבע")
        val scrubbed = Scrub.throwable(original, names)!!
        assertTrue(scrubbed.message!!.contains("IllegalStateException"))
        assertTrue(scrubbed.message!!.contains("<name>"))
        assertFalse(scrubbed.message!!.contains("גבע"))
        assertEquals(
            "the stack is the useful part and must survive",
            original.stackTrace.first().methodName,
            scrubbed.stackTrace.first().methodName)
    }

    /**
     * A scrubbed outer exception is worthless if the cause still names someone,
     * and this app's catch blocks almost always wrap.
     */
    @Test fun causes_are_scrubbed_too() {
        val t = RuntimeException("outer", IllegalArgumentException("inner for אבי"))
        val scrubbed = Scrub.throwable(t, names)!!
        assertFalse(scrubbed.cause!!.message!!.contains("אבי"))
        assertTrue(scrubbed.cause!!.message!!.contains("<name>"))
    }

    @Test fun a_null_throwable_stays_null() {
        assertEquals(null, Scrub.throwable(null, names))
    }
}
