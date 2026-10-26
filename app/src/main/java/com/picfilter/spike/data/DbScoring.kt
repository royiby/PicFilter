package com.picfilter.spike.data

import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase

/**
 * Faces, scores, and the counts that describe a corpus.
 *
 * Extensions on [Db] rather than members of it: every call site is
 * unchanged, and Db keeps the schema and the migrations, which are the
 * part that has to be read as one piece.
 */

// ---------- faces / scores ----------

/** Rescoring re-detects, so the previous pass's rows have to go first. */
fun Db.faceCountFor(itemId: Long): Int =
    readableDatabase.rawQuery("SELECT COUNT(*) FROM face WHERE item_id=?",
        arrayOf(itemId.toString())
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

fun Db.clearFaces(itemId: Long) =
    writableDatabase.execSQL("DELETE FROM face WHERE item_id=?", arrayOf<Any>(itemId))

fun Db.addFace(
    itemId: Long, origPx: Int, sharpness: Double, yaw: Float, roll: Float,
    state: String, personId: Long?, similarity: Float?,
) {
    val v = ContentValues().apply {
        put("item_id", itemId); put("orig_px", origPx); put("sharpness", sharpness)
        put("yaw", yaw); put("roll", roll); put("state", state)
        if (personId != null) put("person_id", personId) else putNull("person_id")
        if (similarity != null) put("similarity", similarity) else putNull("similarity")
    }
    writableDatabase.insert("face", null, v)
}

fun Db.putScore(
    itemId: Long, faces: Int, matched: Int, indet: Int,
    textFrac: Float, textBlocks: Int, decision: String, reason: String, confidence: Float,
) {
    val v = ContentValues().apply {
        put("item_id", itemId); put("faces", faces); put("matched", matched); put("indet", indet)
        put("text_frac", textFrac); put("text_blocks", textBlocks)
        put("decision", decision); put("reason", reason); put("confidence", confidence)
        put("scored_at", System.currentTimeMillis())
        put("centroid_rev", centroidRev())
    }
    writableDatabase.insertWithOnConflict("score", null, v, SQLiteDatabase.CONFLICT_REPLACE)
}

fun Db.setDupeGroup(itemId: Long, group: Long) =
    writableDatabase.execSQL("UPDATE score SET dupe_group=? WHERE item_id=?", arrayOf<Any>(group, itemId))

fun Db.decisionCounts(): List<Pair<String, Int>> {
    val out = mutableListOf<Pair<String, Int>>()
    readableDatabase.rawQuery(
        "SELECT decision, COUNT(*) FROM score GROUP BY decision ORDER BY 2 DESC", null
    ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getInt(1) }
    return out
}

fun Db.reasonCounts(): List<Pair<String, Int>> {
    val out = mutableListOf<Pair<String, Int>>()
    readableDatabase.rawQuery(
        "SELECT reason, COUNT(*) FROM score GROUP BY reason ORDER BY 2 DESC", null
    ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getInt(1) }
    return out
}

fun Db.faceStateCounts(): List<Pair<String, Int>> {
    val out = mutableListOf<Pair<String, Int>>()
    readableDatabase.rawQuery(
        "SELECT state, COUNT(*) FROM face GROUP BY state ORDER BY 2 DESC", null
    ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getInt(1) }
    return out
}

fun Db.matchesPerPerson(): List<Triple<String, Int, Double>> {
    val out = mutableListOf<Triple<String, Int, Double>>()
    readableDatabase.rawQuery(
        """SELECT p.name, COUNT(*), AVG(f.similarity) FROM face f
           JOIN person p ON p.id = f.person_id
           WHERE f.state='MATCH' GROUP BY p.name ORDER BY 2 DESC""", null
    ).use { c -> while (c.moveToNext()) out += Triple(c.getString(0), c.getInt(1), c.getDouble(2)) }
    return out
}

/** Raw per-face metrics for threshold sweeps — no re-detection needed. */
fun Db.facesForSweep(): List<FaceRow> {
    val out = mutableListOf<FaceRow>()
    readableDatabase.rawQuery(
        "SELECT item_id, orig_px, sharpness, yaw, roll FROM face", null
    ).use { c ->
        while (c.moveToNext()) out += FaceRow(
            c.getLong(0), c.getInt(1), c.getDouble(2), c.getFloat(3), c.getFloat(4))
    }
    return out
}

/** itemId -> (textFrac, textBlocks) */
fun Db.textSignals(): Map<Long, Pair<Float, Int>> {
    val out = HashMap<Long, Pair<Float, Int>>()
    readableDatabase.rawQuery("SELECT item_id, text_frac, text_blocks FROM score", null).use { c ->
        while (c.moveToNext()) out[c.getLong(0)] = c.getFloat(1) to c.getInt(2)
    }
    return out
}

fun Db.dupeGroupsAt(hamming: Int): Pair<Int, Int> {
    val all = allDhashes()
    val parent = HashMap<Long, Long>()
    fun find(x: Long): Long { var r = x; while (parent[r] != null && parent[r] != r) r = parent[r]!!; parent[r] = r; return r }
    for ((id, _) in all) parent[id] = id
    for (i in all.indices) for (j in i + 1 until all.size)
        if (java.lang.Long.bitCount(all[i].second xor all[j].second) <= hamming) {
            val ra = find(all[i].first); val rb = find(all[j].first); if (ra != rb) parent[ra] = rb
        }
    val groups = HashMap<Long, Int>()
    for ((id, _) in all) groups[find(id)] = (groups[find(id)] ?: 0) + 1
    val multi = groups.values.count { it >= 2 }
    val extra = groups.values.filter { it >= 2 }.sumOf { it - 1 }
    return multi to extra
}
