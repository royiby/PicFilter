package com.picfilter.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picfilter.spike.data.Db
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the auto-save intent is kept, which is the whole point of it.
 *
 * It lives in `picfilter.db` rather than in SharedPreferences because the
 * database is the thing `db-backup.sh` moves. WorkManager's schedule is in
 * `no_backup/`, private to the applicationId, and travels nowhere -- so a
 * restored install used to come up with every person and label intact and
 * auto-save silently off. These tests pin the storage, not the scheduling;
 * WatcherArmingTest covers what is done with the answer.
 */
@RunWith(AndroidJUnit4::class)
class AutoSaveIntentTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "autosave-test.db"
    private lateinit var db: Db

    @Before fun setUp() {
        ctx.deleteDatabase(dbName)
        db = Db(ctx, dbName)
    }

    @After fun tearDown() { db.close(); ctx.deleteDatabase(dbName) }

    /**
     * Null, not false. A fresh database has no answer, and reading it as "off"
     * would let the heal cancel work on a install that had auto-save running.
     */
    @Test fun a_fresh_database_has_no_recorded_intent() {
        assertNull(db.autoSaveWanted())
    }

    @Test fun the_intent_round_trips_both_ways() {
        db.setAutoSaveWanted(true)
        assertEquals(true, db.autoSaveWanted())

        db.setAutoSaveWanted(false)
        assertEquals(false, db.autoSaveWanted())
    }

    @Test fun setting_it_twice_leaves_one_row() {
        db.setAutoSaveWanted(true)
        db.setAutoSaveWanted(true)
        db.setAutoSaveWanted(false)
        val rows = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM meta WHERE k='autosave_wanted'", null
        ).use { if (it.moveToFirst()) it.getInt(0) else -1 }
        assertEquals("INSERT OR REPLACE, not an append", 1, rows)
        assertEquals(false, db.autoSaveWanted())
    }

    /**
     * The restore, in miniature: the intent has to survive closing and
     * reopening the file, because that is the only way it reaches the new
     * install.
     */
    @Test fun the_intent_survives_a_reopen() {
        db.setAutoSaveWanted(true)
        db.close()

        db = Db(ctx, dbName)
        assertEquals("a reopened database must remember", true, db.autoSaveWanted())
    }

    /** It must not disturb the other meta key sharing the table. */
    @Test fun it_does_not_collide_with_the_centroid_revision() {
        val rev = db.bumpCentroidRev()
        db.setAutoSaveWanted(true)
        assertEquals("revision untouched", rev, db.centroidRev())
        assertTrue(db.autoSaveWanted() == true)

        db.bumpCentroidRev()
        assertTrue("intent untouched", db.autoSaveWanted() == true)
        assertFalse(db.autoSaveWanted() == false)
    }
}
