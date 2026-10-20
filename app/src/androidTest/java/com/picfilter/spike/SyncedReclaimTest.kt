package com.picfilter.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picfilter.spike.data.Db
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reclaiming a local copy must not invite the app to copy it straight back.
 *
 * The folder and the promotion table together decide what still needs
 * rescuing, so a file that is deliberately gone -- because it is in the cloud
 * and the space was reclaimed -- has to stay recorded, and has to stay out of
 * the promotion queue.
 */
@RunWith(AndroidJUnit4::class)
class SyncedReclaimTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "synced-test.db"
    private lateinit var db: Db
    private val itemId = 9100L
    private val destName = "IMG-20260630-WA0021.jpg"

    @Before fun setUp() {
        ctx.deleteDatabase(dbName)
        db = Db(ctx, dbName)
        db.upsertItem(itemId, "content://test/$itemId", destName,
            "Pictures/WhatsApp/Media/WhatsApp Images/", "WhatsApp Images",
            120_000L, 1_767_000_000L, 1200, 1600)
        db.putScore(itemId, 1, 1, 0, 0f, 0, "KEEP", "ENROLLED_PERSON", 1.0f)
    }

    @After fun tearDown() { db.close(); ctx.deleteDatabase(dbName) }

    @Test fun a_keeper_with_no_promotion_is_queued_for_copying() {
        assertEquals(1, db.itemsForPromotion(listOf("KEEP")).size)
    }

    @Test fun recording_the_promotion_takes_it_out_of_the_queue() {
        db.recordPromotion(itemId, "content://dest/1", destName, 120_000L)
        assertEquals(0, db.itemsForPromotion(listOf("KEEP")).size)
    }

    @Test fun marking_it_synced_keeps_it_out_of_the_queue() {
        // The point of the whole feature: the local file is about to be
        // removed, and that must not read as "never rescued".
        db.recordPromotion(itemId, "content://dest/1", destName, 120_000L)
        assertEquals(1, db.markSynced(listOf(itemId)))
        assertEquals(1, db.syncedCount())
        assertTrue(destName in db.syncedDestNames())
        assertEquals("still not a candidate", 0, db.itemsForPromotion(listOf("KEEP")).size)
    }

    @Test fun marking_is_idempotent() {
        db.recordPromotion(itemId, "content://dest/1", destName, 120_000L)
        db.markSynced(listOf(itemId))
        db.markSynced(listOf(itemId))
        assertEquals(1, db.syncedCount())
    }

    @Test fun an_unsynced_promotion_is_not_reported_as_synced() {
        db.recordPromotion(itemId, "content://dest/1", destName, 120_000L)
        assertEquals(0, db.syncedCount())
        assertTrue(db.syncedDestNames().isEmpty())
    }
}
