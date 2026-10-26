package com.picfilter.spike.data

import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase

/**
 * People, their centroids, and the enrolment they come from.
 *
 * Extensions on [Db] rather than members of it: every call site is
 * unchanged, and Db keeps the schema and the migrations, which are the
 * part that has to be read as one piece.
 */

// ---------- people / centroids ----------

fun Db.upsertPerson(name: String): Long {
    writableDatabase.execSQL(
        "INSERT OR IGNORE INTO person(name, created_at) VALUES(?,?)",
        arrayOf<Any>(name, System.currentTimeMillis())
    )
    readableDatabase.rawQuery("SELECT id FROM person WHERE name=?", arrayOf(name)).use {
        return if (it.moveToFirst()) it.getLong(0) else -1L
    }
}

/**
 * [bumpRev] false when EXTENDING a person rather than enrolling one.
 * Matching takes the max over a person's set, so an added vector can only
 * turn a miss into a match, never the reverse. Invalidating every score for
 * that would mean re-reading the whole library to re-confirm matches that
 * cannot change; [invalidateUnmatched] requeues only what could differ.
 */
fun Db.addCentroid(personId: Long, vec: FloatArray, faceCount: Int, bumpRev: Boolean = true) {
    val v = ContentValues().apply {
        put("person_id", personId); put("vec", floatsToBytes(vec))
        put("face_count", faceCount); put("created_at", System.currentTimeMillis())
    }
    writableDatabase.insert("centroid", null, v)
    if (bumpRev) bumpCentroidRev()
}

fun Db.centroidCount(personId: Long): Int =
    readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM centroid WHERE person_id=?", arrayOf(personId.toString())
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

/**
 * Requeue only what an added centroid could change: an item that already
 * matched cannot stop matching, and one with no faces can never start.
 */
fun Db.invalidateUnmatched(): Int {
    writableDatabase.execSQL(
        "UPDATE score SET centroid_rev = -1 WHERE matched = 0 AND faces > 0")
    return readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM score WHERE centroid_rev = -1", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}

/**
 * Faces a person vouched for that the matcher had rejected.
 *
 * This is the one signal that can widen a centroid. Growing from confident
 * matches cannot: those sit inside the region already covered, so it
 * recovers nothing (measured -- see TESTING.md). A face the matcher scored
 * BELOW its threshold, which a human then said is family, lies outside that
 * region, which is precisely why it is worth learning.
 *
 * Attribution has to be unambiguous, because a keep is about the photo and
 * not about a face. So: the item was kept, nothing in it matched anyone
 * (otherwise the keep is explained by that match), and exactly ONE face in
 * it came close to exactly one person. Then that face is that person.
 */
fun Db.vouchedNearMisses(minSim: Float, limit: Int): List<Triple<Long, Long, String>> {
    val out = mutableListOf<Triple<Long, Long, String>>()
    readableDatabase.rawQuery(
        """SELECT f.person_id, i.id, i.uri
           FROM label l
           JOIN item i ON i.id = l.item_id
           JOIN face f ON f.item_id = l.item_id
           WHERE l.label = 'KEEP'
             AND f.person_id IS NOT NULL
             AND f.state != 'MATCH'
             AND f.similarity >= ?
             AND NOT EXISTS (
                 SELECT 1 FROM face m WHERE m.item_id = l.item_id AND m.state = 'MATCH')
             AND (SELECT COUNT(*) FROM face n
                  WHERE n.item_id = l.item_id AND n.person_id IS NOT NULL
                    AND n.similarity >= ?) = 1
           ORDER BY f.similarity DESC LIMIT ?""",
        arrayOf(minSim.toString(), minSim.toString(), limit.toString())
    ).use { c ->
        while (c.moveToNext()) out += Triple(c.getLong(0), c.getLong(1), c.getString(2))
    }
    return out
}

/** All centroids, keyed by person. Matching takes the max similarity over a person's set. */
fun Db.centroidsByPerson(): Map<Long, List<FloatArray>> {
    val out = HashMap<Long, MutableList<FloatArray>>()
    readableDatabase.rawQuery("SELECT person_id, vec FROM centroid", null).use { c ->
        while (c.moveToNext()) {
            out.getOrPut(c.getLong(0)) { mutableListOf() } += bytesToFloats(c.getBlob(1))
        }
    }
    return out
}

fun Db.personNames(): Map<Long, String> {
    val out = HashMap<Long, String>()
    readableDatabase.rawQuery("SELECT id, name FROM person", null).use { c ->
        while (c.moveToNext()) out[c.getLong(0)] = c.getString(1)
    }
    return out
}

fun Db.clearEnrollment() {
    writableDatabase.execSQL("DELETE FROM centroid")
    writableDatabase.execSQL("DELETE FROM person")
    bumpCentroidRev()
}
