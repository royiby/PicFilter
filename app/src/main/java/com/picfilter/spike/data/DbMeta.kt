package com.picfilter.spike.data

import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase

/**
 * Schema-level bookkeeping: the revision a score was judged against, and the two consent flags.
 *
 * Extensions on [Db] rather than members of it: every call site is
 * unchanged, and Db keeps the schema and the migrations, which are the
 * part that has to be read as one piece.
 */

// ---------- centroid revision ----------

/**
 * Bumped whenever the thing a score is compared against changes: a new
 * centroid, a wiped enrolment, a refitted threshold. [unscoredItems] treats
 * any score below the current revision as needing recomputation, so
 * enrolling a person silently queues a rescore instead of leaving every
 * item judged against nobody.
 */
fun Db.centroidRev(): Long =
    readableDatabase.rawQuery("SELECT v FROM meta WHERE k='centroid_rev'", null)
        .use { if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L }

fun Db.bumpCentroidRev(): Long {
    val next = centroidRev() + 1
    writableDatabase.execSQL(
        "INSERT OR REPLACE INTO meta(k, v) VALUES('centroid_rev', ?)",
        arrayOf<Any>(next.toString())
    )
    return next
}

// ---------- auto-save intent ----------

/**
 * Whether the user wants auto-save on, as distinct from whether it is
 * currently scheduled.
 *
 * This lives in the database rather than in SharedPreferences on purpose.
 * WorkManager keeps its schedule in `no_backup/androidx.work.workdb`, which
 * is private to the applicationId and comes across in no backup, so a
 * restored database used to arrive with every person and label intact and
 * nothing scheduled -- auto-save reading "off" on an install that looked
 * complete. The database is the thing that gets backed up and restored, so
 * the intent travels with it and [Watcher.healSchedule] can act on it.
 *
 * Null means no one has ever said: an install that predates this key, or a
 * genuinely new one. The caller decides what that means, because only it
 * can see whether work is already scheduled.
 */
fun Db.autoSaveWanted(): Boolean? =
    readableDatabase.rawQuery("SELECT v FROM meta WHERE k='autosave_wanted'", null)
        .use { if (it.moveToFirst()) it.getString(0) == "1" else null }

fun Db.setAutoSaveWanted(on: Boolean) {
    writableDatabase.execSQL(
        "INSERT OR REPLACE INTO meta(k, v) VALUES('autosave_wanted', ?)",
        arrayOf<Any>(if (on) "1" else "0")
    )
}

// ---------- crash reports ----------

/**
 * Whether the user has agreed to send crash reports.
 *
 * Null until asked, and null is NOT yes. The home screen's intro says
 * "Nothing leaves this phone"; a reporter that defaults to on would make
 * that sentence false in the app's own words.
 */
fun Db.crashReportsWanted(): Boolean? =
    readableDatabase.rawQuery("SELECT v FROM meta WHERE k='crash_reports'", null)
        .use { if (it.moveToFirst()) it.getString(0) == "1" else null }

fun Db.setCrashReportsWanted(on: Boolean) {
    writableDatabase.execSQL(
        "INSERT OR REPLACE INTO meta(k, v) VALUES('crash_reports', ?)",
        arrayOf<Any>(if (on) "1" else "0")
    )
}

/** Items whose score predates the current centroid revision. */
fun Db.staleScoreCount(): Int =
    readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM score WHERE centroid_rev < ?",
        arrayOf(centroidRev().toString())
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
