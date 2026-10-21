package com.picfilter.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picfilter.spike.data.Db
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Taking someone out of the enrolled set.
 *
 * Destructive and user-facing, so the blast radius is the thing under test:
 * removing one person must not disturb another, must not touch photos already
 * rescued, and must requeue only the items whose verdict can actually change.
 */
@RunWith(AndroidJUnit4::class)
class RemovePersonTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "removeperson-test.db"
    private lateinit var db: Db
    private var alice = 0L
    private var bob = 0L

    @Before fun setUp() {
        ctx.deleteDatabase(dbName)
        db = Db(ctx, dbName)
        alice = db.upsertPerson("alice")
        bob = db.upsertPerson("bob")
        db.addCentroid(alice, vec(), 8)
        db.addCentroid(bob, vec(), 8)
    }

    @After fun tearDown() { db.close(); ctx.deleteDatabase(dbName) }

    private fun vec() = FloatArray(192) { 0.1f }

    private fun item(id: Long) = db.upsertItem(
        id, "content://test/$id", "IMG-$id.jpg",
        "Pictures/WhatsApp/Media/WhatsApp Images/", "WhatsApp Images",
        100L, 1_767_000_000L, 1000, 1000)

    /** A scored photo containing one MATCH face for [person]. */
    private fun photoOf(id: Long, person: Long) {
        item(id)
        db.putScore(id, 1, 1, 0, 0f, 0, "KEEP", "ENROLLED_PERSON", 1f)
        db.addFace(id, 120, 50.0, 0f, 0f, "MATCH", person, 0.9f)
    }

    private fun photoOfNobody(id: Long) {
        item(id)
        db.putScore(id, 2, 0, 0, 0f, 0, "DISCARD_CANDIDATE", "CROWD_NO_MATCH", 0.8f)
        db.addFace(id, 120, 50.0, 0f, 0f, "NO_MATCH", null, 0.2f)
    }

    @Test fun the_person_and_their_centroids_are_gone() {
        db.removePerson(alice)
        assertNull("alice must not be in the people list", db.personNames()[alice])
        assertTrue("and nothing may match her again",
            db.centroidsByPerson()[alice].isNullOrEmpty())
    }

    @Test fun everyone_else_is_untouched() {
        db.removePerson(alice)
        assertEquals("bob", db.personNames()[bob])
        assertEquals(1, db.centroidsByPerson()[bob]?.size)
    }

    @Test fun only_their_own_photos_are_scored_again() {
        photoOf(1, alice)
        photoOf(2, alice)
        photoOf(3, bob)
        photoOfNobody(4)
        assertEquals(0, db.unscoredCount())

        val requeued = db.removePerson(alice)

        assertEquals("only alice's two", 2, requeued)
        assertEquals(2, db.unscoredCount())
        // Removing a person can turn a MATCH into a NO_MATCH and nothing else,
        // so every other verdict in the database is still correct. Bumping the
        // global revision would re-read the whole library to re-confirm
        // answers that cannot have changed.
    }

    @Test fun their_cluster_goes_back_to_being_offerable() {
        // So a removal by mistake can be undone by naming them again, rather
        // than by re-running the four-and-a-half-minute scan.
        val cid = db.addCluster(6, vec())
        db.addClusterFace(cid, 10L, null, 120, vec())
        db.setClusterStatus(cid, "NAMED", alice)
        assertTrue("claimed clusters are not offered",
            db.candidates(limit = 10, thumbsEach = 1).none { it.id == cid })

        db.removePerson(alice)

        assertTrue("the cluster is unclaimed again",
            db.clusterCentroids().any { it.first == cid })
    }

    @Test fun a_photo_already_rescued_is_left_alone() {
        // Removing someone changes who PixKin recognises from now on. It does
        // not reach into the backed-up folder and take things out.
        photoOf(5, alice)
        db.recordPromotion(5, "content://dest/5", "IMG-5.jpg", 1234L)
        db.removePerson(alice)
        assertEquals(1, db.promotionCount())
    }
}
