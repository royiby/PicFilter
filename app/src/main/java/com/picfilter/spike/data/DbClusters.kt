package com.picfilter.spike.data

import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase

/**
 * Onboarding's clusters, and the candidates offered from them.
 *
 * Extensions on [Db] rather than members of it: every call site is
 * unchanged, and Db keeps the schema and the migrations, which are the
 * part that has to be read as one piece.
 */

// ---------- onboarding clusters ----------

fun Db.clearClusters() {
    writableDatabase.execSQL("DELETE FROM cluster_face")
    writableDatabase.execSQL("DELETE FROM cluster")
}

fun Db.addCluster(size: Int, centroid: FloatArray): Long {
    val v = ContentValues().apply {
        put("size", size); put("centroid", floatsToBytes(centroid))
        put("created_at", System.currentTimeMillis())
    }
    return writableDatabase.insert("cluster", null, v)
}

fun Db.addClusterFace(clusterId: Long, itemId: Long?, thumb: String?, origPx: Int, vec: FloatArray) {
    val v = ContentValues().apply {
        put("cluster_id", clusterId)
        if (itemId != null) put("item_id", itemId) else putNull("item_id")
        put("thumb_path", thumb); put("orig_px", origPx); put("vec", floatsToBytes(vec))
    }
    writableDatabase.insert("cluster_face", null, v)
}


/**
 * Items worth a human look for rescue: REVIEW items that contain faces,
 * ordered by how close their best near-miss came to an enrolled person.
 * Most-likely-family first, so the user can stop when they stop seeing family.
 */
fun Db.rescueQueue(
    reasons: List<String> = listOf("INDETERMINATE_FACE", "FEW_FACES_NO_MATCH"),
    limit: Int = -1,
): List<RescueRow> {
    val marks = reasons.joinToString(",") { "?" }
    val out = mutableListOf<RescueRow>()
    readableDatabase.rawQuery(
        """SELECT i.id, i.uri, s.reason, s.faces, s.indet,
                  (SELECT p.name FROM face f LEFT JOIN person p ON p.id=f.person_id
                   WHERE f.item_id=i.id AND f.similarity IS NOT NULL
                   ORDER BY f.similarity DESC LIMIT 1),
                  (SELECT MAX(f.similarity) FROM face f WHERE f.item_id=i.id)
           FROM item i
           JOIN score s ON s.item_id = i.id
           LEFT JOIN label l ON l.item_id = i.id
           LEFT JOIN promotion pr ON pr.item_id = i.id
           WHERE s.decision='REVIEW'
             AND s.reason IN ($marks)
             AND l.item_id IS NULL AND pr.item_id IS NULL
           ORDER BY 7 DESC, s.faces DESC LIMIT ?""",
        (reasons + limit.toString()).toTypedArray()
    ).use { c ->
        while (c.moveToNext()) out += RescueRow(
            c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4),
            c.getString(5), if (c.isNull(6)) null else c.getFloat(6))
    }
    return out
}

/** Items the human marked KEEP and that are not yet promoted. */
fun Db.labelledKeepForPromotion(): List<PromoRow> {
    val out = mutableListOf<PromoRow>()
    readableDatabase.rawQuery(
        """SELECT i.id, i.uri, i.name, i.size_bytes, i.date_added, s.reason
           FROM label l
           JOIN item i ON i.id = l.item_id
           JOIN score s ON s.item_id = i.id
           LEFT JOIN promotion pr ON pr.item_id = i.id
           WHERE l.label='KEEP' AND pr.item_id IS NULL
           ORDER BY i.date_added""", null
    ).use { c ->
        while (c.moveToNext()) out += PromoRow(
            c.getLong(0), c.getString(1), c.getString(2),
            c.getLong(3), c.getLong(4), c.getString(5))
    }
    return out
}


