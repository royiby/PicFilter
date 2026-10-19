package com.picfilter.spike.engine

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.picfilter.spike.data.Db
import java.io.File
import java.io.FileOutputStream

private const val TAG = "PICFILTER"

/**
 * Onboarding bootstrap: sample the library, embed faces under the STRICT
 * enrolment gate, cluster them, and persist the clusters as named-person
 * candidates with thumbnails.
 *
 * Validated on a real library (SPIKES.md): 609 images -> 953 faces -> a clear
 * recurring-people head (92,48,46,45,42,38,34) over a long singleton tail, with
 * the top 10 clusters covering 43% of all faces. That head is the candidate list.
 *
 * The strict gate matters: admitting small faces is precisely what produced a
 * single 205-face garbage cluster, because tiny blurry faces all look alike to a
 * 192-d model.
 */
object Clusterer {

    /** Measured: ~600 images is enough to surface the head, at ~80s. */
    const val SAMPLE_SIZE = 600
    private const val THUMB_DIR = "face_thumbs"
    private const val PERSON_DIR = "person_faces"

    fun interface Progress { fun onProgress(done: Int, total: Int, phase: String) }

    fun bootstrap(ctx: Context, sample: Int = SAMPLE_SIZE, progress: Progress? = null) {
        val db = Db(ctx)
        db.clearClusters()
        clearThumbs(ctx)

        val uris = sampleUris(ctx, sample)
        Log.i(TAG, "BOOTSTRAP sampling=${uris.size} images (strict gate)")

        val vecs = ArrayList<FloatArray>()
        val meta = ArrayList<Triple<Long, String?, Int>>()   // itemId, thumbPath, origPx
        var faces = 0
        val t0 = System.currentTimeMillis()

        FaceEngine(
            ctx,
            targetPx = 1200,
            minOrigPx = FaceEngine.ENROL_MIN_ORIG_PX,
            maxPose = FaceEngine.ENROL_MAX_POSE,
        ).use { eng ->
            for ((i, u) in uris.withIndex()) {
                progress?.onProgress(i, uris.size, "Looking through your photos")
                try {
                    val img = eng.decode(ctx, u) ?: continue
                    val obs = eng.observe(img, keepCrop = true)
                    img.bitmap.recycle()
                    for (o in obs) {
                        val e = o.embedding
                        val crop = o.crop
                        if (e == null || crop == null) { crop?.recycle(); continue }
                        val id = ContentUris.parseId(u)
                        val path = saveThumb(ctx, crop, "${id}_${faces}")
                        crop.recycle()
                        vecs += e
                        meta += Triple(id, path, o.origPx)
                        faces++
                    }
                } catch (e: Exception) { /* dead row or unreadable; skip */ }
            }
        }
        Log.i(TAG, "BOOTSTRAP faces=$faces in ${System.currentTimeMillis() - t0}ms")

        if (vecs.isEmpty()) {
            Log.w(TAG, "BOOTSTRAP no usable faces — nothing to enrol")
            db.close(); return
        }

        progress?.onProgress(0, 1, "Grouping people")
        val assign = clusterAverage(vecs, FaceEngine.CLUSTER_THRESHOLD)
        persist(db, assign, vecs, meta)
        db.close()
    }

