package com.picfilter.spike.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.media.MediaScannerConnection
import androidx.exifinterface.media.ExifInterface
import com.picfilter.spike.data.Db
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "PICFILTER"

/**
 * Rescue: copy keepers into a folder that Google Photos backs up.
 *
 * The premise changed on 2026-10-18 — WhatsApp backup had already been switched
 * off by hand months earlier, so nothing needs gatekeeping. What the user lost
 * in exchange was the good photos: 143 items containing enrolled family that
 * exist only on the phone. This restores their cloud copy without re-admitting
 * the junk.
 *
 * Copies only. Never trashes, never deletes, never touches the source file.
 */
object Promoter {

    private const val DEST = "Pictures/PicFilter"
    private val WA_DATE = Regex("""IMG-(\d{8})-WA""")

    fun run(
        ctx: Context, dryRun: Boolean,
        decisions: List<String> = listOf("KEEP"),
        byLabel: Boolean = false,
    ) {
        require(!decisions.contains("DISCARD_CANDIDATE")) { "refusing to promote discards" }
        val db = Db(ctx)
        val rows = if (byLabel) db.labelledKeepForPromotion() else db.itemsForPromotion(decisions)
        val totalMb = rows.sumOf { it.bytes } / 1048576.0
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        Log.i(TAG, "PROMOTE ${if (dryRun) "DRY RUN" else "LIVE"} " +
            "source=${if (byLabel) "human KEEP labels" else decisions} candidates=${rows.size} ${"%.1f".format(totalMb)} MB")
        if (rows.isEmpty()) {
            Log.i(TAG, "PROMOTE nothing to do (already promoted: ${db.promotionSummary()})")
            db.close(); return
        }
        Log.i(TAG, "PROMOTE date range ${fmt.format(rows.first().dateAdded * 1000)} .. " +
            "${fmt.format(rows.last().dateAdded * 1000)}")
        val byReason = rows.groupingBy { it.reason }.eachCount()
        for ((r, n) in byReason) Log.i(TAG, "PROMOTE   reason $r = $n")
        for (r in rows.take(5)) Log.i(TAG, "PROMOTE   e.g. ${r.name}")

        if (dryRun) {
            Log.i(TAG, "PROMOTE dry run complete — nothing was copied")
            db.close(); return
        }

        var ok = 0; var failed = 0; var bytes = 0L
        for (r in rows) {
            try {
                val name = r.name ?: "picfilter_${r.id}.jpg"
                val taken = originalDateMs(name, r.dateAdded)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, DEST)
                    // WhatsApp strips EXIF from ~91% of its media, so without an
                    // explicit date these all land in the timeline as "today".
                    put(MediaStore.MediaColumns.DATE_TAKEN, taken)
                    put(MediaStore.MediaColumns.DATE_ADDED, taken / 1000)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, taken / 1000)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val dest = ctx.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: run { failed++; null } ?: continue

                var copied = 0L
                ctx.contentResolver.openInputStream(Uri.parse(r.uri))!!.use { ins ->
                    ctx.contentResolver.openOutputStream(dest)!!.use { outs ->
                        copied = ins.copyTo(outs)
                    }
                }
                ctx.contentResolver.update(dest, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)

                // MediaProvider re-scans on IS_PENDING -> 0 and recomputes metadata
                // from EXIF, silently discarding DATE_TAKEN set at insert time.
                // Write the date INTO the file, then restate it on the row.
                stampDate(ctx, dest, taken, name)

                db.recordPromotion(r.id, dest.toString(), name, copied)
                bytes += copied; ok++
            } catch (e: Exception) {
                failed++
                if (failed <= 3) Log.w(TAG, "PROMOTE err ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        Log.i(TAG, "PROMOTE copied=$ok failed=$failed ${"%.1f".format(bytes / 1048576.0)} MB -> $DEST")
        Log.i(TAG, "PROMOTE total promoted to date: ${db.promotionSummary()}")
        Log.i(TAG, "PROMOTE source files untouched — this only copies")
        db.close()
    }

    /**
     * Put the real date where it will actually survive: in the JPEG's own EXIF.
     * Google Photos reads EXIF cloud-side, so this travels with the file rather
     * than living only in a local database row.
     */
    fun stampDate(ctx: Context, uri: Uri, takenMs: Long, name: String? = null): Boolean = try {
        val exifFmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val stamp = exifFmt.format(Date(takenMs))
        ctx.contentResolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
            val ex = ExifInterface(pfd.fileDescriptor)
            ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
            ex.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
            ex.setAttribute(ExifInterface.TAG_DATETIME, stamp)
            ex.saveAttributes()
        }
        ctx.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.DATE_TAKEN, takenMs)
        }, null, null)
        // the row is stale until MediaProvider re-reads the file it now shares
        // a date with; without this the local gallery still sorts by import time
        name?.let {
            MediaScannerConnection.scanFile(
                ctx, arrayOf("/sdcard/$DEST/$it"), arrayOf("image/jpeg"), null)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "STAMP failed for $uri: ${e.javaClass.simpleName} ${e.message}")
        false
    }

    /** Repair dates on files already promoted (EXIF was lost on first run). */
    fun fixDates(ctx: Context) {
        val db = Db(ctx)
        var ok = 0; var bad = 0
        for ((uriStr, name) in db.promotedDests()) {
            val taken = originalDateMs(name, System.currentTimeMillis() / 1000)
            if (stampDate(ctx, Uri.parse(uriStr), taken, name)) ok++ else bad++
        }
        Log.i(TAG, "STAMP repaired=$ok failed=$bad")
        db.close()
    }

    /** WhatsApp filenames encode the date: IMG-20260630-WA0181.jpg */
    private fun originalDateMs(name: String, fallbackSeconds: Long): Long {
        WA_DATE.find(name)?.groupValues?.get(1)?.let { d ->
            try {
                return SimpleDateFormat("yyyyMMdd", Locale.US).parse(d)!!.time
            } catch (e: Exception) { /* fall through */ }
        }
        return fallbackSeconds * 1000
    }
}
