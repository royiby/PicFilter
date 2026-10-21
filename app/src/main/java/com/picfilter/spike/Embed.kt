package com.picfilter.spike

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.core.graphics.scale

private const val TAG = "PICFILTER"

/**
 * Label-free validation of the embedding model: embed every detected face in the
 * real WhatsApp library, cluster by cosine similarity, and report the cluster
 * size distribution. Meaningful embeddings => a few large recurring clusters.
 * Reports aggregates only — no crops, no identities, no filenames.
 */
object Embed {

    private const val IN = 112
    private var TARGET_PX = 1000
    private var MIN_BOX = 24
    private const val SIM_THRESHOLD = 0.55f   // cosine; tune later against labels

    fun run(ctx: Context, limit: Int, targetPx: Int = 1000, minBox: Int = 24) {
        TARGET_PX = targetPx; MIN_BOX = minBox
        Log.i(TAG, "EMBED config target_px=$TARGET_PX min_box=$MIN_BOX")
        val model = ctx.assets.openFd("MobileFaceNet.tflite").use { fd ->
            fd.createInputStream().use { it.readBytes() }
        }
        val buf = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder())
        buf.put(model); buf.rewind()
        val interp = Interpreter(buf, Interpreter.Options().apply { numThreads = 4 })

        val inShape = interp.getInputTensor(0).shape()
        val outShape = interp.getOutputTensor(0).shape()
        Log.i(TAG, "MODEL in=${inShape.joinToString("x")} inType=${interp.getInputTensor(0).dataType()}")
        Log.i(TAG, "MODEL out=${outShape.joinToString("x")} outType=${interp.getOutputTensor(0).dataType()}")
        val dim = outShape.last()
        val batch = inShape[0]
        Log.i(TAG, "MODEL using batch=$batch dim=$dim (duplicating face into every slot)")

