package com.picfilter.spike.data

import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase

/**
 * What each screen asks for, in the shape the screen needs it.
 *
 * Extensions on [Db] rather than members of it: every call site is
 * unchanged, and Db keeps the schema and the migrations, which are the
 * part that has to be read as one piece.
 */

// ---------- home screen surfaces ----------


/**
 * Enrolled people for the people row: face crops to choose an avatar from
 * and how many photos that person protects. Several crops, biggest first,
 * because an aligned crop that ran off the edge of its photo is stored with
 * a black band — the caller picks the cleanest one. A person enrolled from
 * a dropped folder has no cluster behind them, so the list can be empty and
 * the caller draws initials instead.
 */
fun Db.enrolledPeople(thumbsEach: Int = 6): List<PersonChip> {
    val out = mutableListOf<PersonChip>()
    readableDatabase.rawQuery(
        """SELECT p.id, p.name,
                  (SELECT COUNT(DISTINCT f.item_id) FROM face f
                   WHERE f.person_id = p.id AND f.state = 'MATCH')
           FROM person p ORDER BY 3 DESC, p.name""", null
    ).use { c ->
        while (c.moveToNext()) out += PersonChip(
            c.getLong(0), c.getString(1), emptyList(), c.getInt(2))
    }
    return out.map { it.copy(thumbs = personThumbs(it.id, thumbsEach)) }
}

/** A cluster's face crops, biggest first. */
fun Db.clusterThumbs(clusterId: Long, limit: Int): List<String> {
    val out = mutableListOf<String>()
    readableDatabase.rawQuery(
        """SELECT thumb_path FROM cluster_face
           WHERE cluster_id = ? AND thumb_path IS NOT NULL
           ORDER BY orig_px DESC LIMIT ?""",
        arrayOf(clusterId.toString(), limit.toString())
    ).use { c -> while (c.moveToNext()) out += c.getString(0) }
    return out
}

/** A person's face crops, biggest first. */
/**
 * Photos this person was matched in, biggest face first.
 *
 * For rebuilding an avatar from the library when the stored crops are gone.
 */
/**
 * Photos this person is in, one row each, biggest face first.
 *
 * [matchedItemsFor] can return the same photo twice when a person matched
 * on two faces in it, which is fine for picking an avatar and wrong for a
 * grid the user counts.
 */
fun Db.photosOf(personId: Long, limit: Int = 500): List<Pair<Long, String>> {
    val out = mutableListOf<Pair<Long, String>>()
    readableDatabase.rawQuery(
        """SELECT i.id, i.uri, MAX(f.orig_px) AS px
           FROM face f JOIN item i ON i.id = f.item_id
           WHERE f.person_id = ? AND f.state = 'MATCH'
           GROUP BY i.id ORDER BY px DESC LIMIT ?""",
        arrayOf(personId.toString(), limit.toString())
    ).use { c -> while (c.moveToNext()) out += c.getLong(0) to c.getString(1) }
    return out
}

/**
 * Take someone out of the enrolled set.
 *
 * Their centroids go, so nothing matches them again, and their clusters go
 * back to being unclaimed candidates rather than vanishing -- a person
 * removed by mistake can be named again without re-running the scan.
 *
 * Only the photos that matched THEM are requeued. Removing a person can
 * turn a MATCH into a NO_MATCH and nothing else, so every other verdict in
 * the database is still correct, and bumping the global revision would
 * re-read the whole library to re-confirm answers that cannot have
 * changed. The mirror of [invalidateUnmatched].
 *
 * Photos already rescued stay rescued. This changes who PixKin recognises
 * from now on; it does not reach into the backed-up folder.
 *
 * @return how many items will be scored again.
 */
fun Db.removePerson(personId: Long): Int {
    val db = writableDatabase
    // Before the rows that identify them are gone.
    db.execSQL(
        """UPDATE score SET centroid_rev = -1
           WHERE item_id IN (SELECT item_id FROM face WHERE person_id = ?)""",
        arrayOf<Any>(personId))
    val requeued = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM score WHERE centroid_rev = -1", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    db.execSQL("UPDATE cluster SET person_id = NULL, status = NULL WHERE person_id = ?",
        arrayOf<Any>(personId))
    db.execSQL("UPDATE face SET person_id = NULL, similarity = NULL WHERE person_id = ?",
        arrayOf<Any>(personId))
    db.execSQL("DELETE FROM centroid WHERE person_id = ?", arrayOf<Any>(personId))
    db.execSQL("DELETE FROM person WHERE id = ?", arrayOf<Any>(personId))
    return requeued
}

