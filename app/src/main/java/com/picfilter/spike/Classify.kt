package com.picfilter.spike

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

private const val TAG = "PICFILTER"

/**
 * What IS the junk? Cross-tabs face count against origin/text signals.
 * Fully automatic — needs no labels and no recognition model.
 * Aggregates only.
 */
object Classify {

    private const val TARGET_PX = 900
    private val SCREEN_RATIOS = floatArrayOf(16f/9, 19.5f/9, 20f/9, 4f/3, 3f/2, 1f)

    fun run(ctx: Context, limit: Int) {
        val faceDet = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.04f).build()
        )
        val textRec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val rows = waRows(ctx, limit)
        Log.i(TAG, "CLASSIFY candidates=${rows.size}")

        // class x faceBucket counts
        val tab = HashMap<String, Int>()
        var ok = 0; var failed = 0
        val t0 = System.currentTimeMillis()

        for (u in rows) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(u)!!.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val w = bounds.outWidth; val h = bounds.outHeight
                if (w <= 0 || h <= 0) { failed++; continue }

                var hasCam = false
                ctx.contentResolver.openInputStream(u)!!.use { ins ->
                    val ex = ExifInterface(ins)
                    hasCam = !ex.getAttribute(ExifInterface.TAG_MAKE).isNullOrBlank() ||
                             !ex.getAttribute(ExifInterface.TAG_MODEL).isNullOrBlank()
                }

                var scale = 1
                val maxDim = maxOf(w, h)
                while (maxDim / (scale * 2) >= TARGET_PX) scale *= 2
                val bmp = ctx.contentResolver.openInputStream(u)!!.use {
                    BitmapFactory.decodeStream(it, null,
                        BitmapFactory.Options().apply { inSampleSize = scale })
                } ?: run { failed++; null } ?: continue

                val img = InputImage.fromBitmap(bmp, 0)
                val faces = Tasks.await(faceDet.process(img)).size
                val text = Tasks.await(textRec.process(img))

                var textArea = 0f
                var blocks = 0
                for (b in text.textBlocks) {
                    blocks++
                    b.boundingBox?.let { textArea += (it.width().toFloat() * it.height()) }
                }
                val textFrac = textArea / (bmp.width.toFloat() * bmp.height)
                bmp.recycle()

                val ratio = maxOf(w, h).toFloat() / minOf(w, h)
                val screenish = SCREEN_RATIOS.any { abs(it - ratio) < 0.03f }

                val cls = when {
                    hasCam                       -> "CAMERA"
                    textFrac > 0.08f || blocks > 10 -> "TEXT_HEAVY"
                    screenish                    -> "SCREENSHOT_SHAPED"
                    else                         -> "OTHER_NOEXIF"
                }
                val fb = when { faces == 0 -> "f0"; faces <= 2 -> "f1-2"; else -> "f3+" }
                tab["$cls|$fb"] = (tab["$cls|$fb"] ?: 0) + 1
                ok++
            } catch (e: Exception) {
                failed++
                if (failed <= 3) Log.w(TAG, "CLASSIFY err ${e.javaClass.simpleName}")
            }
        }
        val wall = System.currentTimeMillis() - t0
        Log.i(TAG, "CLASSIFY processed=$ok failed=$failed wall_ms=$wall thr=${"%.1f".format(ok*1000.0/wall)} img/s")
        for (k in tab.keys.sorted()) Log.i(TAG, "CLASSIFY $k = ${tab[k]}")
        // marginals
        val byCls = HashMap<String, Int>()
        for ((k, v) in tab) byCls[k.substringBefore('|')] = (byCls[k.substringBefore('|')] ?: 0) + v
        for (k in byCls.keys.sorted()) Log.i(TAG, "CLASSIFY TOTAL $k = ${byCls[k]} (${"%.1f".format(byCls[k]!!*100.0/maxOf(ok,1))}%)")
        faceDet.close(); textRec.close()
    }

    private fun waRows(ctx: Context, limit: Int): List<Uri> {
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
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(i))
            }
        }
        return out
    }
}
