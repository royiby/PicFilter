package com.picfilter.spike.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 1 store. Raw SQLite + typed DAO (Room swap is a later cleanup).
 *
 * [name] exists so tests can work on a throwaway database instead of the real
 * one -- an instrumented test runs as this package and would otherwise scribble
 * on the user's own scored corpus.
 */
class Db(ctx: Context, name: String = DEFAULT_NAME) : SQLiteOpenHelper(ctx, name, null, 6) {

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
        d.execSQL("CREATE INDEX idx_face_item ON face(item_id)")
        d.execSQL("CREATE INDEX idx_item_bucket ON item(bucket)")
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
        try { d.execSQL("ALTER TABLE person ADD COLUMN threshold REAL") } catch (e: Exception) { }
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
        } catch (e: Exception) { }
        // On an existing database the scores predate this column and the people
        // may have been enrolled after them, which is exactly the bug this
        // column exists to catch. Start the revision at 1 so those rows read as
        // stale; a fresh database has nobody enrolled and stays at 0.
        val enrolled = try {
            d.rawQuery("SELECT COUNT(*) FROM centroid", null)
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) { 0 }
        if (enrolled > 0) {
            d.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES('centroid_rev', '1')")
        }
    }

    override fun onUpgrade(d: SQLiteDatabase, old: Int, new: Int) {
        // Additive only — the scored corpus is expensive to rebuild.
        if (old < 2) createV2(d)
        if (old < 3) createV3(d)
        if (old < 4) createV4(d)
        if (old < 5) createV5(d)
        if (old < 6) createV6(d)
    }

    // ---------- centroid revision ----------

    /**
     * Bumped whenever the thing a score is compared against changes: a new
     * centroid, a wiped enrolment, a refitted threshold. [unscoredItems] treats
     * any score below the current revision as needing recomputation, so
     * enrolling a person silently queues a rescore instead of leaving every
     * item judged against nobody.
     */
    fun centroidRev(): Long =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k='centroid_rev'", null)
            .use { if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L }

    fun bumpCentroidRev(): Long {
        val next = centroidRev() + 1
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO meta(k, v) VALUES('centroid_rev', ?)",
            arrayOf<Any>(next.toString())
        )
        return next
    }

    /** Items whose score predates the current centroid revision. */
    fun staleScoreCount(): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM score WHERE centroid_rev < ?",
            arrayOf(centroidRev().toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---------- people / centroids ----------

    fun upsertPerson(name: String): Long {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO person(name, created_at) VALUES(?,?)",
            arrayOf<Any>(name, System.currentTimeMillis())
        )
        readableDatabase.rawQuery("SELECT id FROM person WHERE name=?", arrayOf(name)).use {
            return if (it.moveToFirst()) it.getLong(0) else -1L
        }
    }

    fun addCentroid(personId: Long, vec: FloatArray, faceCount: Int) {
        val v = ContentValues().apply {
            put("person_id", personId); put("vec", floatsToBytes(vec))
            put("face_count", faceCount); put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("centroid", null, v)
        bumpCentroidRev()
    }

    /** All centroids, keyed by person. Matching takes the max similarity over a person's set. */
    fun centroidsByPerson(): Map<Long, List<FloatArray>> {
        val out = HashMap<Long, MutableList<FloatArray>>()
        readableDatabase.rawQuery("SELECT person_id, vec FROM centroid", null).use { c ->
            while (c.moveToNext()) {
                out.getOrPut(c.getLong(0)) { mutableListOf() } += bytesToFloats(c.getBlob(1))
            }
        }
        return out
    }

    fun personNames(): Map<Long, String> {
        val out = HashMap<Long, String>()
        readableDatabase.rawQuery("SELECT id, name FROM person", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getString(1)
        }
        return out
    }

    fun clearEnrollment() {
        writableDatabase.execSQL("DELETE FROM centroid")
        writableDatabase.execSQL("DELETE FROM person")
        bumpCentroidRev()
    }

    // ---------- items ----------

    fun upsertItem(
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

    fun itemCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM item", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun unscoredItems(limit: Int): List<Triple<Long, String, Int>> {
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

    fun setDhash(itemId: Long, h: Long) =
        writableDatabase.execSQL("UPDATE item SET dhash=? WHERE id=?", arrayOf<Any>(h, itemId))

    fun allDhashes(): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        readableDatabase.rawQuery("SELECT id, dhash FROM item WHERE dhash IS NOT NULL", null).use { c ->
            while (c.moveToNext()) out += c.getLong(0) to c.getLong(1)
        }
        return out
    }

    // ---------- faces / scores ----------

    /** Rescoring re-detects, so the previous pass's rows have to go first. */
    fun faceCountFor(itemId: Long): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM face WHERE item_id=?",
            arrayOf(itemId.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun clearFaces(itemId: Long) =
        writableDatabase.execSQL("DELETE FROM face WHERE item_id=?", arrayOf<Any>(itemId))

    fun addFace(
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

    fun putScore(
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

    fun setDupeGroup(itemId: Long, group: Long) =
        writableDatabase.execSQL("UPDATE score SET dupe_group=? WHERE item_id=?", arrayOf<Any>(group, itemId))

    fun decisionCounts(): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT decision, COUNT(*) FROM score GROUP BY decision ORDER BY 2 DESC", null
        ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getInt(1) }
        return out
    }

    fun reasonCounts(): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT reason, COUNT(*) FROM score GROUP BY reason ORDER BY 2 DESC", null
        ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getInt(1) }
        return out
    }

    fun faceStateCounts(): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT state, COUNT(*) FROM face GROUP BY state ORDER BY 2 DESC", null
        ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getInt(1) }
        return out
    }

    fun matchesPerPerson(): List<Triple<String, Int, Double>> {
        val out = mutableListOf<Triple<String, Int, Double>>()
        readableDatabase.rawQuery(
            """SELECT p.name, COUNT(*), AVG(f.similarity) FROM face f
               JOIN person p ON p.id = f.person_id
               WHERE f.state='MATCH' GROUP BY p.name ORDER BY 2 DESC""", null
        ).use { c -> while (c.moveToNext()) out += Triple(c.getString(0), c.getInt(1), c.getDouble(2)) }
        return out
    }

    /** Raw per-face metrics for threshold sweeps — no re-detection needed. */
    fun facesForSweep(): List<FaceRow> {
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
    fun textSignals(): Map<Long, Pair<Float, Int>> {
        val out = HashMap<Long, Pair<Float, Int>>()
        readableDatabase.rawQuery("SELECT item_id, text_frac, text_blocks FROM score", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getFloat(1) to c.getInt(2)
        }
        return out
    }

    fun dupeGroupsAt(hamming: Int): Pair<Int, Int> {
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

    // ---------- golden set ----------

    fun putLabel(itemId: Long, label: String, note: String? = null) {
        val v = ContentValues().apply {
            put("item_id", itemId); put("label", label)
            put("at", System.currentTimeMillis()); put("note", note)
        }
        writableDatabase.insertWithOnConflict("label", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteLabel(itemId: Long) =
        writableDatabase.execSQL("DELETE FROM label WHERE item_id=?", arrayOf<Any>(itemId))

    fun labelCounts(): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT label, COUNT(*) FROM label GROUP BY label ORDER BY 2 DESC", null
        ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getInt(1) }
        return out
    }

    /** Golden-set queue: unlabelled items, stratified so every reason is covered. */
    fun goldenQueue(perReason: Int): List<ReviewRow> {
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
    fun dupeGroups(): List<Pair<Long, List<Pair<Long, String>>>> {
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

    fun putDupeVerdict(groupId: Long, verdict: String) {
        val v = ContentValues().apply {
            put("group_id", groupId); put("verdict", verdict); put("at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("dupe_verdict", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun dupeVerdictCounts(): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT verdict, COUNT(*) FROM dupe_verdict GROUP BY verdict", null
        ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getInt(1) }
        return out
    }

    /** Agreement between the scorer's decision and the human label. */
    fun confusion(): List<Triple<String, String, Int>> {
        val out = mutableListOf<Triple<String, String, Int>>()
        readableDatabase.rawQuery(
            """SELECT s.reason, l.label, COUNT(*) FROM label l
               JOIN score s ON s.item_id = l.item_id
               GROUP BY s.reason, l.label ORDER BY s.reason, 3 DESC""", null
        ).use { c -> while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1), c.getInt(2)) }
        return out
    }

    data class LabelRow(
        val itemId: Long, val label: String, val name: String?,
        val reason: String?, val faces: Int, val matched: Int, val indet: Int,
    )

    fun allLabels(): List<LabelRow> {
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

    fun clearLabels(): Int {
        val n = readableDatabase.rawQuery("SELECT COUNT(*) FROM label", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        writableDatabase.execSQL("DELETE FROM label")
        writableDatabase.execSQL("DELETE FROM dupe_verdict")
        return n
    }

    // ---------- onboarding clusters ----------

    fun clearClusters() {
        writableDatabase.execSQL("DELETE FROM cluster_face")
        writableDatabase.execSQL("DELETE FROM cluster")
    }

    fun addCluster(size: Int, centroid: FloatArray): Long {
        val v = ContentValues().apply {
            put("size", size); put("centroid", floatsToBytes(centroid))
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("cluster", null, v)
    }

    fun addClusterFace(clusterId: Long, itemId: Long?, thumb: String?, origPx: Int, vec: FloatArray) {
        val v = ContentValues().apply {
            put("cluster_id", clusterId)
            if (itemId != null) put("item_id", itemId) else putNull("item_id")
            put("thumb_path", thumb); put("orig_px", origPx); put("vec", floatsToBytes(vec))
        }
        writableDatabase.insert("cluster_face", null, v)
    }

    data class RescueRow(
        val id: Long, val uri: String, val reason: String, val faces: Int,
        val indet: Int, val nearName: String?, val nearSim: Float?,
    )

    /**
     * Items worth a human look for rescue: REVIEW items that contain faces,
     * ordered by how close their best near-miss came to an enrolled person.
     * Most-likely-family first, so the user can stop when they stop seeing family.
     */
    fun rescueQueue(
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
    fun labelledKeepForPromotion(): List<PromoRow> {
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

    data class Candidate(val id: Long, val size: Int, val thumbs: List<String>)

    /** Undecided clusters, largest first, each with its best thumbnails. */
    fun candidates(limit: Int, thumbsEach: Int): List<Candidate> {
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
                out += Candidate(id, size, thumbs)
            }
        }
        return out
    }

    data class StoredFace(
        val itemId: Long?, val thumb: String?, val origPx: Int, val vec: FloatArray,
    )

    /** Every embedded face from the bootstrap pass, so clustering can be redone
     *  without re-scanning the library (the 4-minute part). */
    fun allClusterFaces(): List<StoredFace> {
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
    fun clearClusterStatuses(): Int {
        val n = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cluster WHERE status IS NOT NULL", null
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        writableDatabase.execSQL("UPDATE cluster SET status=NULL, person_id=NULL")
        return n
    }

    /** Every cluster's centroid, for matching people back to a fresh scan. */
    fun clusterCentroids(): List<Pair<Long, FloatArray>> {
        val out = mutableListOf<Pair<Long, FloatArray>>()
        readableDatabase.rawQuery("SELECT id, centroid FROM cluster", null).use { c ->
            while (c.moveToNext()) out += c.getLong(0) to bytesToFloats(c.getBlob(1))
        }
        return out
    }

    fun clusterVectors(clusterId: Long): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        readableDatabase.rawQuery(
            "SELECT vec FROM cluster_face WHERE cluster_id=?", arrayOf(clusterId.toString())
        ).use { c -> while (c.moveToNext()) out += bytesToFloats(c.getBlob(0)) }
        return out
    }

    fun setClusterStatus(clusterId: Long, status: String, personId: Long?) {
        val v = ContentValues().apply {
            put("status", status)
            if (personId != null) put("person_id", personId) else putNull("person_id")
        }
        writableDatabase.update("cluster", v, "id=?", arrayOf(clusterId.toString()))
    }

    /** Clusters the user passed over — the people whose photos may get flagged. */
    fun skippedClusters(minSize: Int): List<Candidate> {
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
                out += Candidate(id, c.getInt(1), thumbs)
            }
        }
        return out
    }

    fun enrolledCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM person", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---------- home screen surfaces ----------

    data class PersonChip(
        val id: Long, val name: String, val thumbs: List<String>, val photos: Int,
    )

    /**
     * Enrolled people for the people row: face crops to choose an avatar from
     * and how many photos that person protects. Several crops, biggest first,
     * because an aligned crop that ran off the edge of its photo is stored with
     * a black band — the caller picks the cleanest one. A person enrolled from
     * a dropped folder has no cluster behind them, so the list can be empty and
     * the caller draws initials instead.
     */
    fun enrolledPeople(thumbsEach: Int = 6): List<PersonChip> {
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
    fun clusterThumbs(clusterId: Long, limit: Int): List<String> {
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
    fun matchedItemsFor(personId: Long, limit: Int): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        readableDatabase.rawQuery(
            """SELECT i.id, i.uri FROM face f JOIN item i ON i.id = f.item_id
               WHERE f.person_id = ? AND f.state = 'MATCH'
               ORDER BY f.orig_px DESC LIMIT ?""",
            arrayOf(personId.toString(), limit.toString())
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) to c.getString(1) }
        return out
    }

    private fun personThumbs(personId: Long, limit: Int): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            """SELECT cf.thumb_path FROM cluster_face cf
               JOIN cluster c ON c.id = cf.cluster_id
               WHERE c.person_id = ? AND cf.thumb_path IS NOT NULL
               ORDER BY cf.orig_px DESC LIMIT ?""",
            arrayOf(personId.toString(), limit.toString())
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    /** Suspected junk: DISCARD_CANDIDATE items the human has not judged yet. */
    fun junkQueue(limit: Int = -1): List<RescueRow> {
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
    fun dupePreview(limit: Int): List<String> {
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
    fun dupeGroupCount(): Int =
        readableDatabase.rawQuery(
            """SELECT COUNT(*) FROM (
                 SELECT s.dupe_group FROM score s
                 LEFT JOIN dupe_verdict v ON v.group_id = s.dupe_group
                 WHERE s.dupe_group IS NOT NULL AND v.group_id IS NULL
                 GROUP BY s.dupe_group HAVING COUNT(*) >= 2)""", null
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Full scoring detail for one item — used to check specific photos. */
    fun itemDetail(itemId: Long): String {
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
    fun exposure(): String {
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

    // ---------- promotion (rescue to a backed-up folder) ----------

    data class PromoRow(
        val id: Long, val uri: String, val name: String?, val bytes: Long,
        val dateAdded: Long, val reason: String,
    )

    /** KEEP items not yet promoted. Never returns DISCARD_CANDIDATE. */
    fun itemsForPromotion(decisions: List<String>): List<PromoRow> {
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

    fun recordPromotion(itemId: Long, destUri: String, destName: String, bytes: Long) {
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
    fun calibration(reasonFilter: String? = null): List<Triple<String, Int, Int>> {
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
    fun promotedForReview(limit: Int = -1): List<RescueRow> {
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

    fun promotionNameFor(itemId: Long): String? =
        readableDatabase.rawQuery(
            "SELECT dest_name FROM promotion WHERE item_id=?", arrayOf(itemId.toString())
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun deletePromotion(itemId: Long) {
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
    fun precisionByBand(): List<Triple<String, Int, Int>> {
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
    fun precisionByPerson(): List<Triple<String, Int, Int>> {
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
    fun personThresholds(): Map<Long, Float> {
        val out = HashMap<Long, Float>()
        readableDatabase.rawQuery(
            "SELECT id, threshold FROM person WHERE threshold IS NOT NULL", null
        ).use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getFloat(1) }
        return out
    }

    fun setPersonThreshold(personId: Long, t: Float) {
        writableDatabase.execSQL("UPDATE person SET threshold=? WHERE id=?",
            arrayOf<Any>(t, personId))
        bumpCentroidRev()
    }

    /** (personId, name, similarity, wasActuallyFamily) for every human-judged match. */
    fun labelledMatches(): List<Quad> {
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

    fun promotedDests(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT dest_uri, dest_name FROM promotion", null).use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getString(1)
        }
        return out
    }

    /** Collisions: distinct source items that were written to the same filename. */
    fun promotionCollisions(): Triple<Int, Int, List<String>> {
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

    fun promotionCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM promotion", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun unscoredCount(): Int =
        readableDatabase.rawQuery(
            """SELECT COUNT(*) FROM item i LEFT JOIN score s ON s.item_id = i.id
               WHERE s.item_id IS NULL OR s.centroid_rev < ?""",
            arrayOf(centroidRev().toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun promotionSummary(): String {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(bytes),0) FROM promotion", null
        ).use { c ->
            return if (c.moveToFirst())
                "${c.getInt(0)} items, ${"%.1f".format(c.getLong(1) / 1048576.0)} MB"
            else "none"
        }
    }

    /** Test helper: make the N most recent items unscored again. */
    fun unscoreRecent(n: Int): Int {
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

    fun resetScores() {
        writableDatabase.execSQL("DELETE FROM score")
        writableDatabase.execSQL("DELETE FROM face")
    }

    data class ReviewRow(
        val id: Long, val uri: String, val decision: String, val reason: String,
        val faces: Int, val matched: Int, val indet: Int, val textFrac: Float,
        val dupeGroup: Long?,
    )

    data class FaceRow(
        val itemId: Long, val origPx: Int, val sharpness: Double,
        val yaw: Float, val roll: Float,
    )

    companion object {
        const val DEFAULT_NAME = "picfilter.db"

        fun floatsToBytes(v: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (x in v) b.putFloat(x)
            return b.array()
        }
        fun bytesToFloats(a: ByteArray): FloatArray {
            val b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(a.size / 4) { b.getFloat() }
        }
    }
}