/**
 * Photos PixKin decided NOT to save, and that someone has actually decided
 * about.
 *
 * The home screen used to show `decisionCounts()` here, which counts every
 * item the scorer ever flagged whether or not a human has looked at it. So
 * it read "159 junk found" while the junk queue was empty, because the
 * queue hides what has been judged and the counter did not. A number next
 * to an empty list is worse than no number.
 *
 * An item counts once: either a human said discard, or the scorer called it
 * junk and nobody has overruled that.
 */
fun Db.notSavedCount(): Int =
    readableDatabase.rawQuery(
        """SELECT COUNT(*) FROM score s
           LEFT JOIN label l ON l.item_id = s.item_id
           WHERE l.label = 'DISCARD'
              OR (s.decision = 'DISCARD_CANDIDATE'
                  AND (l.label IS NULL OR l.label <> 'KEEP'))""", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }


fun Db.queueSizes(): QueueSizes {
    fun one(sql: String, args: Array<String> = emptyArray()) =
        readableDatabase.rawQuery(sql, args)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    return QueueSizes(
        verify = one(
            """SELECT COUNT(*) FROM promotion pr
               JOIN score s ON s.item_id = pr.item_id
               LEFT JOIN label l ON l.item_id = pr.item_id
               WHERE l.item_id IS NULL"""),
        rescue = one(
            """SELECT COUNT(*) FROM score s
               LEFT JOIN label l ON l.item_id = s.item_id
               LEFT JOIN promotion pr ON pr.item_id = s.item_id
               WHERE s.decision='REVIEW'
                 AND s.reason IN ('INDETERMINATE_FACE','FEW_FACES_NO_MATCH')
                 AND l.item_id IS NULL AND pr.item_id IS NULL"""),
        noface = one(
            """SELECT COUNT(*) FROM score s
               LEFT JOIN label l ON l.item_id = s.item_id
               LEFT JOIN promotion pr ON pr.item_id = s.item_id
               WHERE s.decision='REVIEW' AND s.reason='NO_FACE_PLAIN'
                 AND l.item_id IS NULL AND pr.item_id IS NULL"""),
        junk = one(
            """SELECT COUNT(*) FROM score s
               LEFT JOIN label l ON l.item_id = s.item_id
               WHERE s.decision='DISCARD_CANDIDATE' AND l.item_id IS NULL"""),
        dupes = dupeGroupCount(),
    )
}

