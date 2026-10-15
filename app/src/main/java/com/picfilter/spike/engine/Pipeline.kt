package com.picfilter.spike.engine

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.picfilter.spike.data.Db
import java.io.FileNotFoundException

private const val TAG = "PICFILTER"

enum class Decision { KEEP, REVIEW, DISCARD_CANDIDATE }

object Pipeline {

    private const val ENROLL_PATH = "Pictures/PicFilterEnroll/%"
    private const val TEXT_FRAC_HEAVY = 0.08f
    private const val TEXT_BLOCKS_HEAVY = 10
    private const val DUPE_HAMMING = 6

    // ---------------------------------------------------------------- enrol

    /**
     * Enrollment by folder convention: Pictures/PicFilterEnroll/<PersonName>/photo.jpg
     * MediaStore reports <PersonName> as bucket_display_name, so this needs no
     * picker UI and no extra permission.
     *
     * Only images with EXACTLY ONE quality-passing face are used — that makes the
     * face->name pairing unambiguous without any bounding-box labels.
     */
    fun enroll(ctx: Context) {
        val db = Db(ctx)
        db.clearEnrollment()
        FaceEngine(ctx, targetPx = 1200).use { eng ->
            val byPerson = HashMap<String, MutableList<FloatArray>>()
            var examined = 0; var rejectedMulti = 0; var rejectedQuality = 0

            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(ENROLL_PATH), null
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iB = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val person = c.getString(iB) ?: continue
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(iId))
                    examined++
                    try {
                        val img = eng.decode(ctx, uri) ?: continue
                        val obs = eng.observe(img)
                        img.bitmap.recycle()
                        val good = obs.filter { it.quality && it.embedding != null }
                        when {
                            good.size == 1 -> byPerson.getOrPut(person) { mutableListOf() } += good[0].embedding!!
                            good.size > 1 -> rejectedMulti++
                            else -> rejectedQuality++
                        }
                    } catch (e: Exception) { rejectedQuality++ }
                }
            }

            Log.i(TAG, "ENROLL examined=$examined usable_people=${byPerson.size} " +
                "rejected_multiface=$rejectedMulti rejected_quality=$rejectedQuality")
            if (byPerson.isEmpty()) {
                Log.w(TAG, "ENROLL nothing enrolled — put photos in " +
                    "/sdcard/Pictures/PicFilterEnroll/<PersonName>/")
            }
            for ((person, vecs) in byPerson) {
                val centroid = FloatArray(eng.dim)
                for (v in vecs) for (i in centroid.indices) centroid[i] += v[i]
                for (i in centroid.indices) centroid[i] /= vecs.size
                l2(centroid)
                val pid = db.upsertPerson(person)
                db.addCentroid(pid, centroid, vecs.size)
                // mean pairwise similarity tells us how tight the enrollment set is
                var sum = 0f; var n = 0
                for (i in vecs.indices) for (j in i + 1 until vecs.size) { sum += cos(vecs[i], vecs[j]); n++ }
                val tight = if (n > 0) sum / n else Float.NaN
                Log.i(TAG, "ENROLL person='$person' faces=${vecs.size} " +
                    "mean_pairwise_sim=${"%.3f".format(tight)}")
            }
        }
        db.close()
    }

    // ----------------------------------------------------------------- scan

    fun scanMedia(ctx: Context, limit: Int) {
        val db = Db(ctx)
        var n = 0
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("%com.whatsapp%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (c.moveToNext() && n < limit) {
                val id = c.getLong(iId)
                db.upsertItem(
                    id,
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    c.getString(1), c.getString(2), c.getString(3),
                    c.getLong(4), c.getLong(5), c.getInt(6), c.getInt(7),
                )
                n++
            }
        }
        Log.i(TAG, "SCAN upserted=$n total_items=${db.itemCount()}")
        db.close()
    }

    // ---------------------------------------------------------------- score

    fun score(ctx: Context, limit: Int) {
        val db = Db(ctx)
        val centroids = db.centroidsByPerson()
        val names = db.personNames()
        Log.i(TAG, "SCORE enrolled_people=${centroids.size} ${names.values}")

        val textRec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var done = 0; var dead = 0; var failed = 0
        val t0 = System.currentTimeMillis()

        FaceEngine(ctx).use { eng ->
            for ((itemId, uriStr, _) in db.unscoredItems(limit)) {
                val uri = Uri.parse(uriStr)
                try {
                    val img = eng.decode(ctx, uri) ?: run { dead++; null } ?: continue
                    val obs = eng.observe(img)

                    var matched = 0; var indet = 0
                    for (o in obs) {
                        val (state, pid, sim) = eng.match(o, centroids)
                        when (state) {
                            MatchState.MATCH -> matched++
                            MatchState.INDETERMINATE -> indet++
                            MatchState.NO_MATCH -> {}
                        }
                        db.addFace(itemId, o.origPx, o.sharpness, o.yaw, o.roll, state.name, pid, sim)
                    }

                    val text = Tasks.await(textRec.process(InputImage.fromBitmap(img.bitmap, 0)))
                    var area = 0f; var blocks = 0
                    for (b in text.textBlocks) { blocks++; b.boundingBox?.let { area += it.width().toFloat() * it.height() } }
                    val textFrac = area / (img.bitmap.width.toFloat() * img.bitmap.height)

                    db.setDhash(itemId, dhash(img.bitmap))
                    img.bitmap.recycle()

                    val (decision, reason, conf) = decide(obs.size, matched, indet, textFrac, blocks)
                    db.putScore(itemId, obs.size, matched, indet, textFrac, blocks, decision.name, reason, conf)
                    done++
                } catch (e: FileNotFoundException) {
                    dead++   // stale MediaStore row: rows outlive files (SPIKES.md)
                } catch (e: Exception) {
                    failed++
                    if (failed <= 3) Log.w(TAG, "SCORE err ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        textRec.close()
        val wall = System.currentTimeMillis() - t0
        Log.i(TAG, "SCORE done=$done dead_rows=$dead failed=$failed wall_ms=$wall " +
            "rate=${"%.1f".format(done * 1000.0 / maxOf(wall, 1))} img/s")
        groupDupes(db)
        db.close()
    }

    /**
     * Decision rules. Auto-discard is only ever proposed when every face in the
     * image was confidently identifiable — one INDETERMINATE face is enough to
     * force review. That closes the "kid in the class photo was too small to
     * recognise" failure mode by construction.
     */
    fun decide(
        faces: Int, matched: Int, indet: Int, textFrac: Float, blocks: Int,
    ): Triple<Decision, String, Float> {
        val textHeavy = textFrac > TEXT_FRAC_HEAVY || blocks > TEXT_BLOCKS_HEAVY
        return when {
            matched >= 1 -> Triple(Decision.KEEP, "ENROLLED_PERSON", 1.0f)
            indet >= 1 -> Triple(Decision.REVIEW, "INDETERMINATE_FACE", 0.0f)
            faces >= 3 -> Triple(Decision.DISCARD_CANDIDATE, "CROWD_NO_MATCH", 0.8f)
            faces in 1..2 -> Triple(Decision.REVIEW, "FEW_FACES_NO_MATCH", 0.4f)
            textHeavy -> Triple(Decision.DISCARD_CANDIDATE, "TEXT_NO_FACE", 0.7f)
            else -> Triple(Decision.REVIEW, "NO_FACE_PLAIN", 0.2f)
        }
    }

    // ---------------------------------------------------------------- dupes

    private fun groupDupes(db: Db) {
        val all = db.allDhashes()
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long { var r = x; while (parent[r] != null && parent[r] != r) r = parent[r]!!; parent[r] = r; return r }
        fun union(a: Long, b: Long) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        for ((id, _) in all) parent[id] = id
        for (i in all.indices) for (j in i + 1 until all.size) {
            if (java.lang.Long.bitCount(all[i].second xor all[j].second) <= DUPE_HAMMING)
                union(all[i].first, all[j].first)
        }
        val groups = HashMap<Long, MutableList<Long>>()
        for ((id, _) in all) groups.getOrPut(find(id)) { mutableListOf() } += id
        var multi = 0; var extra = 0
        for ((root, members) in groups) {
            if (members.size < 2) continue
            multi++; extra += members.size - 1
            for (m in members) db.setDupeGroup(m, root)
        }
        Log.i(TAG, "DUPES groups=$multi redundant_items=$extra (hamming<=$DUPE_HAMMING)")
    }

    private fun dhash(bmp: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var h = 0L; var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val p1 = small.getPixel(x, y); val p2 = small.getPixel(x + 1, y)
            fun lum(p: Int) = 0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)
            if (lum(p1) > lum(p2)) h = h or (1L shl bit)
            bit++
        }
        if (small !== bmp) small.recycle()
        return h
    }

    // --------------------------------------------------------------- report

    fun report(ctx: Context) {
        val db = Db(ctx)
        Log.i(TAG, "REPORT items=${db.itemCount()}")
        Log.i(TAG, "REPORT --- face states ---")
        for ((s, n) in db.faceStateCounts()) Log.i(TAG, "REPORT   face $s = $n")
        Log.i(TAG, "REPORT --- decisions ---")
        for ((d, n) in db.decisionCounts()) Log.i(TAG, "REPORT   $d = $n")
        Log.i(TAG, "REPORT --- reasons ---")
        for ((r, n) in db.reasonCounts()) Log.i(TAG, "REPORT   $r = $n")
        val per = db.matchesPerPerson()
        Log.i(TAG, "REPORT --- matches per person ---")
        if (per.isEmpty()) Log.i(TAG, "REPORT   (none — enroll first)")
        for ((name, n, sim) in per) Log.i(TAG, "REPORT   $name matches=$n mean_sim=${"%.3f".format(sim)}")
        val labels = db.labelCounts()
        Log.i(TAG, "REPORT --- human labels (golden set) ---")
        if (labels.isEmpty()) Log.i(TAG, "REPORT   (none yet — use the Review screen)")
        for ((l, n) in labels) Log.i(TAG, "REPORT   label $l = $n")
        val dv = db.dupeVerdictCounts()
        Log.i(TAG, "REPORT --- dupe verdicts ---")
        if (dv.isEmpty()) Log.i(TAG, "REPORT   (none yet)")
        for ((v, n) in dv) Log.i(TAG, "REPORT   dupe $v = $n")
        val conf = db.confusion()
        if (conf.isNotEmpty()) {
            Log.i(TAG, "REPORT --- scorer reason vs human label ---")
            for ((r, l, n) in conf) Log.i(TAG, "REPORT   $r -> $l = $n")
        }
        db.close()
    }

    fun labels(ctx: Context) {
        val db = Db(ctx)
        val all = db.allLabels()
        Log.i(TAG, "LABELS count=${all.size}")
        for (r in all) Log.i(TAG, "LABELS   ${r.label.padEnd(7)} id=${r.itemId} " +
            "faces=${r.faces} matched=${r.matched} indet=${r.indet} ${r.reason} ${r.name}")
        if (all.isEmpty()) Log.i(TAG, "LABELS   (none recorded)")
        Log.i(TAG, "LABELS note: labels are opinions only — no file is ever touched by review")
        db.close()
    }

    fun unlabel(ctx: Context, itemId: Long) {
        val db = Db(ctx)
        db.deleteLabel(itemId)
        Log.i(TAG, "LABELS removed label for id=$itemId — it returns to the review queue")
        db.close()
    }

    fun clearLabels(ctx: Context) {
        val db = Db(ctx)
        val n = db.clearLabels()
        Log.i(TAG, "LABELS cleared=$n (labels + dupe verdicts); review queue is fresh again")
        db.close()
    }

    fun reset(ctx: Context) {
        val db = Db(ctx); db.resetScores(); db.close(); Log.i(TAG, "RESET scores+faces cleared")
    }
}
