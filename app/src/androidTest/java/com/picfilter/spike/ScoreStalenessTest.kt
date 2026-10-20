package com.picfilter.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picfilter.spike.data.Db
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Enrolling someone has to invalidate the existing scores.
 *
 * Without this, 638 photos stayed scored against nobody: a score pass was a
 * no-op, KEEP was 0, and auto-rescue would have woken on every new photo and
 * rescued nothing.
 */
@RunWith(AndroidJUnit4::class)
class ScoreStalenessTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "staleness-test.db"
    private lateinit var db: Db

    @Before fun setUp() {
        ctx.deleteDatabase(dbName)
        db = Db(ctx, dbName)
    }

    @After fun tearDown() {
        db.close()
        ctx.deleteDatabase(dbName)
    }

    private val itemId = 4242L

    private fun anItem(): Long {
        db.upsertItem(itemId, "content://test/$itemId", "IMG-20260101-WA0001.jpg",
            "Pictures/WhatsApp/Media/WhatsApp Images/", "WhatsApp Images",
            120_000L, 1_767_000_000L, 1200, 1600)
        return itemId
    }

    private fun score(id: Long) =
        db.putScore(id, 1, 0, 0, 0f, 0, "REVIEW", "FEW_FACES_NO_MATCH", 0.4f)

    private fun vec() = FloatArray(192) { 0.1f }

    @Test fun a_fresh_score_is_not_stale() {
        val id = anItem()
        assertEquals(1, db.unscoredCount())
        score(id)
        assertEquals(0, db.unscoredCount())
        assertEquals(0, db.staleScoreCount())
    }

    @Test fun enrolling_a_person_makes_every_score_stale() {
        val id = anItem()
        score(id)
        assertEquals(0, db.unscoredCount())

        val pid = db.upsertPerson("גבע")
        db.addCentroid(pid, vec(), 20)

        assertEquals("the score predates this centroid", 1, db.staleScoreCount())
        assertEquals("so it must be queued for rescoring", 1, db.unscoredCount())
    }

    @Test fun rescoring_clears_the_staleness() {
        val id = anItem()
        score(id)
        db.addCentroid(db.upsertPerson("אבי"), vec(), 12)
        assertEquals(1, db.unscoredCount())

        score(id)                                    // the rescore
        assertEquals(0, db.unscoredCount())
        assertEquals(0, db.staleScoreCount())
    }

    @Test fun refitting_a_threshold_also_invalidates() {
        // A threshold change moves the MATCH/NO_MATCH boundary, so the stored
        // verdicts are no longer the verdicts this configuration would give.
        val id = anItem()
        val pid = db.upsertPerson("מיכל")
        db.addCentroid(pid, vec(), 8)
        score(id)
        assertEquals(0, db.unscoredCount())

        db.setPersonThreshold(pid, 0.82f)
        assertEquals(1, db.unscoredCount())
    }

    @Test fun rewriting_the_same_threshold_invalidates_nothing() {
        // The bump restales every score in the database -- 641 of them on the
        // real device, three scoring passes and two minutes of face detection.
        // A fitter that re-confirms a threshold it already holds must not cost
        // that, or agreeing with itself becomes the most expensive thing it
        // does.
        val id = anItem()
        val pid = db.upsertPerson("נועה")
        db.addCentroid(pid, vec(), 8)
        db.setPersonThreshold(pid, 0.82f)
        score(id)
        assertEquals(0, db.unscoredCount())

        db.setPersonThreshold(pid, 0.82f)
        assertEquals("the bar did not move", 0, db.unscoredCount())

        db.setPersonThreshold(pid, 0.83f)
        assertEquals("but this one did", 1, db.unscoredCount())
    }

    @Test fun wiping_enrolment_also_invalidates() {
        val id = anItem()
        db.addCentroid(db.upsertPerson("דני"), vec(), 10)
        score(id)
        assertEquals(0, db.unscoredCount())

        db.clearEnrollment()
        assertEquals(1, db.unscoredCount())
    }

    @Test fun the_revision_only_ever_moves_forward() {
        val before = db.centroidRev()
        db.addCentroid(db.upsertPerson("אמא"), vec(), 7)
        val after = db.centroidRev()
        assert(after > before) { "centroid revision went $before -> $after" }
    }

    @Test fun rescoring_does_not_accumulate_face_rows() {
        // score() re-detects, and addFace is a plain insert; without clearing
        // first, every rescore would double the face count for that item.
        val id = anItem()
        db.addFace(id, 100, 50.0, 0f, 0f, "NO_MATCH", null, 0.4f)
        assertEquals(1, db.faceCountFor(id))

        db.clearFaces(id)
        db.addFace(id, 100, 50.0, 0f, 0f, "NO_MATCH", null, 0.4f)
        assertEquals(1, db.faceCountFor(id))
    }
}