/** Items still waiting for a human, across every review queue. */
fun Db.undecidedCount(): Int =
    readableDatabase.rawQuery(
        """SELECT COUNT(*) FROM score s
           LEFT JOIN label l ON l.item_id = s.item_id
           WHERE l.item_id IS NULL AND s.decision <> 'KEEP'""", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

fun Db.matchedItemsFor(personId: Long, limit: Int): List<Pair<Long, String>> {
    val out = mutableListOf<Pair<Long, String>>()
    readableDatabase.rawQuery(
        """SELECT i.id, i.uri FROM face f JOIN item i ON i.id = f.item_id
           WHERE f.person_id = ? AND f.state = 'MATCH'
           ORDER BY f.orig_px DESC LIMIT ?""",
        arrayOf(personId.toString(), limit.toString())
    ).use { c -> while (c.moveToNext()) out += c.getLong(0) to c.getString(1) }
    return out
}

internal fun Db.personThumbs(personId: Long, limit: Int): List<String> {
    val out = mutableListOf<String>()
    readableDatabase.rawQuery(
        """SELECT cf.thumb_path FROM cluster_face cf
           JOIN cluster c ON c.id = cf.cluster_id
           WHERE c.person_id = ? AND cf.thumb_path IS NOT NULL
           ORDER BY cf.orig_px DESC LIMIT ?""",
        arrayOf(personId.toString(), limit.toString())
    ).use { c -> while (c.moveToNext()) out += c.getString(0) }
    // A row is not a file. The crops live in the app's own storage while
    // these rows hold their paths, so clearing app data or reinstalling
    // takes the files and leaves 435 rows pointing at nothing. Handing a
    // dead path to the caller is what made the people row fall back to
    // initials while every piece of code that could have repaired it
    // believed there was a face already. [candidates] carries this same
    // filter, for the same reason.
    return out.filter { java.io.File(it).exists() }
}

/** Suspected junk: DISCARD_CANDIDATE items the human has not judged yet. */
fun Db.junkQueue(limit: Int = -1): List<RescueRow> {
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
           WHERE s.decision = 'DISCARD_CANDIDATE' AND l.item_id IS NULL
           ORDER BY i.date_added DESC LIMIT ?""",
        arrayOf(limit.toString())
    ).use { c ->
        while (c.moveToNext()) out += RescueRow(
            c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4),
            c.getString(5), if (c.isNull(6)) null else c.getFloat(6))
    }
    return out
}

/** One photo per undecided near-duplicate group, newest group first. */
fun Db.dupePreview(limit: Int): List<String> {
    val out = mutableListOf<String>()
    readableDatabase.rawQuery(
        """SELECT MAX(i.date_added) AS d,
                  (SELECT i2.uri FROM item i2 JOIN score s2 ON s2.item_id = i2.id
                   WHERE s2.dupe_group = s.dupe_group
                   ORDER BY i2.date_added DESC LIMIT 1)
           FROM score s
           JOIN item i ON i.id = s.item_id
           LEFT JOIN dupe_verdict v ON v.group_id = s.dupe_group
           WHERE s.dupe_group IS NOT NULL AND v.group_id IS NULL
           GROUP BY s.dupe_group HAVING COUNT(*) >= 2
           ORDER BY d DESC LIMIT ?""",
        arrayOf(limit.toString())
    ).use { c -> while (c.moveToNext()) if (!c.isNull(1)) out += c.getString(1) }
    return out
}

/** Near-duplicate groups still awaiting a verdict. */
fun Db.dupeGroupCount(): Int =
    readableDatabase.rawQuery(
        """SELECT COUNT(*) FROM (
             SELECT s.dupe_group FROM score s
             LEFT JOIN dupe_verdict v ON v.group_id = s.dupe_group
             WHERE s.dupe_group IS NOT NULL AND v.group_id IS NULL
             GROUP BY s.dupe_group HAVING COUNT(*) >= 2)""", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

/** Full scoring detail for one item — used to check specific photos. */
fun Db.itemDetail(itemId: Long): String {
    val sb = StringBuilder()
    readableDatabase.rawQuery(
        """SELECT i.name, s.decision, s.reason, s.faces, s.matched, s.indet, s.text_frac
           FROM item i LEFT JOIN score s ON s.item_id = i.id WHERE i.id = ?""",
        arrayOf(itemId.toString())
    ).use { c ->
        if (!c.moveToFirst()) return "item $itemId not found"
        sb.append("${c.getString(0)}  ->  ${c.getString(1)} / ${c.getString(2)}  ")
        sb.append("faces=${c.getInt(3)} matched=${c.getInt(4)} indet=${c.getInt(5)}")
    }
    readableDatabase.rawQuery(
        """SELECT f.state, p.name, f.similarity, f.orig_px FROM face f
           LEFT JOIN person p ON p.id = f.person_id WHERE f.item_id = ?""",
        arrayOf(itemId.toString())
    ).use { c ->
        while (c.moveToNext()) sb.append("\n    face ${c.getString(0)}" +
            (c.getString(1)?.let { " = $it" } ?: "") +
            (if (c.isNull(2)) "" else " sim=${"%.3f".format(c.getFloat(2))}") +
            " px=${c.getInt(3)}")
    }
    return sb.toString()
}

/** What is sitting on this phone only, with no cloud copy? */
fun Db.exposure(): String {
    val sb = StringBuilder()
    fun row(label: String, where: String) {
        readableDatabase.rawQuery(
            """SELECT COUNT(*), MIN(i.date_added), MAX(i.date_added), SUM(i.size_bytes)
               FROM item i JOIN score s ON s.item_id = i.id WHERE $where""", null
        ).use { c ->
            if (c.moveToFirst() && c.getInt(0) > 0) {
                val mb = c.getLong(3) / 1048576.0
                sb.append("\n  %-22s n=%-5d %.1f MB   %s .. %s".format(
                    label, c.getInt(0), mb,
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date(c.getLong(1) * 1000)),
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date(c.getLong(2) * 1000))))
            }
        }
    }
    row("KEEP (family)", "s.decision='KEEP'")
    row("REVIEW", "s.decision='REVIEW'")
    row("DISCARD_CANDIDATE", "s.decision='DISCARD_CANDIDATE'")
    row("ALL whatsapp items", "1=1")
    return sb.toString()
}
