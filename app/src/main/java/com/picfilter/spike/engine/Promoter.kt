package com.picfilter.spike.engine

import android.content.ContentUris
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

    /**
     * Google Photos only lists a device folder once it exists, and the folder
     * is created by the first promotion — which is weeks of waiting if nothing
     * has been rescued yet. Onboarding asks the user to switch backup on there
     * and then, so write a marker file to bring the folder into being and
     * delete it again; the directory outlives it.
     *
     * Deleting our own MediaStore entry needs no dialog (SPIKES.md), so this
     * leaves nothing behind in the user's library.
     */
    fun ensureDestFolder(ctx: Context): Boolean {
        val dir = java.io.File("/sdcard/$DEST")
        if (dir.isDirectory) return true
        return try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, ".pixkin-folder-marker.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, DEST)
            }
            val uri = ctx.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, out)
            }
            ctx.contentResolver.delete(uri, null, null)
            val ok = dir.isDirectory
            Log.i(TAG, "DESTFOLDER created=$ok path=$dir")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "DESTFOLDER failed ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Where rescued photos land, for the backup instructions. */
    const val DEST_LABEL = "Pictures/PicFilter"
    private val WA_DATE = Regex("""IMG-(\d{8})-WA""")

    /**
     * What is in the destination folder right now: display name -> size, plus
     * the uri so a skipped file can be recorded without copying it again.
     */
    private fun existingDest(ctx: Context): Map<String, Pair<Uri, Long>> {
        val out = HashMap<String, Pair<Uri, Long>>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$DEST%"), null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (c.moveToNext()) {
                val name = c.getString(iName) ?: continue
                out[name] = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(iId)
                ) to c.getLong(iSize)
            }
        }
        return out
    }

    /**
     * What to write a photo as, or null when the folder already has it.
     *
     * The promotion table says what PixKin believes it copied; the folder says
     * what is actually there. Those drift apart — clearing app data empties the
     * table while the files stay — and then a run copies everything a second
     * time, because MediaStore does not overwrite: it writes "name (1).jpg".
     * So the folder is the authority here.
     *
     * Same name and same size is the same photo, so skip it. Same name and a
     * different size is a genuine collision — one filename, two pictures — and
     * the second one gets a name of its own rather than being dropped.
     */
    fun destName(
        sourceName: String?, itemId: Long, bytes: Long, existing: Map<String, Long>,
    ): String? {
        val base = sourceName?.takeIf { it.isNotBlank() } ?: "picfilter_$itemId.jpg"
        val there = existing[base] ?: return base
        if (there == bytes) return null
        val dot = base.lastIndexOf('.')
        return if (dot > 0) "${base.substring(0, dot)}_$itemId${base.substring(dot)}"
               else "${base}_$itemId"
    }

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

        // The folder, not the promotion table, decides what still needs copying.
        val existing = existingDest(ctx)
        val sizes = existing.mapValues { it.value.second }
        val alreadyThere = rows.count { destName(it.name, it.id, it.bytes, sizes) == null }
        Log.i(TAG, "PROMOTE folder holds ${existing.size} files; $alreadyThere of the " +
            "${rows.size} candidates are already among them")

        if (dryRun) {
            Log.i(TAG, "PROMOTE dry run complete — nothing was copied")
            db.close(); return
        }

        var ok = 0; var failed = 0; var bytes = 0L; var skipped = 0
        for (r in rows) {
            try {
                val name = destName(r.name, r.id, r.bytes, sizes)
                if (name == null) {
                    // Already in the folder. Record it so the table catches up
                    // with the folder and the next run has nothing to do.
                    val (uri, size) = existing[r.name]!!
                    db.recordPromotion(r.id, uri.toString(), r.name!!, size)
                    skipped++
                    continue
                }
                if (name != r.name) Log.w(TAG,
                    "PROMOTE name collision: ${r.name} is taken by another photo, " +
                    "writing $name instead")
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

                // Verify rather than trust: 19 files from the first run were
                // recorded as promoted and later found absent. A rescue tool
                // that reports success for a file that is not there is worse
                // than one that fails loudly.
                val f = java.io.File("/sdcard/$DEST/$name")
                if (!f.exists() || f.length() <= 0L) {
                    Log.w(TAG, "PROMOTE VERIFY FAILED $name (not on disk after write)")
                    failed++
                    continue
                }
                db.recordPromotion(r.id, dest.toString(), name, copied)
                bytes += copied; ok++
            } catch (e: Exception) {
                failed++
                if (failed <= 3) Log.w(TAG, "PROMOTE err ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        Log.i(TAG, "PROMOTE copied=$ok skipped=$skipped (already in the folder) " +
            "failed=$failed ${"%.1f".format(bytes / 1048576.0)} MB -> $DEST")
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
    /**
     * Stamp dates on already-promoted files that still LACK one.
     *
     * saveAttributes() rewrites the whole JPEG to insert an APP1 segment. This
     * was run twice over all 143 files of the first batch, and 19 of them —
     * the last written — were later found missing. Repeated rewriting is the
     * prime suspect, so only stamp what is actually undated.
     */
    fun fixDates(ctx: Context) {
        val db = Db(ctx)
        var ok = 0; var bad = 0; var skipped = 0; var gone = 0
        for ((uriStr, name) in db.promotedDests()) {
            val f = java.io.File("/sdcard/$DEST/$name")
            if (!f.exists()) { gone++; continue }
            val already = try {
                ExifInterface(f.absolutePath)
                    .getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != null
            } catch (e: Exception) { false }
            if (already) { skipped++; continue }
            val taken = originalDateMs(name, System.currentTimeMillis() / 1000)
            if (stampDate(ctx, Uri.parse(uriStr), taken, name)) ok++ else bad++
        }
        Log.i(TAG, "STAMP stamped=$ok failed=$bad already_dated=$skipped missing_file=$gone")
        db.close()
    }

    /**
     * Undo a promotion the human says was wrong: delete the copy we made and
     * forget it. The SOURCE is never touched. Safe without a system dialog
     * because PicFilter created the destination file itself.
     */
    fun unpromote(ctx: Context, itemId: Long): Boolean {
        val db = Db(ctx)
        val name = db.promotionNameFor(itemId)
        var removed = false
        if (name != null) {
            try {
                val n = ctx.contentResolver.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf("$DEST/", name))
                removed = n > 0
            } catch (e: Exception) {
                Log.w(TAG, "UNPROMOTE delete failed for $name: ${e.javaClass.simpleName}")
            }
            java.io.File("/sdcard/$DEST/$name").takeIf { it.exists() }?.delete()
        }
        db.deletePromotion(itemId)
        db.close()
        Log.i(TAG, "UNPROMOTE item=$itemId name=$name file_removed=$removed " +
            "(source untouched; if it already synced, delete it in Google Photos too)")
        return removed
    }

    /** Drop promotion records whose file is gone, so they become eligible again. */
    /**
     * Redundant copies of the same photo sitting in the destination folder.
     *
     * The folder accumulated them: before the folder became the authority for
     * what still needed copying, a second promote run wrote the same picture
     * again, and because MediaStore never overwrites it landed as
     * "name (1).jpg" -- or, once destName() started avoiding collisions, as
     * "name_<itemId>.jpg". 174 files turned out to be 111 photos.
     *
     * Identity is the file's bytes, not its name, so this cannot mistake two
     * genuinely different pictures for one. Of each set the shortest name wins,
     * which is the original promotion rather than either rename.
     */
    fun redundantCopies(ctx: Context): List<Pair<Uri, String>> {
        val byHash = HashMap<String, MutableList<Pair<Uri, String>>>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$DEST%"), null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(iId))
                val name = c.getString(iName) ?: continue
                val hash = try {
                    val md = java.security.MessageDigest.getInstance("MD5")
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            md.update(buf, 0, n)
                        }
                    } ?: continue
                    md.digest().joinToString("") { "%02x".format(it) }
                } catch (e: Exception) { continue }
                byHash.getOrPut(hash) { mutableListOf() } += uri to name
            }
        }
        val out = mutableListOf<Pair<Uri, String>>()
        for ((_, copies) in byHash) {
            if (copies.size < 2) continue
            val keep = copies.minByOrNull { it.second.length }!!
            out += copies.filter { it !== keep }
        }
        return out
    }

    /**
     * Report the redundant copies, for a dry run.
     *
     * The trashing itself lives in MainActivity, because it needs the activity's
     * result launcher: setting IS_TRASHED directly works only on entries the app
     * OWNS, and uninstalling drops that ownership -- after a reinstall every
     * update silently returns 0, which is exactly what happened here. Files we
     * no longer own need MediaStore.createTrashRequest and the user's tap, which
     * is the better consent model for deleting someone's pictures anyway.
     */
    fun tidyDest(ctx: Context, dryRun: Boolean = true): Int {
        val extras = redundantCopies(ctx)
        Log.i(TAG, "TIDYDEST ${extras.size} redundant copies" +
            if (dryRun) " (dry run, nothing moved)" else "")
        for ((_, name) in extras.take(10)) Log.i(TAG, "TIDYDEST   $name")
        return extras.size
    }

    /** Rescued copies are presumed uploaded once they have sat here this long. */
    const val SYNC_GRACE_HOURS = 24

    /**
     * Give every file in the folder a promotion record.
     *
     * The record is what stops a photo being copied again -- itemsForPromotion
     * skips anything with a row. The folder drifted ahead of the table (111
     * files against 31 rows) because files were written by runs whose records
     * were later dropped, so without this, reclaiming the files would simply
     * invite the next pass to copy them all back.
     */
    fun reconcileDest(ctx: Context): Int {
        val db = Db(ctx)
        val known = db.promotedDests().map { it.second }.toSet()
        var added = 0
        for ((name, pair) in existingDest(ctx)) {
            if (name in known) continue
            val itemId = db.itemIdByName(name) ?: continue
            db.recordPromotion(itemId, pair.first.toString(), name, pair.second)
            added++
        }
        Log.i(TAG, "RECONCILE added $added promotion records for files already in the folder")
        db.close()
        return added
    }

    /**
     * Local copies that have done their job and can be reclaimed.
     *
     * We cannot ask Google Photos what it holds -- the API that could answer is
     * closed to us -- so "synced" is an inference: the folder is backed up
     * (verified by finding a rescued photo in the cloud, under its original
     * date), and this file has been sitting in it for at least
     * [SYNC_GRACE_HOURS]. Marking is therefore the user's assertion, which is
     * why it needs their confirmation and why the files are trashed rather than
     * deleted.
     *
     * Marking happens whether or not they then clear the files: "this is in the
     * cloud" is true independently of whether a local copy still exists.
     */
    fun reclaimable(ctx: Context, graceHours: Int = SYNC_GRACE_HOURS, dryRun: Boolean = true):
        List<Pair<Uri, String>> {
        reconcileDest(ctx)
        val db = Db(ctx)
        val cutoff = System.currentTimeMillis() - graceHours * 3_600_000L
        val out = mutableListOf<Pair<Uri, String>>()
        val ids = mutableListOf<Long>()
        var tooNew = 0
        for ((name, pair) in existingDest(ctx)) {
            val f = java.io.File("/sdcard/$DEST/$name")
            if (!f.exists()) continue
            if (f.lastModified() > cutoff) { tooNew++; continue }
            val itemId = db.itemIdByName(name) ?: continue
            out += pair.first to name
            ids += itemId
        }
        val mb = out.sumOf { java.io.File("/sdcard/$DEST/${it.second}").length() } / 1048576.0
        Log.i(TAG, "RECLAIM ${out.size} copies reclaimable (${"%.1f".format(mb)} MB), " +
            "$tooNew still within the ${graceHours}h grace period" +
            if (dryRun) " (dry run)" else "")
        if (!dryRun) Log.i(TAG, "RECLAIM marked ${db.markSynced(ids)} as synced")
        db.close()
        return out
    }

    fun repair(ctx: Context): Int {
        val db = Db(ctx)
        var dropped = 0
        // A synced copy is MEANT to be gone: its photo is in the cloud and the
        // local file was reclaimed. Dropping its record here would make the next
        // pass copy it back, which is the whole trap this guards against.
        val synced = db.syncedDestNames()
        for ((_, name) in db.promotedDests()) {
            if (name in synced) continue
            if (!java.io.File("/sdcard/$DEST/$name").exists()) {
                db.writableDatabase.execSQL(
                    "DELETE FROM promotion WHERE dest_name=?", arrayOf<Any>(name))
                dropped++
            }
        }
        Log.i(TAG, "REPAIR dropped $dropped stale promotion records; re-run promote to recopy")
        db.close()
        return dropped
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
