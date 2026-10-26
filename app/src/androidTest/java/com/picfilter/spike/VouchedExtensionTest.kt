package com.picfilter.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picfilter.spike.data.Db
import com.picfilter.spike.data.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which human verdicts are allowed to widen a person.
 *
 * A keep is a judgement about a photo, not about a face, so attribution has to
 * be earned: the wrong rule here teaches a person someone else's face, and a
 * centroid that has drifted quietly mislabels everything afterwards.
 */
@RunWith(AndroidJUnit4::class)
class VouchedExtensionTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "vouched-test.db"
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

    private fun vouched() = db.vouchedNearMisses(0.55f, 50)

    @Test fun a_kept_photo_with_one_near_miss_teaches_that_person() {
        item(1); score(1, 1, 0); face(1, alice, "NO_MATCH", 0.62f); db.putLabel(1, "KEEP")
        val v = vouched()
        assertEquals(1, v.size)
        assertEquals(alice, v[0].first)
    }

    @Test fun a_keep_explained_by_a_match_teaches_nothing() {
        // Someone the app already recognised is in this photo, so the keep says
        // nothing about the other, unmatched face.
        item(2); score(2, 2, 1)
        face(2, bob, "MATCH", 0.91f)
        face(2, alice, "NO_MATCH", 0.62f)
        db.putLabel(2, "KEEP")
        assertTrue(vouched().isEmpty())
    }

    @Test fun two_near_misses_in_one_photo_are_ambiguous() {
        // A keep is about the photo. With two candidates there is no way to
        // tell which face the user meant, so neither is used.
        item(3); score(3, 2, 0)
        face(3, alice, "NO_MATCH", 0.62f)
        face(3, bob, "NO_MATCH", 0.58f)
        db.putLabel(3, "KEEP")
        assertTrue(vouched().isEmpty())
    }

    @Test fun a_distant_face_is_not_a_near_miss() {
        // Far enough away and it is as likely to be a stranger standing beside
        // them; the keep was about the picture.
        item(4); score(4, 1, 0); face(4, alice, "NO_MATCH", 0.31f); db.putLabel(4, "KEEP")
        assertTrue(vouched().isEmpty())
    }

    @Test fun a_discarded_photo_teaches_nothing() {
        item(5); score(5, 1, 0); face(5, alice, "NO_MATCH", 0.62f); db.putLabel(5, "DISCARD")
        assertTrue(vouched().isEmpty())
    }

    @Test fun an_unreviewed_photo_teaches_nothing() {
        item(6); score(6, 1, 0); face(6, alice, "NO_MATCH", 0.62f)
        assertTrue(vouched().isEmpty())
    }

    @Test fun requeueing_skips_what_an_added_centroid_cannot_change() {
        // Monotonic: matching takes the max over a person's set, so an item
        // that already matched cannot stop matching, and one with no faces can
        // never start. Only faces-but-no-match needs looking at again.
        item(10); score(10, 2, 1)      // already matched
        item(11); score(11, 0, 0)      // no faces at all
        item(12); score(12, 3, 0)      // faces, matched nobody
        item(13); score(13, 1, 0)      // faces, matched nobody
        assertEquals(2, db.invalidateUnmatched())
        assertEquals("only the reconsiderable ones are queued", 2, db.unscoredCount())
    }
}