    /**
     * Average-linkage agglomerative clustering.
     *
     * The greedy variant below merges against a RUNNING CENTROID, which chains:
     * A joins B, the centroid drifts toward B, C joins the drifted centroid, and
     * two different people end up merged — while the same drift pushes genuine
     * matches below threshold and splits others. The user saw both failures at
     * once, which is the signature of chaining rather than a mis-set threshold.
     *
     * Average linkage compares a candidate against the MEAN SIMILARITY TO EVERY
     * MEMBER of a cluster, so one drifting member cannot drag the whole group.
     * Lance-Williams update keeps it O(n^2) rather than recomputing pairs.
     */
    fun clusterAverage(vecs: List<FloatArray>, threshold: Float): IntArray {
        val n = vecs.size
        if (n == 0) return IntArray(0)
        val sim = FloatArray(n * n)
        for (i in 0 until n) {
            sim[i * n + i] = Float.NEGATIVE_INFINITY   // never merge with self
            for (j in i + 1 until n) {
                val s = cos(vecs[i], vecs[j])
                sim[i * n + j] = s; sim[j * n + i] = s
            }
        }
        val active = BooleanArray(n) { true }
        val size = IntArray(n) { 1 }
        val label = IntArray(n) { it }          // union-find style representative

        while (true) {
            var bi = -1; var bj = -1; var best = threshold
            for (i in 0 until n) {
                if (!active[i]) continue
                val row = i * n
                for (j in i + 1 until n) {
                    if (!active[j]) continue
                    val s = sim[row + j]
                    if (s >= best) { best = s; bi = i; bj = j }
                }
            }
            if (bi < 0) break

            // merge bj into bi, average-linkage (Lance-Williams)
            val ni = size[bi]; val nj = size[bj]
            for (k in 0 until n) {
                if (!active[k] || k == bi || k == bj) continue
                val merged = (ni * sim[bi * n + k] + nj * sim[bj * n + k]) / (ni + nj)
                sim[bi * n + k] = merged; sim[k * n + bi] = merged
            }
            size[bi] = ni + nj
            active[bj] = false
            for (x in 0 until n) if (label[x] == bj) label[x] = bi
        }

        // compact labels to 0..k-1
        val remap = HashMap<Int, Int>()
        val out = IntArray(n)
        for (i in 0 until n) out[i] = remap.getOrPut(label[i]) { remap.size }
        val counts = HashMap<Int, Int>()
        for (a in out) counts[a] = (counts[a] ?: 0) + 1
        Log.i(TAG, "AVGLINK t=$threshold clusters=${remap.size} " +
            "singletons=${counts.values.count { it == 1 }} " +
            "top10=${counts.values.sortedDescending().take(10).joinToString(",")}")
        return out
    }

    /** Greedy agglomerative against running centroids; O(n * clusters). */
    private fun cluster(vecs: List<FloatArray>, threshold: Float): IntArray {
        val centroids = ArrayList<FloatArray>()
        val sizes = ArrayList<Int>()
        val assign = IntArray(vecs.size)
        for ((i, e) in vecs.withIndex()) {
            var best = -1; var bestSim = -1f
            for (k in centroids.indices) {
                val s = cos(e, centroids[k])
                if (s > bestSim) { bestSim = s; best = k }
            }
            if (best >= 0 && bestSim >= threshold) {
                val c = centroids[best]; val n = sizes[best]
                for (j in c.indices) c[j] = (c[j] * n + e[j]) / (n + 1)
                l2(c); sizes[best] = n + 1
                assign[i] = best
            } else {
                centroids += e.copyOf(); sizes += 1
                assign[i] = centroids.size - 1
            }
        }
        Log.i(TAG, "BOOTSTRAP clusters=${centroids.size} " +
            "top=${sizes.sortedDescending().take(10).joinToString(",")}")
        return assign
    }

    private fun persist(
        db: Db, assign: IntArray, vecs: List<FloatArray>,
        meta: List<Triple<Long, String?, Int>>,
    ) {
        val members = HashMap<Int, MutableList<Int>>()
        for (i in assign.indices) members.getOrPut(assign[i]) { mutableListOf() } += i
        var kept = 0
        var singles = 0
        for ((_, idxs) in members.entries.sortedByDescending { it.value.size }) {
            // Singletons are noise for ONBOARDING, but they must still be STORED:
            // recluster() rebuilds from cluster_face, so dropping them here erodes
            // the face set a little more on every regroup. Store all, surface some.
            if (idxs.size < 2) singles++
            val dim = vecs[0].size
            val centroid = FloatArray(dim)
            for (i in idxs) for (j in 0 until dim) centroid[j] += vecs[i][j]
            for (j in 0 until dim) centroid[j] /= idxs.size
            l2(centroid)
            val cid = db.addCluster(idxs.size, centroid)
            for (i in idxs) {
                val (itemId, thumb, px) = meta[i]
                db.addClusterFace(cid, itemId, thumb, px, vecs[i])
            }
            if (idxs.size >= 2) kept++
        }
        Log.i(TAG, "BOOTSTRAP persisted=$kept multi-face clusters, $singles singletons " +
            "(singletons stored but not offered as candidates)")
    }

