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
import androidx.core.net.toUri
import androidx.core.graphics.scale
import androidx.core.graphics.get
import android.os.Environment

private const val TAG = "PICFILTER"

enum class Decision { KEEP, REVIEW, DISCARD_CANDIDATE }

object Pipeline {

    private const val ENROLL_DIR = "Pictures/PicFilterEnroll"
    private const val ENROLL_PATH = "$ENROLL_DIR/%"
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
                    "${Environment.getExternalStorageDirectory()}/$ENROLL_DIR/<PersonName>/")
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
        val thresholds = db.personThresholds()
        if (thresholds.isNotEmpty())
            Log.i(TAG, "SCORE per-person thresholds: " +
                thresholds.entries.joinToString { "${names[it.key]}=${"%.2f".format(it.value)}" })
        Log.i(TAG, "SCORE enrolled_people=${centroids.size} ${names.values} " +
            "centroid_rev=${db.centroidRev()} stale=${db.staleScoreCount()}")

        // Refuse to score people's photos against nobody.
        //
        // This happened: 290 images were scored in one window with every face
        // detected normally and every single one recorded NO_MATCH, because the
        // centroid set came back empty while five people were enrolled. 17 of
        // the user's keepers silently became REVIEW or DISCARD_CANDIDATE -- the
        // scorer cannot tell "nobody matched" from "nobody to match against",
        // so it wrote 290 confident wrong answers.
        //
        // Scoring with nobody enrolled at all is legitimate: that is a new
        // install, and the verdicts are honestly "we do not know yet". Scoring
        // with people enrolled and no centroids for them is not, and the right
        // move is to do nothing and let the pass be retried.
        val enrolled = db.enrolledCount()
        if (enrolled > 0 && centroids.isEmpty()) {
            db.close()
            throw IllegalStateException(
                "refusing to score: $enrolled people enrolled but no centroids loaded")
        }

        val textRec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var done = 0; var dead = 0; var failed = 0
        val t0 = System.currentTimeMillis()

        FaceEngine(ctx).use { eng ->
            for ((itemId, uriStr, _) in db.unscoredItems(limit)) {
                val uri = uriStr.toUri()
                try {
                    val img = eng.decode(ctx, uri) ?: run { dead++; null } ?: continue
                    val obs = eng.observe(img)

                    // One transaction for the whole item: a clear plus a row
                    // per detected face is otherwise a separate commit each,
                    // and a crowd photo can hold a dozen faces.
                    var matched = 0; var indet = 0
                    db.inTransaction {
                        // This item may be a rescore after an enrolment change.
                        db.clearFaces(itemId)
                        for (o in obs) {
                            val (state, pid, sim) = eng.match(o, centroids, thresholds)
                            when (state) {
                                MatchState.MATCH -> matched++
                                MatchState.INDETERMINATE -> indet++
                                MatchState.NO_MATCH -> {}
                            }
                            db.addFace(itemId, o.origPx, o.sharpness, o.yaw, o.roll,
                                state.name, pid, sim)
                        }
                    }

                    // Only read text when it can change the answer. decide()
                    // consults the text signals in exactly one case -- an image
                    // with NO faces -- because a match, an unjudged face, a
                    // crowd, or one or two strangers all settle the verdict
                    // before textHeavy is reached. Running it anyway cost a
                    // second ML Kit pass over every image: measured at ~44 MB
                    // of native-heap growth per 100 images, discarded for the
                    // ~63% of this library that has a face in it.
                    var textFrac = 0f
                    var blocks = 0
                    if (obs.isEmpty()) {
                        val text = Tasks.await(textRec.process(InputImage.fromBitmap(img.bitmap, 0)))
                        var area = 0f
                        for (b in text.textBlocks) { blocks++; b.boundingBox?.let { area += it.width().toFloat() * it.height() } }
                        textFrac = area / (img.bitmap.width.toFloat() * img.bitmap.height)
                    }

                    db.setDhash(itemId, dhash(img.bitmap))
                    img.bitmap.recycle()

                    val (decision, reason, conf) = decide(obs.size, matched, indet, textFrac, blocks)
                    db.putScore(itemId, obs.size, matched, indet, textFrac, blocks, decision.name, reason, conf)
                    done++
                } catch (e: FileNotFoundException) {
                    dead++   // stale MediaStore row: rows outlive files (SPIKES.md)
                // Deliberately broad. One photo is decoded by the
                // platform decoder, measured by ML Kit and embedded by
                // TFLite -- three third-party surfaces that throw
                // IOException, MlKitException, IllegalStateException and
                // more between them. A single unreadable photo must not
                // end the pass over the other six hundred.
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

    /**
     * Localise native-heap growth by stage. Temporary diagnostic.
     *
     * Reusing embed()'s direct ByteBuffer did not stop the heap climbing, so
     * stop guessing and bisect: run the same images through progressively more
     * of the pipeline and watch where the growth appears.
     *
     *   1 decode + recycle only
     *   2 decode + detect + align + sharpness, but NEVER embed -- an impossible
     *     minOrigPx gate fails every face, so detection still runs
     *   3 decode + detect + embed
     *   4 decode + detect + embed + text recognition
     */
    fun memProbe(ctx: Context, stage: Int, n: Int) {
        val db = Db(ctx)
        val uris = db.unscoredItems(10_000).take(n).map { it.second }
        db.close()
        val centroids = emptyMap<Long, List<FloatArray>>()
        val textRec = if (stage >= 4)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) else null
        val gate = if (stage == 2) 999_999 else FaceEngine.MIN_ORIG_PX
        val base = android.os.Debug.getNativeHeapAllocatedSize()
        Log.i(TAG, "MEMPROBE stage=$stage n=${uris.size} gate=$gate base_kb=${base / 1024}")

        FaceEngine(ctx, minOrigPx = gate).use { eng ->
            var done = 0
            for (u in uris) {
                try {
                    val img = eng.decode(ctx, u.toUri()) ?: continue
                    if (stage >= 2) {
                        val obs = eng.observe(img)
                        for (o in obs) eng.match(o, centroids)
                    }
                    if (textRec != null) {
                        Tasks.await(textRec.process(InputImage.fromBitmap(img.bitmap, 0)))
                    }
                    img.bitmap.recycle()
                } catch (e: Exception) { /* a dead row is not what we are measuring */ }
                done++
                if (done % 25 == 0) {
                    val now = android.os.Debug.getNativeHeapAllocatedSize()
                    Log.i(TAG, "MEMPROBE stage=$stage done=$done " +
                        "native_kb=${now / 1024} delta_kb=${(now - base) / 1024} " +
                        "per_img_kb=${(now - base) / 1024 / done}")
                }
            }
        }
        textRec?.close()
        val end = android.os.Debug.getNativeHeapAllocatedSize()
        Log.i(TAG, "MEMPROBE stage=$stage DONE native_kb=${end / 1024} " +
            "delta_kb=${(end - base) / 1024}")
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
        db.inTransaction {
            for ((root, members) in groups) {
                if (members.size < 2) continue
                multi++; extra += members.size - 1
                for (m in members) db.setDupeGroup(m, root)
            }
        }
        Log.i(TAG, "DUPES groups=$multi redundant_items=$extra (hamming<=$DUPE_HAMMING)")
    }

    private fun dhash(bmp: Bitmap): Long {
        val small = bmp.scale(9, 8, true)
        var h = 0L; var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val p1 = small[x, y]; val p2 = small[x + 1, y]
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
