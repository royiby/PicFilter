package com.picfilter.spike.data

import android.content.ContentValues
import androidx.core.database.sqlite.transaction
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase

/**
 * Rescuing a photo into the backed-up folder, and keeping track of it.
 *
 * Extensions on [Db] rather than members of it: every call site is
 * unchanged, and Db keeps the schema and the migrations, which are the
 * part that has to be read as one piece.
 */

// ---------- promotion (rescue to a backed-up folder) ----------


/** KEEP items not yet promoted. Never returns DISCARD_CANDIDATE. */
fun Db.itemsForPromotion(decisions: List<String>): List<PromoRow> {
    val marks = decisions.joinToString(",") { "?" }
    val out = mutableListOf<PromoRow>()
    readableDatabase.rawQuery(
        """SELECT i.id, i.uri, i.name, i.size_bytes, i.date_added, s.reason
           FROM item i JOIN score s ON s.item_id = i.id
           LEFT JOIN promotion pr ON pr.item_id = i.id
           WHERE s.decision IN ($marks) AND pr.item_id IS NULL
           ORDER BY i.date_added""",
        decisions.toTypedArray()
    ).use { c ->
        while (c.moveToNext()) out += PromoRow(
            c.getLong(0), c.getString(1), c.getString(2),
            c.getLong(3), c.getLong(4), c.getString(5))
    }
    return out
}

fun Db.recordPromotion(itemId: Long, destUri: String, destName: String, bytes: Long) {
    val v = ContentValues().apply {
        put("item_id", itemId); put("dest_uri", destUri); put("dest_name", destName)
        put("bytes", bytes); put("at", System.currentTimeMillis())
    }
    writableDatabase.insertWithOnConflict("promotion", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    writableDatabase.execSQL(
        "INSERT INTO action_log(item_id, action, at, note) VALUES(?,?,?,?)",
        arrayOf<Any>(itemId, "PROMOTE", System.currentTimeMillis(), destName))
}

/**
 * Human verdicts bucketed by the photo's best face similarity. Every one of
 * these is a labelled point in the band where MATCH_THRESHOLD is contested,
 * so this says directly whether 0.55 sits in the right place.
 */
fun Db.calibration(reasonFilter: String? = null): List<Triple<String, Int, Int>> {
    val out = mutableListOf<Triple<String, Int, Int>>()
    val edges = listOf(0.0f, 0.35f, 0.40f, 0.45f, 0.475f, 0.50f, 0.52f, 0.54f, 1.01f)
    for (i in 0 until edges.size - 1) {
        val lo = edges[i]; val hi = edges[i + 1]
        var keep = 0; var total = 0
        val extra = if (reasonFilter != null) " AND s.reason = ?" else ""
        readableDatabase.rawQuery(
            """SELECT l.label, (SELECT MAX(f.similarity) FROM face f WHERE f.item_id=l.item_id) sim
               FROM label l JOIN score s ON s.item_id = l.item_id
               WHERE l.label IN ('KEEP','DISCARD')$extra""",
            if (reasonFilter != null) arrayOf(reasonFilter) else null
        ).use { c ->
            while (c.moveToNext()) {
                if (c.isNull(1)) continue
                val sim = c.getFloat(1)
                if (sim >= lo && sim < hi) {
                    total++; if (c.getString(0) == "KEEP") keep++
                }
            }
        }
        if (total > 0) out += Triple("%.3f..%.3f".format(lo, hi), keep, total)
    }
    return out
}

/** Promoted items awaiting human confirmation that they really are family. */
fun Db.promotedForReview(limit: Int = -1): List<RescueRow> {
    val out = mutableListOf<RescueRow>()
    readableDatabase.rawQuery(
        """SELECT i.id, i.uri, s.reason, s.faces, s.indet,
                  (SELECT p.name FROM face f LEFT JOIN person p ON p.id=f.person_id
                   WHERE f.item_id=i.id AND f.state='MATCH'
                   ORDER BY f.similarity DESC LIMIT 1),
                  (SELECT MAX(f.similarity) FROM face f WHERE f.item_id=i.id AND f.state='MATCH')
           FROM promotion pr
           JOIN item i ON i.id = pr.item_id
           JOIN score s ON s.item_id = i.id
           LEFT JOIN label l ON l.item_id = i.id
           WHERE l.item_id IS NULL
           ORDER BY 7 ASC LIMIT ?""", arrayOf(limit.toString())
    ).use { c ->
        while (c.moveToNext()) out += RescueRow(
            c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4),
            c.getString(5), if (c.isNull(6)) null else c.getFloat(6))
    }
    return out
}

fun Db.promotionNameFor(itemId: Long): String? =
    readableDatabase.rawQuery(
        "SELECT dest_name FROM promotion WHERE item_id=?", arrayOf(itemId.toString())
    ).use { if (it.moveToFirst()) it.getString(0) else null }

fun Db.deletePromotion(itemId: Long) {
    writableDatabase.execSQL("DELETE FROM promotion WHERE item_id=?", arrayOf<Any>(itemId))
    writableDatabase.execSQL(
        "INSERT INTO action_log(item_id, action, at, note) VALUES(?,?,?,?)",
        arrayOf<Any>(itemId, "UNPROMOTE", System.currentTimeMillis(), "human said not family"))
}