    /**
     * Re-group already-embedded faces at a different threshold. Instant — the
     * expensive part (decode + detect + embed, ~4 min) is not repeated.
     */
    fun recluster(ctx: Context, threshold: Float) {
        val db = Db(ctx)
        val faces = db.allClusterFaces()
        if (faces.isEmpty()) { Log.w(TAG, "RECLUSTER nothing stored — run bootstrap first"); db.close(); return }
        val vecs = faces.map { it.vec }
        val assign = clusterAverage(vecs, threshold)
        db.clearClusters()
        val meta = faces.map { Triple(it.itemId ?: -1L, it.thumb, it.origPx) }
        persist(db, assign, vecs, meta)
        Log.i(TAG, "RECLUSTER threshold=$threshold over ${faces.size} stored faces")
        db.close()
    }

    /** How does cluster structure respond to the threshold? No re-scan needed. */
    fun sweepThresholds(ctx: Context) {
        val db = Db(ctx)
        val faces = db.allClusterFaces()
        if (faces.isEmpty()) { Log.w(TAG, "SWEEPCL nothing stored — run bootstrap first"); db.close(); return }
        val vecs = faces.map { it.vec }
        Log.i(TAG, "SWEEPCL faces=${vecs.size}")
        for (t in floatArrayOf(0.45f, 0.50f, 0.55f, 0.60f, 0.65f, 0.70f, 0.75f, 0.80f)) {
            clusterAverage(vecs, t)      // logs its own AVGLINK line for comparison
            val assign = cluster(vecs, t)
            val sizes = HashMap<Int, Int>()
            for (a in assign) sizes[a] = (sizes[a] ?: 0) + 1
            val sorted = sizes.values.sortedDescending()
            val singles = sorted.count { it == 1 }
            Log.i(TAG, "SWEEPCL t=$t clusters=${sorted.size} singletons=$singles " +
                "largest=${sorted.firstOrNull() ?: 0} " +
                "(${"%.1f".format((sorted.firstOrNull() ?: 0) * 100.0 / vecs.size)}% of faces) " +
                "top10=${sorted.take(10).joinToString(",")}")
        }
        db.close()
    }

    // ---------------------------------------------------------------- misc

    /**
     * WhatsApp has used several folder shapes over the years — Android/media/
     * com.whatsapp/..., Pictures/WhatsApp, a bare WhatsApp/Media tree — and the
     * bucket names differ again ("WhatsApp Images", "Whatsapp"). Matching the
     * word in either covers all of them, and nothing else on a phone is called
     * WhatsApp. Matching only "com.whatsapp", as the spike did, misses the
     * Pictures/Whatsapp folder that a restore or a file manager leaves behind.
     */
    fun isWhatsAppFolder(relativePath: String?, bucket: String?): Boolean {
        val p = relativePath?.lowercase() ?: ""
        val b = bucket?.lowercase() ?: ""
        return p.contains("whatsapp") || b.contains("whatsapp")
    }

