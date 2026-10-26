package com.picfilter.spike.data

import android.content.ContentValues
import androidx.core.database.sqlite.transaction
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase

/**
 * The library as rows: what has been seen, and its perceptual hash.
 *
 * Extensions on [Db] rather than members of it: every call site is
 * unchanged, and Db keeps the schema and the migrations, which are the
 * part that has to be read as one piece.
 */

// ---------- items ----------

fun Db.upsertItem(
    id: Long, uri: String, name: String?, relPath: String?, bucket: String?,
    sizeBytes: Long, dateAdded: Long, width: Int, height: Int,
) {
    val v = ContentValues().apply {
        put("id", id); put("uri", uri); put("name", name); put("rel_path", relPath)
        put("bucket", bucket); put("size_bytes", sizeBytes); put("date_added", dateAdded)
        put("width", width); put("height", height); put("scanned_at", System.currentTimeMillis())
    }
    writableDatabase.insertWithOnConflict("item", null, v, SQLiteDatabase.CONFLICT_REPLACE)
}

fun Db.itemCount(): Int =
    readableDatabase.rawQuery("SELECT COUNT(*) FROM item", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

fun Db.unscoredItems(limit: Int): List<Triple<Long, String, Int>> {
    val out = mutableListOf<Triple<Long, String, Int>>()
    readableDatabase.rawQuery(
        """SELECT i.id, i.uri, i.width FROM item i
           LEFT JOIN score s ON s.item_id = i.id
           WHERE s.item_id IS NULL OR s.centroid_rev < ?
           ORDER BY i.date_added DESC LIMIT ?""",
        arrayOf(centroidRev().toString(), limit.toString())
    ).use { c -> while (c.moveToNext()) out += Triple(c.getLong(0), c.getString(1), c.getInt(2)) }
    return out
}

/**
 * Run [block] as one transaction.
 *
 * SQLite commits every statement on its own otherwise, which means an
 * fsync each. The write-heavy paths do hundreds in a row -- a scoring pass
 * writes a face row per detected face, clustering persisted 435 of them in
 * one go -- so batching is the difference between one flush and hundreds.
 */
fun <T> Db.inTransaction(block: () -> T): T {
    var result: T? = null
    writableDatabase.transaction { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

fun Db.setDhash(itemId: Long, h: Long) =
    writableDatabase.execSQL("UPDATE item SET dhash=? WHERE id=?", arrayOf<Any>(h, itemId))

fun Db.allDhashes(): List<Pair<Long, Long>> {
    val out = mutableListOf<Pair<Long, Long>>()
    readableDatabase.rawQuery("SELECT id, dhash FROM item WHERE dhash IS NOT NULL", null).use { c ->
        while (c.moveToNext()) out += c.getLong(0) to c.getLong(1)
    }
    return out
}
