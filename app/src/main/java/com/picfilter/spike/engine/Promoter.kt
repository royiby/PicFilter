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
import java.io.IOException
import java.util.Date
import java.util.Locale
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap

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

    /**
     * The folder's own name, as Google Photos lists it in "Back up device
     * folders". The backup screen shows this row for the user to find, so it is
     * deliberately not a translated string -- Google Photos will not translate
     * it either.
     */
    const val DEST_NAME = "PixKin"

    /**
     * Where rescued photos used to land.
     *
     * The folder was named after the spike, not the product, so an app called
     * PixKin handed people a folder called PicFilter. Renaming it is cheap for
     * a new install and not for an existing one, whose photos are already in
     * the old folder and whose promotion rows point at them -- hence
     * [moveDest]. Kept as a constant rather than inlined because the migration
     * has to keep finding the old folder long after nothing writes to it.
     */
    const val LEGACY_DEST = "Pictures/PicFilter"

    private const val DEST = "Pictures/$DEST_NAME"

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
        // Existence means "MediaStore has something filed under this path",
        // which is also the only sense Google Photos cares about when it lists
        // device folders. Stat-ing /sdcard answered a different question.
        if (existingDest(ctx).isNotEmpty()) return true
        return try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, ".pixkin-folder-marker.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, DEST)
            }
            val uri = ctx.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, out)
            }
            // The marker is gone; the directory it created is what we wanted,
            // and the insert succeeding is the proof that the path is writable.
            Log.i(TAG, "DESTFOLDER created path=$DEST")
            true
        } catch (e: Exception) {
            Log.e(TAG, "DESTFOLDER failed ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Where rescued photos land, for the backup instructions. */
    const val DEST_LABEL = DEST

    /**
     * Move everything out of [LEGACY_DEST] and into [DEST].
     *
     * Updating `RELATIVE_PATH` moves the file on disk and keeps its MediaStore
     * `_id`, which is what `promotion.dest_uri` holds -- so the database needs
     * no migration of its own and the rows stay valid throughout. That is the
     * whole reason to move them this way rather than copy-and-delete: there is
     * no window where a row points at a file that is not there.
     *
     * The app owns these files because it created them, so it can move them
     * without any storage permission a user would have to grant.
     *
     * Returns moved to failed. Dry-run reports what it would do and touches
     * nothing, like every other destructive op here.
     */
    /** Everything still sitting in [LEGACY_DEST], as uri to display name. */
    fun legacyDestItems(ctx: Context): List<Pair<Uri, String>> {
        val out = mutableListOf<Pair<Uri, String>>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$LEGACY_DEST%"), null
        )?.use { c ->
            while (c.moveToNext()) out += ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
            ) to (c.getString(1) ?: "?")
        }
        return out
    }

    /**
     * Move [items] from [LEGACY_DEST] into [DEST].
     *
     * Updating `RELATIVE_PATH` moves the file on disk and keeps its MediaStore
     * `_id`, which is what `promotion.dest_uri` holds -- so the database needs
     * no migration of its own and no row ever points at a file that is not
     * there. That is the reason to move rather than copy-and-delete.
     *
     * **The caller must already hold write access.** These files were created
     * by the old applicationId, and MediaStore ownership is per package, so
     * the app that rescued them is not the app running now: every update
     * throws RecoverableSecurityException until the user grants write access
     * through `MediaStore.createWriteRequest`. An app normally owns what it
     * wrote, which is why nothing here expected to need permission.
     */
    fun moveToDest(ctx: Context, items: List<Pair<Uri, String>>): Pair<Int, Int> {
        var moved = 0
        var failed = 0
        for ((uri, name) in items) {
            try {
                val n = ctx.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$DEST/")
                }, null, null)
                if (n == 1) moved++ else { failed++; Log.w(TAG, "MOVEDEST no-op $name") }
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "MOVEDEST failed $name: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        Log.i(TAG, "MOVEDEST moved=$moved failed=$failed $LEGACY_DEST -> $DEST")
        return moved to failed
    }

    private val WA_DATE = Regex("""IMG-(\d{8})-WA""")

    /**
     * One file sitting in the destination folder, as MediaStore describes it.
     *
     * [path] is the filesystem path MediaStore itself reports. The column is
     * deprecated and may be null, so it is only ever used where a path is
     * genuinely required -- MediaScannerConnection takes one -- and never to
     * decide whether a file exists. That used to be done by building
     * "/sdcard/$DEST/$name" by hand, which assumes the primary
     * volume is mounted there and that the folder is where we last left it.
     * Neither is guaranteed, and MediaStore is the thing that actually knows.
     */
    data class DestFile(
        val uri: Uri, val name: String, val size: Long,
        val modifiedSeconds: Long, val path: String?,
    )

    /**
     * What is in the destination folder right now, keyed by display name.
     */
    private fun existingDest(ctx: Context): Map<String, DestFile> {
        val out = HashMap<String, DestFile>()
        @Suppress("DEPRECATION")
        val dataCol = MediaStore.MediaColumns.DATA
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                dataCol,
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$DEST%"), null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iData = c.getColumnIndex(dataCol)
            while (c.moveToNext()) {
                val name = c.getString(iName) ?: continue
                out[name] = DestFile(
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(iId)),
                    name = name,
                    size = c.getLong(iSize),
                    modifiedSeconds = c.getLong(iMod),
                    path = if (iData >= 0 && !c.isNull(iData)) c.getString(iData) else null,
                )
            }
        }
        return out
    }

    /**
     * Does this uri point at bytes we can actually read?
     *
     * The honest replacement for File(...).exists() && length() > 0: ask the
     * provider to open it. A MediaStore row can outlive its file, and opening
     * is the only answer that cannot be stale.
     */
    /**
     * Display names in the destination folder whose bytes are actually
     * readable. For diagnostics that want to compare the table with reality.
     */
    fun presentDestNames(ctx: Context): Set<String> =
        existingDest(ctx).filterValues { readableBytes(ctx, it.uri) > 0L }.keys

    private fun readableBytes(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (e: IOException) { -1L } catch (e: SecurityException) { -1L }

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
        val sizes = existing.mapValues { it.value.size }
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
                    val there = existing[r.name]!!
                    db.recordPromotion(r.id, there.uri.toString(), r.name!!, there.size)
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
                ctx.contentResolver.openInputStream(r.uri.toUri())!!.use { ins ->
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
                //
                // Asked of the provider that owns the row, rather than of a
                // path we assembled ourselves.
                if (readableBytes(ctx, dest) <= 0L) {
                    Log.w(TAG, "PROMOTE VERIFY FAILED $name (unreadable after write)")
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
        // MediaScannerConnection needs a filesystem path, and MediaStore is the
        // only thing that knows it. Looked up, never assembled.
        val scanPath = name?.let { existingDest(ctx)[it]?.path }
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
        scanPath?.let {
            MediaScannerConnection.scanFile(ctx, arrayOf(it), arrayOf("image/jpeg"), null)
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
        val inFolder = existingDest(ctx)
        for ((uriStr, name) in db.promotedDests()) {
            val there = inFolder[name]
            if (there == null || readableBytes(ctx, there.uri) <= 0L) { gone++; continue }
            // Read the EXIF through the provider, so this works whatever the
            // volume is mounted as.
            val already = try {
                ctx.contentResolver.openInputStream(there.uri)?.use { ins ->
                    ExifInterface(ins).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != null
                } ?: false
            } catch (e: IOException) { false } catch (e: SecurityException) { false }
            if (already) { skipped++; continue }
            val taken = originalDateMs(name, System.currentTimeMillis() / 1000)
            if (stampDate(ctx, uriStr.toUri(), taken, name)) ok++ else bad++
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
            } catch (e: SecurityException) {
                Log.w(TAG, "UNPROMOTE delete failed for $name: ${e.javaClass.simpleName}")
            }
            // No filesystem fallback: this app created the entry, so deleting
            // through the provider is the whole of it. Reaching round the
            // provider to a path we guessed is how a delete silently targets
            // nothing -- or something else.
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
                } catch (e: IOException) { continue } catch (e: SecurityException) { continue }
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
        for ((name, there) in existingDest(ctx)) {
            if (name in known) continue
            val itemId = db.itemIdByName(name) ?: continue
            db.recordPromotion(itemId, there.uri.toString(), name, there.size)
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
        var bytes = 0L
        for ((name, there) in existingDest(ctx)) {
            // DATE_MODIFIED is in seconds, and it is MediaStore's own record of
            // when the file last changed -- the same fact File.lastModified()
            // was being asked for, from the party that maintains it.
            if (there.modifiedSeconds * 1000L > cutoff) { tooNew++; continue }
            if (readableBytes(ctx, there.uri) <= 0L) continue
            val itemId = db.itemIdByName(name) ?: continue
            out += there.uri to name
            ids += itemId
            bytes += there.size
        }
        val mb = bytes / 1048576.0
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
        val inFolder = existingDest(ctx)
        for ((_, name) in db.promotedDests()) {
            if (name in synced) continue
            val there = inFolder[name]
            if (there == null || readableBytes(ctx, there.uri) <= 0L) {
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
            } catch (e: java.text.ParseException) { /* fall through */ }
        }
        return fallbackSeconds * 1000
    }
}