/**
 * Precision ABOVE the match threshold: of the photos the scorer called KEEP
 * and a human then judged, how many were actually family — banded by match
 * strength. The mirror of calibration(), which only ever looked below 0.55.
 */
fun Db.precisionByBand(): List<Triple<String, Int, Int>> {
    val out = mutableListOf<Triple<String, Int, Int>>()
    val edges = listOf(0.55f, 0.58f, 0.61f, 0.65f, 0.70f, 0.80f, 1.01f)
    val rows = mutableListOf<Pair<String, Float>>()
    readableDatabase.rawQuery(
        """SELECT l.label,
                  (SELECT MAX(f.similarity) FROM face f
                   WHERE f.item_id=l.item_id AND f.state='MATCH') sim
           FROM label l JOIN score s ON s.item_id = l.item_id
           WHERE s.decision='KEEP' AND l.label IN ('KEEP','DISCARD')""", null
    ).use { c ->
        while (c.moveToNext()) if (!c.isNull(1)) rows += c.getString(0) to c.getFloat(1)
    }
    for (i in 0 until edges.size - 1) {
        val lo = edges[i]; val hi = edges[i + 1]
        val band = rows.filter { it.second >= lo && it.second < hi }
        if (band.isNotEmpty())
            out += Triple("%.2f..%.2f".format(lo, hi), band.count { it.first == "KEEP" }, band.size)
    }
    return out
}

/** Which enrolled person is responsible for the false matches. */
fun Db.precisionByPerson(): List<Triple<String, Int, Int>> {
    val out = mutableListOf<Triple<String, Int, Int>>()
    readableDatabase.rawQuery(
        """SELECT p.name,
                  SUM(CASE WHEN l.label='KEEP' THEN 1 ELSE 0 END),
                  COUNT(*)
           FROM label l
           JOIN score s ON s.item_id = l.item_id
           JOIN face f ON f.item_id = l.item_id AND f.state='MATCH'
           JOIN person p ON p.id = f.person_id
           WHERE s.decision='KEEP' AND l.label IN ('KEEP','DISCARD')
           GROUP BY p.name ORDER BY 3 DESC""", null
    ).use { c -> while (c.moveToNext()) out += Triple(c.getString(0), c.getInt(1), c.getInt(2)) }
    return out
}

/** personId -> fitted threshold (absent means use the global default). */
fun Db.personThresholds(): Map<Long, Float> {
    val out = HashMap<Long, Float>()
    readableDatabase.rawQuery(
        "SELECT id, threshold FROM person WHERE threshold IS NOT NULL", null
    ).use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getFloat(1) }
    return out
}

/**
 * Only a bar that actually MOVES invalidates a score.
 *
 * The bump restales every score in the database -- 641 of them here, which
 * is three scoring passes and about two minutes of face detection. Writing
 * the same number back does not move the boundary, so it must not cost
 * that. A fitter that re-confirms its thresholds on a schedule would
 * otherwise rescore the whole library every time it agreed with itself.
 *
 * This can only compare against what is STORED. A person on the global
 * default has no row value, and whether an explicit threshold equal to
 * that default is a change is a question about the default -- which lives
 * in the engine, not here. [Fitter] holds that half.
 */
fun Db.setPersonThreshold(personId: Long, t: Float) {
    val current = readableDatabase.rawQuery(
        "SELECT threshold FROM person WHERE id=?", arrayOf(personId.toString())
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getFloat(0) else null }
    if (current == t) return
    writableDatabase.execSQL("UPDATE person SET threshold=? WHERE id=?",
        arrayOf<Any>(t, personId))
    bumpCentroidRev()
}

/** (personId, name, similarity, wasActuallyFamily) for every human-judged match. */
fun Db.labelledMatches(): List<Quad> {
    val out = mutableListOf<Quad>()
    readableDatabase.rawQuery(
        """SELECT p.id, p.name, f.similarity, l.label
           FROM label l
           JOIN score s ON s.item_id = l.item_id
           JOIN face f ON f.item_id = l.item_id AND f.state='MATCH'
           JOIN person p ON p.id = f.person_id
           WHERE s.decision='KEEP' AND l.label IN ('KEEP','DISCARD')
             AND f.similarity IS NOT NULL""", null
    ).use { c ->
        while (c.moveToNext()) out += Quad(
            c.getLong(0), c.getString(1), c.getFloat(2), c.getString(3) == "KEEP")
    }
    return out
}

data class Quad(val personId: Long, val name: String, val sim: Float, val correct: Boolean)