/** Undecided clusters, largest first, each with its best thumbnails. */
fun Db.candidates(limit: Int, thumbsEach: Int): List<Candidate> {
    val out = mutableListOf<Candidate>()
    readableDatabase.rawQuery(
        "SELECT id, size FROM cluster WHERE status IS NULL AND size >= 2 ORDER BY size DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0); val size = c.getInt(1)
            val thumbs = mutableListOf<String>()
            readableDatabase.rawQuery(
                """SELECT thumb_path FROM cluster_face WHERE cluster_id=? AND thumb_path IS NOT NULL
                   ORDER BY orig_px DESC LIMIT ?""",
                arrayOf(id.toString(), thumbsEach.toString())
            ).use { t -> while (t.moveToNext()) thumbs += t.getString(0) }
            // Only offer a face the user can actually look at. The crops
            // live in the app's own storage while these rows hold their
            // paths, so clearing app data leaves 435 rows pointing at
            // nothing -- and the screen then asks "do you know this person?"
            // with no person on it.
            val onDisk = thumbs.filter { java.io.File(it).exists() }
            if (onDisk.isNotEmpty()) out += Candidate(id, size, onDisk)
        }
    }
    return out
}


/** Every embedded face from the bootstrap pass, so clustering can be redone
 *  without re-scanning the library (the 4-minute part). */
fun Db.allClusterFaces(): List<StoredFace> {
    val out = mutableListOf<StoredFace>()
    readableDatabase.rawQuery(
        "SELECT item_id, thumb_path, orig_px, vec FROM cluster_face", null
    ).use { c ->
        while (c.moveToNext()) out += StoredFace(
            if (c.isNull(0)) null else c.getLong(0),
            c.getString(1), c.getInt(2), bytesToFloats(c.getBlob(3)))
    }
    return out
}

/** Undo all candidate decisions (does not touch enrolled people). */
fun Db.clearClusterStatuses(): Int {
    val n = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM cluster WHERE status IS NOT NULL", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    writableDatabase.execSQL("UPDATE cluster SET status=NULL, person_id=NULL")
    return n
}

/** Every cluster's centroid, for matching people back to a fresh scan. */
fun Db.clusterCentroids(): List<Pair<Long, FloatArray>> {
    val out = mutableListOf<Pair<Long, FloatArray>>()
    readableDatabase.rawQuery("SELECT id, centroid FROM cluster", null).use { c ->
        while (c.moveToNext()) out += c.getLong(0) to bytesToFloats(c.getBlob(1))
    }
    return out
}

fun Db.clusterVectors(clusterId: Long): List<FloatArray> {
    val out = mutableListOf<FloatArray>()
    readableDatabase.rawQuery(
        "SELECT vec FROM cluster_face WHERE cluster_id=?", arrayOf(clusterId.toString())
    ).use { c -> while (c.moveToNext()) out += bytesToFloats(c.getBlob(0)) }
    return out
}

fun Db.setClusterStatus(clusterId: Long, status: String, personId: Long?) {
    val v = ContentValues().apply {
        put("status", status)
        if (personId != null) put("person_id", personId) else putNull("person_id")
    }
    writableDatabase.update("cluster", v, "id=?", arrayOf(clusterId.toString()))
}

/** Clusters the user passed over — the people whose photos may get flagged. */
fun Db.skippedClusters(minSize: Int): List<Candidate> {
    val out = mutableListOf<Candidate>()
    readableDatabase.rawQuery(
        """SELECT id, size FROM cluster
           WHERE (status = 'SKIPPED' OR status IS NULL) AND size >= ?
           ORDER BY size DESC""",
        arrayOf(minSize.toString())
    ).use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val thumbs = mutableListOf<String>()
            readableDatabase.rawQuery(
                "SELECT thumb_path FROM cluster_face WHERE cluster_id=? AND thumb_path IS NOT NULL ORDER BY orig_px DESC LIMIT 1",
                arrayOf(id.toString())
            ).use { t -> while (t.moveToNext()) thumbs += t.getString(0) }
            // The same filter [candidates] and [personThumbs] carry, and
            // the third place it was needed. A row is not a file: these
            // paths are absolute and contain the app's own package name, so
            // clearing data, reinstalling, or changing the applicationId
            // leaves every one of them pointing at nothing. Without this
            // the summary drew "Worth a second look" over a row of blank
            // rectangles with photo counts under them.
            //
            // A cluster with no face left to show is dropped rather than
            // shown faceless: the screen's whole question is "do you
            // recognise this person", which is unanswerable without a face.
            // The cost is that a warning about unclaimed faces disappears
            // when their crops do, which is the better of two bad screens.
            val onDisk = thumbs.filter { java.io.File(it).exists() }
            if (onDisk.isNotEmpty()) out += Candidate(id, c.getInt(1), onDisk)
        }
    }
    return out
}

fun Db.enrolledCount(): Int =
    readableDatabase.rawQuery("SELECT COUNT(*) FROM person", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }
