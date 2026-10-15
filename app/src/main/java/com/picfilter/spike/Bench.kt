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
import java.io.FileNotFoundException

private const val TAG = "PICFILTER"

/**
 * Read-only benchmark: how fast can we decode + count faces on real WhatsApp
 * media, and how discriminating is the "3+ faces" rule on this library?
 * Reports aggregates only — no filenames, no image content.
 */
object Bench {

    private const val TARGET_PX = 800      // downscale before detection
    private const val MIN_FACE_FRACTION = 0.04f

    fun run(ctx: Context, limit: Int) {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(MIN_FACE_FRACTION)
                .build()
        )

        val uris = waUris(ctx, limit)
        Log.i(TAG, "BENCH candidates=${uris.size} (limit=$limit)")
        if (uris.isEmpty()) { Log.w(TAG, "BENCH no whatsapp images found"); return }

        val hist = HashMap<Int, Int>()
        var ok = 0; var dead = 0; var failed = 0
        var decodeMs = 0L; var detectMs = 0L
        val wall0 = System.currentTimeMillis()

        for (u in uris) {
            try {
                val t0 = System.nanoTime()
                val bmp = decodeDownscaled(ctx, u) ?: run { failed++; null } ?: continue
                val t1 = System.nanoTime()
                val faces = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
                val t2 = System.nanoTime()
                decodeMs += (t1 - t0) / 1_000_000
                detectMs += (t2 - t1) / 1_000_000
                val n = faces.size
                hist[n] = (hist[n] ?: 0) + 1
                ok++
                bmp.recycle()
            } catch (e: FileNotFoundException) {
                dead++   // stale MediaStore row — the gotcha from the emulator run
            } catch (e: Exception) {
                failed++
                if (failed <= 3) Log.w(TAG, "BENCH err ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        val wall = System.currentTimeMillis() - wall0

        Log.i(TAG, "BENCH processed=$ok dead_rows=$dead failed=$failed")
        Log.i(TAG, "BENCH wall_ms=$wall throughput=${"%.1f".format(ok * 1000.0 / wall)} img/s")
        Log.i(TAG, "BENCH avg_decode_ms=${if (ok>0) decodeMs/ok else 0} avg_detect_ms=${if (ok>0) detectMs/ok else 0}")

        val keys = hist.keys.sorted()
        for (k in keys) Log.i(TAG, "BENCH faces=$k count=${hist[k]}")
        val zero = hist[0] ?: 0
        val one2 = (hist[1] ?: 0) + (hist[2] ?: 0)
        val crowd = keys.filter { it >= 3 }.sumOf { hist[it] ?: 0 }
        Log.i(TAG, "BENCH SUMMARY zero_face=$zero one_or_two=$one2 crowd_3plus=$crowd")
        Log.i(TAG, "BENCH crowd_share=${"%.1f".format(crowd * 100.0 / maxOf(ok,1))}%")
        detector.close()
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
                out += ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(i)
                )
            }
        }
        return out
    }

    private fun decodeDownscaled(ctx: Context, u: Uri) = run {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var scale = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / (scale * 2) >= TARGET_PX) scale *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = scale }
        ctx.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
