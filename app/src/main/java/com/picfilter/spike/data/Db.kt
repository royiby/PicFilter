package com.picfilter.spike.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.SQLException
import androidx.core.database.sqlite.transaction

/**
 * Phase 1 store. Raw SQLite + typed DAO (Room swap is a later cleanup).
 *
 * [name] exists so tests can work on a throwaway database instead of the real
 * one -- an instrumented test runs as this package and would otherwise scribble
 * on the user's own scored corpus.
 */
class Db(ctx: Context, name: String = DEFAULT_NAME) : SQLiteOpenHelper(ctx, name, null, 8) {

    override fun onCreate(d: SQLiteDatabase) {
        d.execSQL("""CREATE TABLE person(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT UNIQUE NOT NULL,
            created_at INTEGER NOT NULL)""")
        d.execSQL("""CREATE TABLE centroid(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            person_id INTEGER NOT NULL,
            vec BLOB NOT NULL,
            face_count INTEGER NOT NULL,
            created_at INTEGER NOT NULL)""")
        d.execSQL("""CREATE TABLE item(
            id INTEGER PRIMARY KEY,
            uri TEXT NOT NULL, name TEXT, rel_path TEXT, bucket TEXT,
            size_bytes INTEGER, date_added INTEGER,
            width INTEGER, height INTEGER, dhash INTEGER,
            scanned_at INTEGER NOT NULL)""")
        d.execSQL("""CREATE TABLE face(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            item_id INTEGER NOT NULL,
            orig_px INTEGER, sharpness REAL, yaw REAL, roll REAL,
            state TEXT NOT NULL, person_id INTEGER, similarity REAL)""")
        d.execSQL("""CREATE TABLE score(
            item_id INTEGER PRIMARY KEY,
            faces INTEGER, matched INTEGER, indet INTEGER,
            text_frac REAL, text_blocks INTEGER, dupe_group INTEGER,
            decision TEXT NOT NULL, reason TEXT, confidence REAL,
            scored_at INTEGER NOT NULL)""")
        d.execSQL("""CREATE TABLE action_log(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            item_id INTEGER NOT NULL, action TEXT NOT NULL,
            at INTEGER NOT NULL, note TEXT)""")
        createV2(d)
        createV3(d)
        createV4(d)
        createV5(d)
        createV6(d)
        createV7(d)
        d.execSQL("CREATE INDEX idx_face_item ON face(item_id)")
        d.execSQL("CREATE INDEX idx_item_bucket ON item(bucket)")
        createV8(d)
    }

    private fun createV2(d: SQLiteDatabase) {
        d.execSQL("""CREATE TABLE IF NOT EXISTS label(
            item_id INTEGER PRIMARY KEY,
            label TEXT NOT NULL,
            at INTEGER NOT NULL,
            note TEXT)""")
        d.execSQL("""CREATE TABLE IF NOT EXISTS dupe_verdict(
            group_id INTEGER PRIMARY KEY,
            verdict TEXT NOT NULL,
            at INTEGER NOT NULL)""")
    }

    private fun createV3(d: SQLiteDatabase) {
        d.execSQL("""CREATE TABLE IF NOT EXISTS cluster(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            size INTEGER NOT NULL,
            centroid BLOB NOT NULL,
            status TEXT,
            person_id INTEGER,
            created_at INTEGER NOT NULL)""")
        d.execSQL("""CREATE TABLE IF NOT EXISTS cluster_face(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            cluster_id INTEGER NOT NULL,
            item_id INTEGER,
            thumb_path TEXT,
            orig_px INTEGER,
            vec BLOB NOT NULL)""")
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_cf_cluster ON cluster_face(cluster_id)")
    }

    private fun createV4(d: SQLiteDatabase) {
        d.execSQL("""CREATE TABLE IF NOT EXISTS promotion(
            item_id INTEGER PRIMARY KEY,
            dest_uri TEXT, dest_name TEXT, bytes INTEGER,
            at INTEGER NOT NULL)""")
    }

    private fun createV5(d: SQLiteDatabase) {
        // Already there on a database that has been upgraded once before.
        try { d.execSQL("ALTER TABLE person ADD COLUMN threshold REAL") }
        catch (e: SQLException) { }
    }

    private fun createV6(d: SQLiteDatabase) {
        d.execSQL("""CREATE TABLE IF NOT EXISTS meta(
            k TEXT PRIMARY KEY,
            v TEXT NOT NULL)""")
        // A score is only valid for the centroids and thresholds it was computed
        // against. Rows written before this column existed default to 0, which is
        // below any live revision, so enrolling anyone marks the whole corpus stale.
        try {
            d.execSQL("ALTER TABLE score ADD COLUMN centroid_rev INTEGER NOT NULL DEFAULT 0")
        } catch (e: SQLException) { }
        // On an existing database the scores predate this column and the people
        // may have been enrolled after them, which is exactly the bug this
        // column exists to catch. Start the revision at 1 so those rows read as
        // stale; a fresh database has nobody enrolled and stays at 0.
        val enrolled = try {
            d.rawQuery("SELECT COUNT(*) FROM centroid", null)
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: SQLException) { 0 }
        if (enrolled > 0) {
            d.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES('centroid_rev', '1')")
        }
    }

    private fun createV7(d: SQLiteDatabase) {
        // When a rescued copy reached the cloud. Once set, the local file in
        // the rescued copy has done its job and can be removed -- and must NOT
        // be copied again just because it is no longer there.
        try { d.execSQL("ALTER TABLE promotion ADD COLUMN synced_at INTEGER") }
        catch (e: SQLException) { }
    }

    /**
     * The indices the hot queries were missing.
     *
     * EXPLAIN QUERY PLAN on the real database showed full table SCANs for the
     * two columns the app filters on most: face.person_id, which every
     * per-person screen and every avatar rebuild uses, and label.label, which
     * both home counters read on every launch. At 1,846 face rows a scan is
     * free, which is exactly why this went unnoticed; the cost arrives with
     * the library, not with the code.
     *
     * score.decision earns one for the same reason -- queueSizes() filters on
     * it four times per home screen.
     */
    private fun createV8(d: SQLiteDatabase) {
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_face_person ON face(person_id)")
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_label_label ON label(label)")
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_score_decision ON score(decision)")
    }

    override fun onUpgrade(d: SQLiteDatabase, old: Int, new: Int) {
        // Additive only — the scored corpus is expensive to rebuild.
        if (old < 2) createV2(d)
        if (old < 3) createV3(d)
        if (old < 4) createV4(d)
        if (old < 5) createV5(d)
        if (old < 6) createV6(d)
        if (old < 7) createV7(d)
        if (old < 8) createV8(d)
    }

    companion object {
        const val DEFAULT_NAME = "picfilter.db"

    }
}
