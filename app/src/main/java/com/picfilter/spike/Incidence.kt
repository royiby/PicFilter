package com.picfilter.spike

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

private const val TAG = "PICFILTER"

/**
 * The decisive number for small-face policy: not "what % of FACES are too small"
 * (measured: 32%) but "what % of IMAGES contain at least one face too small to
 * identify" — and specifically among crowd images, which are the discard
 * candidates. If that is high, "indeterminate blocks auto-discard" silently
 * disables auto-discard entirely.
 *
 * Detection-only (no embedding), and face sizes are recorded in ORIGINAL-image
 * pixels so one pass yields the incidence curve for every candidate threshold.
 */
object Incidence {

    private const val TARGET_PX = 1000
    private val THRESHOLDS = intArrayOf(30, 40, 50, 60, 80, 100)

    fun run(ctx: Context, limit: Int) {
        val det = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setMinFaceSize(0.02f)       // deliberately permissive: we want the tail
                .build()
        )
        val uris = waUris(ctx, limit)
        Log.i(TAG, "INCIDENCE candidates=${uris.size} (detection only, min_face=2%)")

        // per image: list of face sizes in ORIGINAL pixels
        val perImage = ArrayList<IntArray>()
        var failed = 0
        val t0 = System.currentTimeMillis()

        for (u in uris) {
            try {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val i1 = ctx.contentResolver.openInputStream(u) ?: continue
                i1.use { BitmapFactory.decodeStream(it, null, b) }
                val maxDim = maxOf(b.outWidth, b.outHeight)
                if (maxDim <= 0) { failed++; continue }
                var scale = 1
                while (maxDim / (scale * 2) >= TARGET_PX) scale *= 2
                val i2 = ctx.contentResolver.openInputStream(u) ?: continue
                val bmp = i2.use {
                    BitmapFactory.decodeStream(it, null,
                        BitmapFactory.Options().apply { inSampleSize = scale })
                } ?: run { failed++; null } ?: continue

                val faces = Tasks.await(det.process(InputImage.fromBitmap(bmp, 0)))
                // decoded px * inSampleSize == original px
                val sizes = IntArray(faces.size) { k ->
                    val box = faces[k].boundingBox
                    minOf(box.width(), box.height()) * scale
                }
                perImage += sizes
                bmp.recycle()
            } catch (e: Exception) { failed++ }
        }
        val wall = System.currentTimeMillis() - t0
        val allFaces = perImage.sumOf { it.size }
        Log.i(TAG, "INCIDENCE images=${perImage.size} failed=$failed faces=$allFaces wall_ms=$wall")

        // face-size histogram in original pixels
        val edges = intArrayOf(0, 20, 40, 60, 80, 120, 200, Int.MAX_VALUE)
        val hist = IntArray(edges.size - 1)
        for (sizes in perImage) for (s in sizes) {
            for (i in 0 until edges.size - 1) if (s >= edges[i] && s < edges[i + 1]) { hist[i]++; break }
        }
        for (i in hist.indices) {
            val hi = if (edges[i + 1] == Int.MAX_VALUE) "inf" else edges[i + 1].toString()
            Log.i(TAG, "INCIDENCE size_px[${edges[i]}..$hi) = ${hist[i]} (${pct(hist[i], allFaces)})")
        }

        val crowds = perImage.filter { it.size >= 3 }
        val mids   = perImage.filter { it.size in 1..2 }
        Log.i(TAG, "INCIDENCE buckets: f0=${perImage.count { it.isEmpty() }} f1-2=${mids.size} f3plus=${crowds.size}")

        // the incidence curve
        for (t in THRESHOLDS) {
            val facesBelow = perImage.sumOf { sizes -> sizes.count { it < t } }
            val imgsAny    = perImage.count { sizes -> sizes.any { it < t } }
            val crowdAny   = crowds.count { sizes -> sizes.any { it < t } }
            val crowdAll   = crowds.count { sizes -> sizes.all { it < t } }
            val midAny     = mids.count { sizes -> sizes.any { it < t } }
            Log.i(TAG, "INCIDENCE T=$t faces_below=${pct(facesBelow, allFaces)} " +
                "images_any_indet=${pct(imgsAny, perImage.size)} " +
                "crowd_any_indet=${pct(crowdAny, crowds.size)} " +
                "crowd_all_indet=${pct(crowdAll, crowds.size)} " +
                "mid_any_indet=${pct(midAny, mids.size)}")
        }
        det.close()
    }

    private fun pct(n: Int, d: Int) = if (d == 0) "n/a" else "$n/$d=${"%.1f".format(n * 100.0 / d)}%"

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
