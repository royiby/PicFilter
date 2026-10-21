package com.picfilter.spike.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

private const val TAG = "PICFILTER"

enum class MatchState { MATCH, NO_MATCH, INDETERMINATE }

data class FaceObs(
    val origPx: Int,
    val sharpness: Double,
    val yaw: Float,
    val roll: Float,
    val embedding: FloatArray?,   // null when quality bar not met
    val quality: Boolean,
    /** Aligned 112x112 crop, only retained when keepCrop was requested. */
    val crop: android.graphics.Bitmap? = null,
)

data class DecodedImage(val bitmap: Bitmap, val sampleSize: Int, val origW: Int, val origH: Int)

/**
 * Detect -> align -> quality-gate -> embed.
 *
 * Quality is judged on ORIGINAL-image pixels (scale-invariant; a decoded-pixel
 * gate silently changes meaning with decode resolution — see SPIKES.md 3c).
 * Gate measured at 40px in SPIKES.md 3d: 18.5% of faces indeterminate,
 * only 40.5% of crowd images affected.
 */
class FaceEngine(
    ctx: Context,
    private val targetPx: Int = 1000,
    private val minOrigPx: Int = MIN_ORIG_PX,
    private val maxPose: Float = MAX_POSE,
) : AutoCloseable {

    companion object {
        const val IN = 112

        // --- scoring gate: biased to RECALL. Every face must be judged, and a
        // face we cannot judge becomes INDETERMINATE, which forces review.
        const val MIN_ORIG_PX = 40
        const val MAX_POSE = 45f

        // --- enrolment gate: biased to PRECISION. Centroids are only as good as
        // the faces behind them, and admitting small faces is exactly what
        // produced the 205-face garbage cluster (SPIKES.md).
        const val ENROL_MIN_ORIG_PX = 80
        const val ENROL_MAX_POSE = 30f

        // MIN_SHARPNESS deleted: a 0->25 sweep moved 2 faces out of 1409 (SPIKES.md).
        // Sharpness is still measured and stored — it just never gated anything.
        /**
         * Raised 0.55 -> 0.70 after measuring precision ABOVE the threshold for
         * the first time: at 0.55, only 47% of "family" photos actually were
         * (67/142). Precision by band: 0.55-0.58 = 18%, 0.58-0.61 = 10%,
         * 0.61-0.65 = 35%, 0.65-0.70 = 42%, 0.70-0.80 = 82%, 0.80+ = 93%.
         * Everything below 0.70 is a coin flip or worse. Per-person thresholds
         * (fitted from the same labels) override this where a centroid is
         * reliable enough to justify a lower bar.
         */
        const val MATCH_THRESHOLD = 0.70f

        /**
         * Average-linkage threshold. Lower than the greedy value it replaces,
         * because average linkage does not chain: greedy merged against a
         * drifting running centroid, which simultaneously over-merged some
         * people and split others (the user observed both at once).
         *
         * Measured on a real 433-face library, avg-linkage @0.60 DOMINATES
         * greedy @0.65 on both axes: largest cluster 13 vs 18 (less merging)
         * and 79 vs 93 singletons (less fragmentation). See SPIKES.md.
         */
        const val CLUSTER_THRESHOLD = 0.60f
    }

    private val interp: Interpreter
    private val batch: Int
    val dim: Int

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.02f)
            .build()
    )

    init {
        val bytes = ctx.assets.openFd("MobileFaceNet.tflite").use { fd ->
            fd.createInputStream().use { it.readBytes() }
        }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buf.put(bytes); buf.rewind()
        interp = Interpreter(buf, Interpreter.Options().apply { numThreads = 4 })
        // model was published for pairwise comparison: input batch is fixed at 2
        batch = interp.getInputTensor(0).shape()[0]
        dim = interp.getOutputTensor(0).shape().last()
        Log.i(TAG, "ENGINE model batch=$batch dim=$dim gate=${minOrigPx}px/${maxPose.toInt()}deg")
    }

    // Scratch, allocated once per engine rather than once per face.
    //
    // embed() used to allocate a fresh 294 KB direct ByteBuffer per call, which
    // looked like the cause of the native heap climbing past 400 MB during a
    // library scan. It was not: reusing these buffers left the growth curve
    // unchanged (17 -> 167 -> 266 -> 389 -> 496 MB before, 18 -> 152 -> 267 ->
    // 366 -> 478 MB after, four passes in one process). A stage bisect put the
    // growth in ML Kit instead -- see Pipeline.memProbe. Embedding accounts for
    // under 1 MB per 100 images.
    //
    // Keeping the reuse anyway: it avoids ~400 MB of pointless allocation churn
    // across a scan, and it costs nothing. It does make an engine
    // single-threaded by contract, which it already was -- a TFLite Interpreter
    // is not thread-safe.
    private val inputBuf: ByteBuffer =
        ByteBuffer.allocateDirect(batch * IN * IN * 3 * 4).order(ByteOrder.nativeOrder())
    private val outBuf: Array<FloatArray> = Array(batch) { FloatArray(dim) }
    private val pxScratch = IntArray(IN * IN)
    private val lumaScratch = DoubleArray(IN * IN)

    fun decode(ctx: Context, u: Uri): DecodedImage? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val i1 = ctx.contentResolver.openInputStream(u) ?: return null
        i1.use { BitmapFactory.decodeStream(it, null, b) }   // returns null by design
        val maxDim = maxOf(b.outWidth, b.outHeight)
        if (maxDim <= 0) return null
        var s = 1
        while (maxDim / (s * 2) >= targetPx) s *= 2
        val i2 = ctx.contentResolver.openInputStream(u) ?: return null
        val bmp = i2.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        } ?: return null
        return DecodedImage(bmp, s, b.outWidth, b.outHeight)
    }

    fun observe(img: DecodedImage, keepCrop: Boolean = false): List<FaceObs> {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(img.bitmap, 0)))
        val out = ArrayList<FaceObs>(faces.size)
        for (f in faces) {
            val box = f.boundingBox
            val origPx = minOf(box.width(), box.height()) * img.sampleSize
            val crop = align(img.bitmap, f)
            if (crop == null) {
                out += FaceObs(origPx, 0.0, f.headEulerAngleY, f.headEulerAngleZ, null, false)
                continue
            }
            val sharp = sharpness(crop)
            val yaw = f.headEulerAngleY
            val roll = f.headEulerAngleZ
            val ok = origPx >= minOrigPx &&
                     abs(yaw) <= maxPose && abs(roll) <= maxPose
            val emb = if (ok) embed(crop) else null
            if (keepCrop && ok) {
                out += FaceObs(origPx, sharp, yaw, roll, emb, ok, crop)
            } else {
                crop.recycle()
                out += FaceObs(origPx, sharp, yaw, roll, emb, ok)
            }
        }
        return out
    }

    /**
     * Match against every centroid of every person; a person's score is the max
     * over their centroid set (supports multiple dated centroids per person).
     */
    fun match(
        obs: FaceObs,
        centroids: Map<Long, List<FloatArray>>,
        thresholds: Map<Long, Float> = emptyMap(),
    ): Triple<MatchState, Long?, Float?> = matchFace(obs, centroids, thresholds)

    // ---- alignment: falls back to a box crop rather than failing ----
    // The spike's align() returned null on missing landmarks / small eye distance,
    // which inflated the apparent small-face loss (SPIKES.md 3d correction).
    private fun align(src: Bitmap, f: Face): Bitmap? {
        val box = f.boundingBox
        val le = f.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val re = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        return try {
            if (le != null && re != null) {
                val dx = re.x - le.x; val dy = re.y - le.y
                val eyeDist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (eyeDist >= 4f) {
                    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    val cx = (le.x + re.x) / 2f; val cy = (le.y + re.y) / 2f
                    val scale = (IN * 0.33f) / eyeDist
                    val m = Matrix().apply {
                        postTranslate(-cx, -cy); postRotate(-angle)
                        postScale(scale, scale); postTranslate(IN / 2f, IN * 0.42f)
                    }
                    val outBmp = createBitmap(IN, IN, Bitmap.Config.ARGB_8888)
                    Canvas(outBmp).drawBitmap(src, m, null)
                    return outBmp
                }
            }
            boxCrop(src, box.left, box.top, box.width(), box.height())
        } catch (e: Exception) { null }
    }

    private fun boxCrop(src: Bitmap, l: Int, t: Int, w: Int, h: Int): Bitmap? {
        val x = l.coerceIn(0, maxOf(src.width - 1, 0))
        val y = t.coerceIn(0, maxOf(src.height - 1, 0))
        val ww = w.coerceAtMost(src.width - x); val hh = h.coerceAtMost(src.height - y)
        if (ww < 2 || hh < 2) return null
        val sub = Bitmap.createBitmap(src, x, y, ww, hh)
        val scaled = sub.scale(IN, IN, true)
        if (scaled !== sub) sub.recycle()
        return scaled
    }

    private fun embed(bmp: Bitmap): FloatArray {
        inputBuf.clear()
        bmp.getPixels(pxScratch, 0, IN, 0, 0, IN, IN)
        repeat(batch) {
            for (p in pxScratch) {
                inputBuf.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
                inputBuf.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
                inputBuf.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
        }
        inputBuf.rewind()
        interp.run(inputBuf, outBuf)
        // Copy before returning: outBuf is reused on the next call, and the
        // caller keeps this array for the life of the FaceObs.
        return outBuf[0].copyOf().also { l2(it) }
    }

    /** Variance of the Laplacian over the aligned crop (grayscale). */
    private fun sharpness(bmp: Bitmap): Double {
        val n = IN
        val px = pxScratch
        bmp.getPixels(px, 0, n, 0, 0, n, n)
        val g = lumaScratch
        for (i in px.indices) {
            val p = px[i]
            g[i] = 0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)
        }
        var sum = 0.0; var sumSq = 0.0; var count = 0
        for (y in 1 until n - 1) for (x in 1 until n - 1) {
            val i = y * n + x
            val lap = -4 * g[i] + g[i - 1] + g[i + 1] + g[i - n] + g[i + n]
            sum += lap; sumSq += lap * lap; count++
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    override fun close() { interp.close(); detector.close() }
}

/**
 * The three-state decision, lifted out of [FaceEngine] so it can be tested
 * without a TFLite interpreter: this is pure arithmetic, and it is the single
 * most consequential rule in the app -- it decides whose photos survive.
 */
fun matchFace(
    obs: FaceObs,
    centroids: Map<Long, List<FloatArray>>,
    thresholds: Map<Long, Float> = emptyMap(),
    default: Float = FaceEngine.MATCH_THRESHOLD,
): Triple<MatchState, Long?, Float?> {
    if (!obs.quality || obs.embedding == null) return Triple(MatchState.INDETERMINATE, null, null)
    if (centroids.isEmpty()) return Triple(MatchState.NO_MATCH, null, null)
    var bestPerson: Long? = null
    var best = -1f
    for ((pid, vecs) in centroids) for (v in vecs) {
        val s = cos(obs.embedding, v)
        if (s > best) { best = s; bestPerson = pid }
    }
    // Keep bestPerson even on a miss: a face at 0.52 against a known person is a
    // strong "probably family" hint for the rescue review, and it is exactly the
    // band where MATCH_THRESHOLD is doing contested work.
    val bar = thresholds[bestPerson] ?: default
    return if (best >= bar) Triple(MatchState.MATCH, bestPerson, best)
           else Triple(MatchState.NO_MATCH, bestPerson, best)
}

fun l2(v: FloatArray) {
    var s = 0f; for (x in v) s += x * x
    val n = sqrt(s.toDouble()).toFloat().coerceAtLeast(1e-9f)
    for (i in v.indices) v[i] /= n
}

fun cos(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size) return -1f
    var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s
}