        val det = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.05f).build()
        )

        val uris = waUris(ctx, limit)
        Log.i(TAG, "EMBED candidates=${uris.size} dim=$dim")

        val embeddings = ArrayList<FloatArray>()
        var imgs = 0; var faces = 0; var skipped = 0
        var embedMs = 0L
        val t0 = System.currentTimeMillis()

        for (u in uris) {
            try {
                val bmp = decode(ctx, u) ?: continue
                imgs++
                val found = Tasks.await(det.process(InputImage.fromBitmap(bmp, 0)))
                for (f in found) {
                    val crop = align(bmp, f) ?: run {
                        skipped++
                        if (skipped <= 5) Log.w(TAG, "EMBED align_null box=${f.boundingBox.width()}x${f.boundingBox.height()} " +
                            "leftEye=${f.getLandmark(FaceLandmark.LEFT_EYE) != null} rightEye=${f.getLandmark(FaceLandmark.RIGHT_EYE) != null}")
                        null
                    } ?: continue
                    val t = System.nanoTime()
                    val e = embed(interp, crop, dim, batch)
                    embedMs += (System.nanoTime() - t) / 1_000_000
                    crop.recycle()
                    embeddings += e
                    faces++
                }
                bmp.recycle()
            } catch (e: Exception) {
                skipped++
                if (skipped <= 5) Log.w(TAG, "EMBED img_exception ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        val wall = System.currentTimeMillis() - t0
        Log.i(TAG, "EMBED images=$imgs faces=$faces skipped=$skipped wall_ms=$wall")
        Log.i(TAG, "EMBED avg_embed_ms=${if (faces>0) embedMs/faces else 0} faces_per_s=${"%.1f".format(faces*1000.0/maxOf(wall,1))}")

        if (embeddings.size < 2) { Log.w(TAG, "EMBED too few faces to cluster"); interp.close(); return }

        // greedy agglomerative clustering by cosine similarity to cluster centroid
        val centroids = ArrayList<FloatArray>()
        val sizes = ArrayList<Int>()
        for (e in embeddings) {
            var best = -1; var bestSim = -1f
            for (i in centroids.indices) {
                val s = cos(e, centroids[i])
                if (s > bestSim) { bestSim = s; best = i }
            }
            if (best >= 0 && bestSim >= SIM_THRESHOLD) {
                val c = centroids[best]; val n = sizes[best]
                for (k in c.indices) c[k] = (c[k] * n + e[k]) / (n + 1)
                l2(c); sizes[best] = n + 1
            } else { centroids += e.copyOf(); sizes += 1 }
        }

        val sorted = sizes.sortedDescending()
        Log.i(TAG, "CLUSTER total_faces=${embeddings.size} clusters=${sizes.size} threshold=$SIM_THRESHOLD")
        Log.i(TAG, "CLUSTER top20_sizes=${sorted.take(20).joinToString(",")}")
        val singles = sorted.count { it == 1 }
        val top10 = sorted.take(10).sum()
        Log.i(TAG, "CLUSTER singletons=$singles (${"%.1f".format(singles*100.0/sizes.size)}% of clusters)")
        Log.i(TAG, "CLUSTER top10_covers=$top10/${embeddings.size} faces (${"%.1f".format(top10*100.0/embeddings.size)}%)")
        interp.close(); det.close()
    }

    /**
     * Validates interpreter plumbing with no faces required: feeds two different
     * synthetic 112x112 images through the batch-2 model and checks the output is
     * a finite, non-degenerate 192-d vector. This is exactly what the failed
     * resizeInput broke.
     */
    fun modelCheck(ctx: Context) {
        val model = ctx.assets.openFd("MobileFaceNet.tflite").use { fd ->
            fd.createInputStream().use { it.readBytes() }
        }
        val buf = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder())
        buf.put(model); buf.rewind()
        val interp = Interpreter(buf, Interpreter.Options().apply { numThreads = 4 })
        val inShape = interp.getInputTensor(0).shape()
        val dim = interp.getOutputTensor(0).shape().last()
        val batch = inShape[0]
        Log.i(TAG, "CHECK in=${inShape.joinToString("x")} out_dim=$dim batch=$batch")

        fun synth(seed: Int): Bitmap {
            val bm = createBitmap(IN, IN, Bitmap.Config.ARGB_8888)
            for (y in 0 until IN) for (x in 0 until IN) {
                val v = ((x * 7 + y * 13 + seed * 53) % 256)
                bm[x, y] = (0xFF shl 24) or (v shl 16) or ((255 - v) shl 8) or ((v * 2) % 256)
            }
            return bm
        }

        try {
            val a = embed(interp, synth(1), dim, batch)
            val b = embed(interp, synth(99), dim, batch)
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE; var sum = 0f
            var nonFinite = 0; var nonZero = 0
            for (v in a) {
                if (!v.isFinite()) nonFinite++
                if (v != 0f) nonZero++
                if (v < mn) mn = v; if (v > mx) mx = v; sum += v
            }
            var norm = 0f; for (v in a) norm += v * v
            Log.i(TAG, "CHECK vecA dim=${a.size} nonfinite=$nonFinite nonzero=$nonZero " +
                "min=${"%.4f".format(mn)} max=${"%.4f".format(mx)} mean=${"%.4f".format(sum/a.size)} l2=${"%.4f".format(norm)}")
            Log.i(TAG, "CHECK cos(A,A)=${"%.4f".format(cos(a,a))} cos(A,B)=${"%.4f".format(cos(a,b))}")
            Log.i(TAG, "CHECK first8_A=" + a.take(8).joinToString(",") { "%.4f".format(it) })
            Log.i(TAG, "CHECK first8_B=" + b.take(8).joinToString(",") { "%.4f".format(it) })
            val ok = nonFinite == 0 && nonZero > dim / 2 && kotlin.math.abs(norm - 1f) < 0.01f &&
                     cos(a, b) < 0.999f
            Log.i(TAG, "CHECK PLUMBING_OK=$ok")
        } catch (e: Exception) {
            Log.e(TAG, "CHECK FAILED ${e.javaClass.simpleName}: ${e.message}")
        }
        interp.close()
    }

    // ---- 5-point-ish alignment using the two eyes ----
    private fun align(src: Bitmap, f: Face): Bitmap? {
        val le = f.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val re = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val box = f.boundingBox
        if (box.width() < MIN_BOX || box.height() < MIN_BOX) return null
        return try {
            if (le != null && re != null) {
                val dx = re.x - le.x; val dy = re.y - le.y
                val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                val eyeDist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (eyeDist < 8f) return null
                val cx = (le.x + re.x) / 2f; val cy = (le.y + re.y) / 2f
                // MobileFaceNet convention: inter-eye ~ 0.33 of 112px crop
                val scale = (IN * 0.33f) / eyeDist
                val m = Matrix().apply {
                    postTranslate(-cx, -cy)
                    postRotate(-angle)
                    postScale(scale, scale)
                    postTranslate(IN / 2f, IN * 0.42f)  // eyes slightly above centre
                }
                val out = createBitmap(IN, IN, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(out).drawBitmap(src, m, null)
                out
            } else {
                val x = box.left.coerceAtLeast(0); val y = box.top.coerceAtLeast(0)
                val w = minOf(box.width(), src.width - x); val h = minOf(box.height(), src.height - y)
                if (w <= 0 || h <= 0) return null
                Bitmap.createBitmap(src, x, y, w, h).scale(IN, IN)
            }
        } catch (e: Exception) { null }
    }

    private fun embed(interp: Interpreter, bmp: Bitmap, dim: Int, batch: Int): FloatArray {
        val input = ByteBuffer.allocateDirect(batch * IN * IN * 3 * 4).order(ByteOrder.nativeOrder())
        val px = IntArray(IN * IN)
        bmp.getPixels(px, 0, IN, 0, 0, IN, IN)
        // this model's input batch is 2 (it was published for pairwise comparison);
        // fill every slot with the same face and read slot 0.
        repeat(batch) {
            for (p in px) {
                input.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
        }
        input.rewind()
        val out = Array(batch) { FloatArray(dim) }
        interp.run(input, out)
        val e = out[0]; l2(e); return e
    }

    private fun l2(v: FloatArray) {
        var s = 0f; for (x in v) s += x * x
        val n = sqrt(s.toDouble()).toFloat().coerceAtLeast(1e-9f)
        for (i in v.indices) v[i] /= n
    }

    private fun cos(a: FloatArray, b: FloatArray): Float {
        var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s
    }

    private fun decode(ctx: Context, u: Uri): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val ins = ctx.contentResolver.openInputStream(u) ?: return null
        ins.use { BitmapFactory.decodeStream(it, null, b) }   // returns null by design
        val maxDim = maxOf(b.outWidth, b.outHeight)
        if (maxDim <= 0) return null
        var scale = 1
        while (maxDim / (scale * 2) >= TARGET_PX) scale *= 2
        val ins2 = ctx.contentResolver.openInputStream(u) ?: return null
        return ins2.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = scale })
        }
    }

    private fun waUris(ctx: Context, limit: Int): List<Uri> {
        val out = mutableListOf<Uri>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("%com.whatsapp%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val i = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (c.moveToNext() && out.size < limit) {
                out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(i))
            }
        }
        return out
    }
}
