package com.picfilter.spike.data

import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase

/**
 * Human judgements: the golden set, duplicate verdicts, and what they agree with.
 *
 * Extensions on [Db] rather than members of it: every call site is
 * unchanged, and Db keeps the schema and the migrations, which are the
 * part that has to be read as one piece.
 */

// ---------- golden set ----------

fun Db.putLabel(itemId: Long, label: String, note: String? = null) {
    val v = ContentValues().apply {
        put("item_id", itemId); put("label", label)
        put("at", System.currentTimeMillis()); put("note", note)
    }
    writableDatabase.insertWithOnConflict("label", null, v, SQLiteDatabase.CONFLICT_REPLACE)
}

fun Db.deleteLabel(itemId: Long) =
    writableDatabase.execSQL("DELETE FROM label WHERE item_id=?", arrayOf<Any>(itemId))

fun Db.labelCounts(): List<Pair<String, Int>> {
    val out = mutableListOf<Pair<String, Int>>()
    readableDatabase.rawQuery(
        "SELECT label, COUNT(*) FROM label GROUP BY label ORDER BY 2 DESC", null
    ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getInt(1) }
    return out
}

/** Golden-set queue: unlabelled items, stratified so every reason is covered. */
fun Db.goldenQueue(perReason: Int): List<ReviewRow> {
    val out = mutableListOf<ReviewRow>()
    val reasons = mutableListOf<String>()
    readableDatabase.rawQuery("SELECT DISTINCT reason FROM score", null).use { c ->
        while (c.moveToNext()) reasons += c.getString(0)
    }
    for (r in reasons) {
        readableDatabase.rawQuery(
            """SELECT i.id, i.uri, s.decision, s.reason, s.faces, s.matched, s.indet,
                      s.text_frac, s.dupe_group
               FROM score s JOIN item i ON i.id = s.item_id
               LEFT JOIN label l ON l.item_id = i.id
               WHERE s.reason = ? AND l.item_id IS NULL
               ORDER BY i.date_added DESC LIMIT ?""",
            arrayOf(r, perReason.toString())
        ).use { c ->
            while (c.moveToNext()) out += ReviewRow(
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                c.getInt(4), c.getInt(5), c.getInt(6), c.getFloat(7),
                if (c.isNull(8)) null else c.getLong(8))
        }
    }
    return out.shuffled()
}

/** Dupe groups needing a verdict, as lists of member uris. */
fun Db.dupeGroups(): List<Pair<Long, List<Pair<Long, String>>>> {
    val groups = LinkedHashMap<Long, MutableList<Pair<Long, String>>>()
    readableDatabase.rawQuery(
        """SELECT s.dupe_group, i.id, i.uri FROM score s
           JOIN item i ON i.id = s.item_id
           LEFT JOIN dupe_verdict v ON v.group_id = s.dupe_group
           WHERE s.dupe_group IS NOT NULL AND v.group_id IS NULL
           ORDER BY s.dupe_group""", null
    ).use { c ->
        while (c.moveToNext())
            groups.getOrPut(c.getLong(0)) { mutableListOf() } += c.getLong(1) to c.getString(2)
    }
    return groups.entries.map { it.key to it.value }.filter { it.second.size >= 2 }
}

fun Db.putDupeVerdict(groupId: Long, verdict: String) {
    val v = ContentValues().apply {
        put("group_id", groupId); put("verdict", verdict); put("at", System.currentTimeMillis())
    }
    writableDatabase.insertWithOnConflict("dupe_verdict", null, v, SQLiteDatabase.CONFLICT_REPLACE)
}

fun Db.dupeVerdictCounts(): List<Pair<String, Int>> {
    val out = mutableListOf<Pair<String, Int>>()
    readableDatabase.rawQuery(
        "SELECT verdict, COUNT(*) FROM dupe_verdict GROUP BY verdict", null
    ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getInt(1) }
    return out
}

/** Agreement between the scorer's decision and the human label. */
fun Db.confusion(): List<Triple<String, String, Int>> {
    val out = mutableListOf<Triple<String, String, Int>>()
    readableDatabase.rawQuery(
        """SELECT s.reason, l.label, COUNT(*) FROM label l
           JOIN score s ON s.item_id = l.item_id
           GROUP BY s.reason, l.label ORDER BY s.reason, 3 DESC""", null
    ).use { c -> while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1), c.getInt(2)) }
    return out
}


fun Db.allLabels(): List<LabelRow> {
    val out = mutableListOf<LabelRow>()
    readableDatabase.rawQuery(
        """SELECT l.item_id, l.label, i.name, s.reason, s.faces, s.matched, s.indet
           FROM label l
           LEFT JOIN item i ON i.id = l.item_id
           LEFT JOIN score s ON s.item_id = l.item_id
           ORDER BY l.at""", null
    ).use { c ->
        while (c.moveToNext()) out += LabelRow(
            c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
            c.getInt(4), c.getInt(5), c.getInt(6))
    }
    return out
}

fun Db.clearLabels(): Int {
    val n = readableDatabase.rawQuery("SELECT COUNT(*) FROM label", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    writableDatabase.execSQL("DELETE FROM label")
    writableDatabase.execSQL("DELETE FROM dupe_verdict")
    return n
}
