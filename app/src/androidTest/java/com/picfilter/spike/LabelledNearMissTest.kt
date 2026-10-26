package com.picfilter.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picfilter.spike.data.Db
import com.picfilter.spike.data.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of a threshold that lives below the bar.
 *
 * Fitting from matched faces alone measures precision over what was kept and
 * never sees a false negative, so the bar could only ever rise -- three
 * people's fitted thresholds became unreachable that way. These pin the query
 * that supplies the other side.
 *
 * Attribution is the same earned rule as [VouchedExtensionTest], because a
 * verdict is about a photo and not about a face. The difference is polarity:
 * widening a centroid can only learn from a KEEP, while a threshold learns from
 * both -- a keep below the bar is a face that should have matched, a discard
 * below it is a face that correctly did not.
 */
@RunWith(AndroidJUnit4::class)
class LabelledNearMissTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "nearmiss-test.db"
    private lateinit var db: Db
    private var alice = 0L
    private var bob = 0L

    @Before fun setUp() {
        ctx.deleteDatabase(dbName)
        db = Db(ctx, dbName)
        alice = db.upsertPerson("alice")
        bob = db.upsertPerson("bob")
    }

    @After fun tearDown() { db.close(); ctx.deleteDatabase(dbName) }

    private fun item(id: Long) = db.upsertItem(
        id, "content://test/$id", "IMG-$id.jpg",
        "Pictures/WhatsApp/Media/WhatsApp Images/", "WhatsApp Images",
        100L, 1_767_000_000L, 1000, 1000)

    private fun score(id: Long, faces: Int, matched: Int) =
        db.putScore(id, faces, matched, 0, 0f, 0,
            if (matched > 0) "KEEP" else "REVIEW", "r", 0f)

    private fun face(id: Long, person: Long?, state: String, sim: Float?) =
        db.addFace(id, 120, 50.0, 0f, 0f, state, person, sim)

    private fun below() = db.labelledNearMisses(0.55f)

    @Test fun a_kept_photo_below_the_bar_is_a_face_that_should_have_matched() {
        item(1); score(1, 1, 0); face(1, alice, "NO_MATCH", 0.62f); db.putLabel(1, "KEEP")
        val pts = below()
        assertEquals(1, pts.size)
        assertEquals(alice, pts[0].personId)
        assertEquals(0.62f, pts[0].sim, 1e-6f)
        assertTrue("a keep below the bar is a false negative", pts[0].correct)
    }

    @Test fun a_discarded_photo_below_the_bar_is_a_face_that_correctly_did_not() {
        // The asymmetry with vouchedNearMisses, which ignores discards: this is
        // the evidence that keeps a bar from dropping into a band of strangers.
        item(2); score(2, 1, 0); face(2, alice, "NO_MATCH", 0.62f); db.putLabel(2, "DISCARD")
        val pts = below()
        assertEquals(1, pts.size)
        assertEquals(alice, pts[0].personId)
        assertFalse("a discard below the bar is a true negative", pts[0].correct)
    }

    @Test fun a_verdict_explained_by_a_match_teaches_nothing() {
        // Someone already recognised is in this photo, so the verdict is about
        // them and says nothing about the other face.
        item(3); score(3, 2, 1)
        face(3, bob, "MATCH", 0.91f)
        face(3, alice, "NO_MATCH", 0.62f)
        db.putLabel(3, "KEEP")
        assertTrue(below().isEmpty())
    }

    @Test fun two_near_faces_in_one_photo_are_ambiguous() {
        item(4); score(4, 2, 0)
        face(4, alice, "NO_MATCH", 0.62f)
        face(4, bob, "NO_MATCH", 0.58f)
        db.putLabel(4, "DISCARD")
        assertTrue(below().isEmpty())
    }

    @Test fun a_distant_face_is_not_evidence_about_the_boundary() {
        // Under the floor it is a stranger either way, and a point there would
        // be fitted into a band the calibration says is empty of family.
        item(5); score(5, 1, 0); face(5, alice, "NO_MATCH", 0.31f); db.putLabel(5, "KEEP")
        assertTrue(below().isEmpty())
    }

    @Test fun an_unreviewed_photo_teaches_nothing() {
        item(6); score(6, 1, 0); face(6, alice, "NO_MATCH", 0.62f)
        assertTrue(below().isEmpty())
    }

    @Test fun the_two_queries_together_cover_both_sides_of_the_bar() {
        // What the fitter actually consumes: a match above the bar from the
        // saved-photos queue, a near miss below it from the rescue queue.
        item(7); score(7, 1, 1); face(7, alice, "MATCH", 0.88f); db.putLabel(7, "KEEP")
        item(8); score(8, 1, 0); face(8, alice, "NO_MATCH", 0.60f); db.putLabel(8, "KEEP")

        val above = db.labelledMatches()
        val under = below()
        assertEquals("one labelled match", 1, above.size)
        assertEquals("one labelled near miss", 1, under.size)
        assertTrue("the near miss is under the default bar",
            under[0].sim < com.picfilter.spike.engine.FaceEngine.MATCH_THRESHOLD)
        assertTrue("the match is at or over it",
            above[0].sim >= com.picfilter.spike.engine.FaceEngine.MATCH_THRESHOLD)
    }

    @Test fun a_discard_still_teaches_a_centroid_nothing() {
        // Guards the boundary between the two uses: the same row that gives the
        // fitter a negative point must not widen anyone's centroid.
        item(9); score(9, 1, 0); face(9, alice, "NO_MATCH", 0.62f); db.putLabel(9, "DISCARD")
        assertEquals("the fitter sees it", 1, below().size)
        assertTrue("the centroid does not", db.vouchedNearMisses(0.55f, 50).isEmpty())
    }
}
