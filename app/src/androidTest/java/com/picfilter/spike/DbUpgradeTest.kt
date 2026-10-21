package com.picfilter.spike

import android.database.sqlite.SQLiteDatabase
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
 * The v5 -> v6 upgrade, on a database shaped like the one actually on the phone:
 * scores written before anyone was enrolled, people enrolled afterwards. That
 * database IS the bug, so the upgrade has to mark its scores stale rather than
 * leaving them to look current.
 */
@RunWith(AndroidJUnit4::class)
class DbUpgradeTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "upgrade-test.db"

    @Before fun setUp() = ctx.deleteDatabase(dbName).let { }
    @After fun tearDown() = ctx.deleteDatabase(dbName).let { }

    /**
     * A real v5 database: everything migrations 1-5 would have built.
     *
     * This was "just enough of the v5 schema for the upgrade to act on", and it
     * passed only because no migration before v8 touched the tables it left
     * out. v8 adds an index on `label`, which a real v5 database has had since
     * v2 and this fixture did not -- so the test failed on a schema that cannot
     * exist. A fixture that is a subset of reality tests a database nobody has.
     */
    private fun writeV5(withCentroid: Boolean) {
        val f = ctx.getDatabasePath(dbName)
        f.parentFile?.mkdirs()
        val d = SQLiteDatabase.openOrCreateDatabase(f, null)
        d.execSQL("""CREATE TABLE person(
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE,
            created_at INTEGER NOT NULL, threshold REAL)""")
        d.execSQL("""CREATE TABLE centroid(
            id INTEGER PRIMARY KEY AUTOINCREMENT, person_id INTEGER NOT NULL,
            vec BLOB NOT NULL, face_count INTEGER, created_at INTEGER NOT NULL)""")
        d.execSQL("""CREATE TABLE item(
            id INTEGER PRIMARY KEY, uri TEXT NOT NULL, name TEXT, rel_path TEXT,
            bucket TEXT, size_bytes INTEGER, date_added INTEGER, width INTEGER,
            height INTEGER, dhash INTEGER, scanned_at INTEGER)""")
        d.execSQL("""CREATE TABLE face(
            id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL,
            orig_px INTEGER, sharpness REAL, yaw REAL, roll REAL, state TEXT,
            person_id INTEGER, similarity REAL)""")
        d.execSQL("""CREATE TABLE score(
            item_id INTEGER PRIMARY KEY, faces INTEGER, matched INTEGER,
            indet INTEGER, text_frac REAL, text_blocks INTEGER, dupe_group INTEGER,
            decision TEXT NOT NULL, reason TEXT, confidence REAL,
            scored_at INTEGER NOT NULL)""")
        // v2
        d.execSQL("""CREATE TABLE label(
            item_id INTEGER PRIMARY KEY, label TEXT NOT NULL, at INTEGER NOT NULL,
            note TEXT)""")
        d.execSQL("""CREATE TABLE dupe_verdict(
            group_id INTEGER PRIMARY KEY, verdict TEXT NOT NULL, at INTEGER NOT NULL)""")
        // v3
        d.execSQL("""CREATE TABLE cluster(
            id INTEGER PRIMARY KEY AUTOINCREMENT, size INTEGER NOT NULL,
            centroid BLOB NOT NULL, status TEXT, person_id INTEGER,
            created_at INTEGER NOT NULL)""")
        d.execSQL("""CREATE TABLE cluster_face(
            id INTEGER PRIMARY KEY AUTOINCREMENT, cluster_id INTEGER NOT NULL,
            item_id INTEGER, thumb_path TEXT, orig_px INTEGER, vec BLOB NOT NULL)""")
        // v4
        d.execSQL("""CREATE TABLE promotion(
            item_id INTEGER PRIMARY KEY, dest_uri TEXT, dest_name TEXT,
            bytes INTEGER, at INTEGER NOT NULL)""")

        d.execSQL("INSERT INTO item(id, uri) VALUES(7, 'content://test/7')")
        d.execSQL("""INSERT INTO score(item_id, faces, matched, indet, decision, scored_at)
            VALUES(7, 2, 0, 0, 'DISCARD_CANDIDATE', 1)""")
        if (withCentroid) {
            d.execSQL("INSERT INTO person(id, name, created_at) VALUES(1, 'גבע', 1)")
            d.execSQL("INSERT INTO centroid(person_id, vec, face_count, created_at) VALUES(1, X'00', 20, 1)")
        }
        d.version = 5
        d.close()
    }

    @Test fun a_v5_database_with_people_enrolled_comes_up_stale() {
        writeV5(withCentroid = true)
        val db = Db(ctx, dbName)
        assertEquals("revision must start above the scores'", 1L, db.centroidRev())
        assertEquals("the pre-enrolment score is stale", 1, db.staleScoreCount())
        assertEquals("so it is queued for rescoring", 1, db.unscoredCount())
        db.close()
    }

    @Test fun a_v5_database_with_nobody_enrolled_is_left_alone() {
        // Nothing to re-compare against, so there is no reason to redo the work.
        writeV5(withCentroid = false)
        val db = Db(ctx, dbName)
        assertEquals(0L, db.centroidRev())
        assertEquals(0, db.staleScoreCount())
        assertEquals(0, db.unscoredCount())
        db.close()
    }

    @Test fun the_upgrade_adds_the_v8_indices() {
        // v8's indices are what broke this fixture, so pin that they arrive.
        writeV5(withCentroid = true)
        Db(ctx, dbName).use { it.readableDatabase }
        val d = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(dbName), null)
        val names = mutableListOf<String>()
        d.rawQuery("SELECT name FROM sqlite_master WHERE type='index'", null).use { c ->
            while (c.moveToNext()) names += c.getString(0)
        }
        d.close()
        for (ix in listOf("idx_face_person", "idx_label_label", "idx_score_decision")) {
            assertTrue("$ix missing after upgrade; have $names", ix in names)
        }
    }

    @Test fun the_upgrade_keeps_the_scored_corpus() {
        // Upgrades are additive: rebuilding 638 scores costs two minutes of
        // face detection, so a migration must never drop the table.
        writeV5(withCentroid = true)
        val db = Db(ctx, dbName)
        assertEquals(1, db.itemCount())
        assertTrue("the score row survived", db.decisionCounts().isNotEmpty())
        db.close()
    }
}