    /**
     * How many photos sit in the WhatsApp folders, and a few of them to show.
     * Onboarding opens with the user's own pictures rather than a paragraph
     * about them: the point lands faster when it is their group chat on screen.
     */
    fun whatsAppPreview(ctx: Context, previews: Int): Pair<Int, List<Uri>> {
        var count = 0
        val sample = mutableListOf<Uri>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            ), null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                if (!isWhatsAppFolder(c.getString(iPath), c.getString(iBucket))) continue
                count++
                if (sample.size < previews) sample += ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(iId))
            }
        }
        return count to sample
    }

    /**
     * Onboarding samples the WhatsApp folders first. Those are the photos
     * PixKin will be judging, so the people who keep appearing there are the
     * people worth enrolling — a colleague who only shows up in camera photos
     * is not what this question is about.
     *
     * Camera photos are still better enrolment material face for face (closer,
     * sharper, more frontal), so they top the sample up when the WhatsApp
     * folders cannot fill it on their own.
     */
    private fun sampleUris(ctx: Context, limit: Int): List<Uri> {
        val wa = mutableListOf<Uri>()
        val rest = mutableListOf<Uri>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            ), null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                if (wa.size >= limit) break
                val u = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(iId))
                if (isWhatsAppFolder(c.getString(iPath), c.getString(iBucket))) wa += u
                else if (rest.size < limit) rest += u
            }
        }
        val topUp = if (wa.size >= limit) emptyList() else rest.take(limit - wa.size)
        Log.i(TAG, "BOOTSTRAP sample whatsapp=${wa.size} other=${topUp.size}")
        return wa + topUp
    }

    private fun thumbDir(ctx: Context) = File(ctx.filesDir, THUMB_DIR).apply { mkdirs() }

    private fun personDir(ctx: Context) = File(ctx.filesDir, PERSON_DIR).apply { mkdirs() }

    /**
     * An enrolled person's own face crops, biggest first.
     *
     * The cluster crops cannot serve this: every scan calls clearClusters and
     * clearThumbs, so re-running setup would leave everyone who was already
     * enrolled with no face to show. Enrolment takes its own copies.
     */
    fun personFaces(ctx: Context, personId: Long): List<String> =
        personDir(ctx).listFiles { f -> f.name.startsWith("${personId}_") }
            ?.sortedBy { it.name }
            ?.map { it.absolutePath }
            ?: emptyList()

    /**
     * Give an already-enrolled person their face back after a re-scan.
     *
     * Running setup again clears every cluster, which orphans the crops the
     * people row shows. The person's centroid still describes the same face,
     * so match it against the new clusters and borrow their crops. Anyone with
     * no close cluster keeps their initials.
     */
    fun relinkFaces(ctx: Context): Int {
        val db = Db(ctx)
        val clusters = db.clusterCentroids()
        var fixed = 0
        if (clusters.isNotEmpty()) {
            for ((pid, vecs) in db.centroidsByPerson()) {
                if (personFaces(ctx, pid).isNotEmpty()) continue
                var best = -1f
                var bestId = -1L
                for ((cid, cvec) in clusters) {
                    val sim = vecs.maxOf { cos(it, cvec) }
                    if (sim > best) { best = sim; bestId = cid }
                }
                if (bestId > 0 && best >= 0.6f) {
                    keepPersonFaces(ctx, pid, bestId, db)
                    fixed++
                    Log.i(TAG, "RELINK person=$pid cluster=$bestId sim=${"%.3f".format(best)}")
                }
            }
        }
        db.close()
        return fixed
    }

    private fun keepPersonFaces(ctx: Context, personId: Long, clusterId: Long, db: Db) {
        personDir(ctx).listFiles { f -> f.name.startsWith("${personId}_") }
            ?.forEach { it.delete() }
        for ((i, src) in db.clusterThumbs(clusterId, 6).withIndex()) {
            try {
                File(src).copyTo(File(personDir(ctx), "${personId}_$i.jpg"), overwrite = true)
            } catch (e: Exception) {
                Log.w(TAG, "PERSONFACE copy failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun saveThumb(ctx: Context, bmp: Bitmap, name: String): String? = try {
        val f = File(thumbDir(ctx), "$name.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        f.absolutePath
    } catch (e: Exception) { null }

    fun clearThumbs(ctx: Context) {
        thumbDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /**
     * Turn a named cluster into an enrolled person. Stores the cluster centroid
     * plus, when the cluster is large enough, a second centroid from its most
     * distinct half — matching takes the MAX over a person's centroids, so extra
     * vectors only ever help recall.
     */
    fun enrolCluster(ctx: Context, clusterId: Long, name: String) {
        val db = Db(ctx)
        val vecs = db.clusterVectors(clusterId)
        if (vecs.isEmpty()) { db.close(); return }
        val dim = vecs[0].size
        val pid = db.upsertPerson(name)

        val centroid = FloatArray(dim)
        for (v in vecs) for (j in 0 until dim) centroid[j] += v[j]
        for (j in 0 until dim) centroid[j] /= vecs.size
        l2(centroid)
        db.addCentroid(pid, centroid, vecs.size)

        if (vecs.size >= 6) {
            val far = vecs.sortedBy { cos(it, centroid) }.take(vecs.size / 3)
            if (far.size >= 2) {
                val second = FloatArray(dim)
                for (v in far) for (j in 0 until dim) second[j] += v[j]
                for (j in 0 until dim) second[j] /= far.size
                l2(second)
                db.addCentroid(pid, second, far.size)
                Log.i(TAG, "ENROL '$name' centroids=2 (main=${vecs.size}, outlier=${far.size})")
            }
        } else {
            Log.i(TAG, "ENROL '$name' centroids=1 (${vecs.size} faces)")
        }
        db.setClusterStatus(clusterId, "NAMED", pid)
        keepPersonFaces(ctx, pid, clusterId, db)
        db.close()
    }
}