/**
 * Human verdicts on faces that did NOT match -- the other half of a
 * threshold.
 *
 * [labelledMatches] can only see faces above the bar, so a fit computed
 * from it alone never observes a false negative and can only ever ratchet
 * the bar upward. Deciding where a boundary belongs needs labelled points
 * on both sides of it, and these are the ones below.
 *
 * Attribution is the same rule [vouchedNearMisses] uses, and for the same
 * reason -- a verdict is about a photo, not about a face. The item was
 * reviewed, nothing in it matched anyone (otherwise the verdict is
 * explained by that match), and exactly ONE face came close to exactly one
 * person. Then that face is that person, and the verdict is about them.
 *
 * The polarity is what differs. [vouchedNearMisses] only wants KEEPs,
 * because only a keep can widen a centroid and a discard teaches it
 * nothing. A threshold wants both: a keep here is a face that SHOULD have
 * matched, and a discard is a face that correctly did not.
 *
 * Two biases the caller has to carry, because this sample is not random:
 * the rescue queue is ordered by near-miss similarity, so reviewed items
 * skew toward the top of the band; and the single-near-face clause drops
 * crowded photos, which are not like the ones it keeps.
 */
fun Db.labelledNearMisses(minSim: Float, limit: Int = 500): List<Quad> {
    val out = mutableListOf<Quad>()
    readableDatabase.rawQuery(
        """SELECT f.person_id, p.name, f.similarity, l.label
           FROM label l
           JOIN item i ON i.id = l.item_id
           JOIN face f ON f.item_id = l.item_id
           JOIN person p ON p.id = f.person_id
           WHERE l.label IN ('KEEP','DISCARD')
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
        while (c.moveToNext()) out += Quad(
            c.getLong(0), c.getString(1), c.getFloat(2), c.getString(3) == "KEEP")
    }
    return out
}

fun Db.promotedDests(): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    readableDatabase.rawQuery("SELECT dest_uri, dest_name FROM promotion", null).use { c ->
        while (c.moveToNext()) out += c.getString(0) to c.getString(1)
    }
    return out
}

/** Collisions: distinct source items that were written to the same filename. */
fun Db.promotionCollisions(): Triple<Int, Int, List<String>> {
    var rows = 0; var distinct = 0
    val dupes = mutableListOf<String>()
    readableDatabase.rawQuery("SELECT COUNT(*), COUNT(DISTINCT dest_name) FROM promotion", null)
        .use { if (it.moveToFirst()) { rows = it.getInt(0); distinct = it.getInt(1) } }
    readableDatabase.rawQuery(
        "SELECT dest_name, COUNT(*) n FROM promotion GROUP BY dest_name HAVING n > 1 ORDER BY n DESC",
        null
    ).use { c -> while (c.moveToNext()) dupes += "${c.getString(0)} x${c.getInt(1)}" }
    return Triple(rows, distinct, dupes)
}

/** The source item a rescued file came from; dest names are the source names. */
fun Db.itemIdByName(name: String): Long? =
    readableDatabase.rawQuery(
        "SELECT id FROM item WHERE name = ? LIMIT 1", arrayOf(name)
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

fun Db.markSynced(itemIds: List<Long>): Int {
    if (itemIds.isEmpty()) return 0
    val now = System.currentTimeMillis()
    var n = 0
    writableDatabase.transaction {
        for (id in itemIds) {
            execSQL(
                "UPDATE promotion SET synced_at=? WHERE item_id=? AND synced_at IS NULL",
                arrayOf<Any>(now, id))
            n++
        }
    }
    return n
}

/** Rescued files we have already recorded as safely in the cloud. */
fun Db.syncedDestNames(): Set<String> {
    val out = HashSet<String>()
    readableDatabase.rawQuery(
        "SELECT dest_name FROM promotion WHERE synced_at IS NOT NULL AND dest_name IS NOT NULL",
        null
    ).use { c -> while (c.moveToNext()) out += c.getString(0) }
    return out
}

fun Db.syncedCount(): Int =
    readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM promotion WHERE synced_at IS NOT NULL", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

fun Db.promotionCount(): Int =
    readableDatabase.rawQuery("SELECT COUNT(*) FROM promotion", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

fun Db.unscoredCount(): Int =
    readableDatabase.rawQuery(
        """SELECT COUNT(*) FROM item i LEFT JOIN score s ON s.item_id = i.id
           WHERE s.item_id IS NULL OR s.centroid_rev < ?""",
        arrayOf(centroidRev().toString())
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

fun Db.promotionSummary(): String {
    readableDatabase.rawQuery(
        "SELECT COUNT(*), COALESCE(SUM(bytes),0) FROM promotion", null
    ).use { c ->
        return if (c.moveToFirst())
            "${c.getInt(0)} items, ${"%.1f".format(c.getLong(1) / 1048576.0)} MB"
        else "none"
    }
}

/** Test helper: make the N most recent items unscored again. */
fun Db.unscoreRecent(n: Int): Int {
    val ids = mutableListOf<Long>()
    readableDatabase.rawQuery(
        "SELECT item_id FROM score ORDER BY scored_at DESC LIMIT ?", arrayOf(n.toString())
    ).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
    for (id in ids) {
        writableDatabase.execSQL("DELETE FROM score WHERE item_id=?", arrayOf<Any>(id))
        writableDatabase.execSQL("DELETE FROM face WHERE item_id=?", arrayOf<Any>(id))
    }
    return ids.size
}

fun Db.resetScores() {
    writableDatabase.execSQL("DELETE FROM score")
    writableDatabase.execSQL("DELETE FROM face")
}
