package com.picfilter.spike

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.lifecycleScope
import com.picfilter.spike.engine.Pipeline
import com.picfilter.spike.engine.Sweep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import androidx.core.net.toUri

private const val TAG = "PICFILTER"
private const val WA_FILTER = "%com.whatsapp%"

/**
 * The adb-driven developer surface: every `op` extra, and the Phase 0 spikes.
 *
 * This was 423 of MainActivity's 884 lines, which made the file look like the
 * app's centre of gravity when most of it is diagnostics nobody ships. The
 * Activity is now the home screen and the permission flow; this is the
 * workbench. Nothing here is reachable from the UI.
 *
 * Extension functions on MainActivity rather than a class, so the code moved
 * without being rewritten -- the receiver is still the Activity and every call
 * reads as it did.
 */

/**
 * The adb-driven diagnostics.
 *
 * Most of these stay on a bare Thread on purpose, which is the one place in
 * the app that is still right. They are fire-and-forget measurements that
 * only write to logcat, and a long score or bootstrap must finish even if
 * the screen goes away -- lifecycleScope would cancel it halfway and report
 * nothing. The two that come back to the UI are scoped; see below.
 */
internal fun MainActivity.dispatch(op: String) {
    // MainActivity is exported -- it has to be, it is the launcher -- and this
    // runs off an intent extra. Without this gate any app on the device could
    // start it with op=reset or op=clearlabels and destroy work the user spent
    // hours on, with no permission of any kind. Every op in this file has been
    // driven from the shell all along, which is the same path a hostile app
    // would take.
    if (!BuildConfig.DEBUG) {
        Log.w(TAG, "DEV op '$op' ignored: diagnostics are debug-build only")
        return
    }
    // Nested lambdas shadow `this` (a CoroutineScope inside launch, a
    // ContentValues inside apply), so the Activity is held by name.
    val act = this
    when (op) {
        // These four query MediaStore over the whole library. Scoped
        // rather than bare-threaded because trash and untrash come back to
        // the main thread to raise the system dialog.
        "scan" -> lifecycleScope.launch { withContext(Dispatchers.IO) { scan() } }
        "trash" -> lifecycleScope.launch {
            val uris = withContext(Dispatchers.IO) { waUris() }
            Log.i(TAG, "SPIKE2 requesting trash for ${uris.size} uris")
            if (uris.isEmpty()) Log.w(TAG, "nothing to trash") else trashUris(uris)
        }
        "untrash" -> lifecycleScope.launch {
            val uris = withContext(Dispatchers.IO) { waUrisIncludingTrashed() }
            Log.i(TAG, "requesting UNtrash for ${uris.size} uris")
            if (uris.isNotEmpty()) {
                val pi = MediaStore.createTrashRequest(contentResolver, uris, false)
                trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }
        }
        "verify" -> lifecycleScope.launch { withContext(Dispatchers.IO) { verify() } }
        "spikewrite" -> promote()   // Phase 0 gatekeeper write test
        "bench" -> Thread {
            Bench.run(this, intent?.getStringExtra("limit")?.toIntOrNull() ?: 300)
        }.start()
        // ---- Phase 1 pipeline ----
        "enroll"    -> Thread { Pipeline.enroll(this) }.start()
        "scanmedia" -> Thread {
            Pipeline.scanMedia(this, intent?.getStringExtra("limit")?.toIntOrNull() ?: 5000)
        }.start()
        "score"     -> Thread {
            Pipeline.score(this, intent?.getStringExtra("limit")?.toIntOrNull() ?: 5000)
        }.start()
        "report"    -> Thread { Pipeline.report(this) }.start()
        "sweep"     -> Thread { Sweep.run(this) }.start()
        "promote"   -> Thread {
            com.picfilter.spike.engine.Promoter.run(
                this,
                dryRun = intent?.getStringExtra("live") != "1",
                decisions = (intent?.getStringExtra("decisions") ?: "KEEP").split(","),
                byLabel = intent?.getStringExtra("bylabel") == "1",
            )
        }.start()
        "repair"    -> Thread { com.picfilter.spike.engine.Promoter.repair(this) }.start()
        // These two come back to the UI to raise the system trash dialog, so
        // they are scoped: a result that arrives after the screen is gone
        // has nothing to show it to, and launching from a dead Activity
        // throws.
        "reclaim"   -> lifecycleScope.launch {
            val live = intent?.getStringExtra("live") == "1"
            val hrs = intent?.getStringExtra("hours")?.toIntOrNull()
                ?: com.picfilter.spike.engine.Promoter.SYNC_GRACE_HOURS
            val gone = withContext(Dispatchers.IO) {
                com.picfilter.spike.engine.Promoter.reclaimable(
                    act, hrs, dryRun = !live)
            }
            if (live && gone.isNotEmpty()) {
                Log.i(TAG, "RECLAIM asking to trash ${gone.size} local copies")
                trashUris(gone.map { it.first })
            }
        }
        "tidydest"  -> lifecycleScope.launch {
            val live = intent?.getStringExtra("live") == "1"
            val extras = withContext(Dispatchers.IO) {
                com.picfilter.spike.engine.Promoter.tidyDest(act, dryRun = !live)
                if (live) com.picfilter.spike.engine.Promoter.redundantCopies(act)
                else emptyList()
            }
            if (extras.isNotEmpty()) {
                Log.i(TAG, "TIDYDEST asking to trash ${extras.size} copies")
                trashUris(extras.map { it.first })
            }
        }
        "unpromote" -> Thread {
            intent?.getStringExtra("id")?.toLongOrNull()?.let {
                com.picfilter.spike.engine.Promoter.unpromote(this, it)
            }
        }.start()
        // Setup only opens itself on a first run, so these are the way back
        // in to look at it without clearing anyone's enrolment.
        "onboard"   -> startActivity(android.content.Intent().apply {
            component = ComponentName(act, OnboardingActivity::class.java)
            putExtra("first_run", "1")
            intent?.getStringExtra("step")?.let { putExtra("step", it) }
        })
        "watchon"   -> Thread { com.picfilter.spike.engine.Watcher.enable(this) }.start()
        "watchoff"  -> Thread { com.picfilter.spike.engine.Watcher.disable(this) }.start()
        "watchnow"  -> Thread { com.picfilter.spike.engine.Watcher.runNow(this) }.start()
        "watchstat" -> Thread { com.picfilter.spike.engine.Watcher.status(this) }.start()
        "pickenrol" -> Thread {
            // Dev only: feed enrolFromUris real photos without the picker,
            // so the engine can be checked apart from the UI.
            val d = com.picfilter.spike.data.Db(this)
            val pid = intent?.getStringExtra("pid")?.toLongOrNull() ?: 0L
            val n = intent?.getStringExtra("n")?.toIntOrNull() ?: 4
            val uris = d.matchedItemsFor(pid, n).map { it.second.toUri() }
            d.close()
            Log.i(TAG, "PICKENROL dev: feeding ${uris.size} photos")
            com.picfilter.spike.engine.Clusterer.enrolFromUris(
                this, intent?.getStringExtra("name") ?: "PICKTEST", uris)
        }.start()
        "extend"    -> Thread { com.picfilter.spike.engine.Clusterer.extendFromLabels(this) }.start()
        "avatars"   -> Thread {
            com.picfilter.spike.engine.Clusterer.rebuildAvatars(
                this, force = intent?.getStringExtra("force") == "1")
        }.start()
        "memprobe"  -> Thread {
            Pipeline.memProbe(
                this,
                stage = intent?.getStringExtra("stage")?.toIntOrNull() ?: 1,
                n = intent?.getStringExtra("n")?.toIntOrNull() ?: 100,
            )
        }.start()
        "unscore"   -> Thread {
            val d = com.picfilter.spike.data.Db(this)
            Log.i(TAG, "UNSCORE cleared ${d.unscoreRecent(intent?.getStringExtra("n")?.toIntOrNull() ?: 3)}")
            d.close()
        }.start()
        "fixdates"  -> Thread { com.picfilter.spike.engine.Promoter.fixDates(this) }.start()
        "promodiag" -> Thread {
            val d = com.picfilter.spike.data.Db(this)
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
            var missing = 0; var present = 0
            val onDisk = com.picfilter.spike.engine.Promoter.presentDestNames(this)
            d.readableDatabase.rawQuery(
                "SELECT dest_name, at, bytes FROM promotion ORDER BY at", null).use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0)
                    if (n in onDisk) present++ else {
                        missing++
                        Log.i(TAG, "PRDIAG MISSING $n promoted_at=${fmt.format(java.util.Date(c.getLong(1)))} recorded_bytes=${c.getLong(2)}")
                    }
                }
            }
            Log.i(TAG, "PRDIAG present=$present missing=$missing")
            d.close()
        }.start()
        "promolist" -> Thread {
            val d = com.picfilter.spike.data.Db(this)
            for ((_, n) in d.promotedDests()) Log.i(TAG, "PROMOLIST $n")
            d.close()
        }.start()
        "promocheck" -> Thread {
            val d = com.picfilter.spike.data.Db(this)
            val (rows, distinct, dupes) = d.promotionCollisions()
            Log.i(TAG, "PROMOCHK rows=$rows distinct_names=$distinct lost=${rows - distinct}")
            for (x in dupes.take(12)) Log.i(TAG, "PROMOCHK   collision $x")
            d.close()
        }.start()
        "fit"       -> Thread { com.picfilter.spike.engine.Fitter.fit(this) }.start()
        "precision" -> Thread {
            val d = com.picfilter.spike.data.Db(this)
            Log.i(TAG, "PREC --- precision above the 0.55 threshold ---")
            var tk = 0; var tt = 0
            for ((band, keep, total) in d.precisionByBand()) {
                tk += keep; tt += total
                Log.i(TAG, "PREC  %-12s correct %3d/%-3d  %5.1f%%".format(
                    band, keep, total, keep * 100.0 / total))
            }
            if (tt > 0) Log.i(TAG, "PREC  OVERALL      correct %3d/%-3d  %5.1f%%  (false: %d)".format(
                tk, tt, tk * 100.0 / tt, tt - tk))
            Log.i(TAG, "PREC --- by person ---")
            for ((name, keep, total) in d.precisionByPerson())
                Log.i(TAG, "PREC  %-10s correct %3d/%-3d  %5.1f%%".format(
                    name, keep, total, keep * 100.0 / total))
            d.close()
        }.start()
        "calibrate" -> Thread {
            val d = com.picfilter.spike.data.Db(this)
            Log.i(TAG, "CALIB  band            KEEP/total   keep-rate")
            for (r in listOf("FEW_FACES_NO_MATCH", "INDETERMINATE_FACE")) {
                Log.i(TAG, "CALIB  --- $r ---")
                for ((band, keep, total) in d.calibration(r))
                    Log.i(TAG, "CALIB  %-15s %3d/%-3d      %5.1f%%".format(
                        band, keep, total, keep * 100.0 / total))
            }
            d.close()
        }.start()
        "rescueq"   -> Thread {
            val d = com.picfilter.spike.data.Db(this)
            val q = d.rescueQueue()
            Log.i(TAG, "RESCUEQ size=${q.size}")
            for (r in q.take(15)) Log.i(TAG, "RESCUEQ  " +
                "near=${r.nearName ?: "-"} ${r.nearSim?.let { "%.3f".format(it) } ?: "-"} " +
                "faces=${r.faces} indet=${r.indet} ${r.reason}")
            val withNear = q.count { (it.nearSim ?: 0f) >= 0.45f }
            Log.i(TAG, "RESCUEQ items with a near-miss >=0.45: $withNear")
            d.close()
        }.start()
        "exposure"  -> Thread {
            val d = com.picfilter.spike.data.Db(this)
            Log.i(TAG, "EXPOSURE" + d.exposure()); d.close()
        }.start()
        "item"      -> Thread {
            val d = com.picfilter.spike.data.Db(this)
            val id = intent?.getStringExtra("id")?.toLongOrNull() ?: 0L
            Log.i(TAG, "ITEM " + d.itemDetail(id)); d.close()
        }.start()
        "clearcl"   -> Thread {
            val d = com.picfilter.spike.data.Db(this)
            Log.i(TAG, "CLUSTERS reset ${d.clearClusterStatuses()} decisions"); d.close()
        }.start()
        "sweepcl"   -> Thread { com.picfilter.spike.engine.Clusterer.sweepThresholds(this) }.start()
        "recluster" -> Thread {
            com.picfilter.spike.engine.Clusterer.recluster(
                this, intent?.getStringExtra("t")?.toFloatOrNull()
                    ?: com.picfilter.spike.engine.FaceEngine.CLUSTER_THRESHOLD)
        }.start()
        "bootstrap" -> Thread {
            com.picfilter.spike.engine.Clusterer.bootstrap(
                this, intent?.getStringExtra("sample")?.toIntOrNull()
                    ?: com.picfilter.spike.engine.Clusterer.SAMPLE_SIZE)
        }.start()
        "labels"      -> Thread { Pipeline.labels(this) }.start()
        "clearlabels" -> Thread { Pipeline.clearLabels(this) }.start()
        "unlabel"     -> Thread {
            intent?.getStringExtra("id")?.toLongOrNull()?.let { Pipeline.unlabel(this, it) }
        }.start()
        "reset"     -> Thread { Pipeline.reset(this) }.start()
        "pipeline"  -> Thread {
            val lim = intent?.getStringExtra("limit")?.toIntOrNull() ?: 5000
            Pipeline.enroll(this)
            Pipeline.scanMedia(this, lim)
            Pipeline.score(this, lim)
            Pipeline.report(this)
        }.start()
        // ---- diagnostics from Phase 0 ----
        "incidence" -> Thread {
            Incidence.run(this, intent?.getStringExtra("limit")?.toIntOrNull() ?: 609)
        }.start()
        "modelcheck" -> Thread { Embed.modelCheck(this) }.start()
        "embed" -> Thread {
            Embed.run(
                this,
                intent?.getStringExtra("limit")?.toIntOrNull() ?: 609,
                intent?.getStringExtra("px")?.toIntOrNull() ?: 1000,
                intent?.getStringExtra("minbox")?.toIntOrNull() ?: 24,
            )
        }.start()
        "classify" -> Thread {
            Classify.run(this, intent?.getStringExtra("limit")?.toIntOrNull() ?: 609)
        }.start()
        else -> Log.w(TAG, "unknown op=$op")
    }
}

// ---------- permission state (incl. Android 14+ partial access) ----------

// ---------- spike 1: can an ordinary app see WhatsApp's folder? ----------

internal fun MainActivity.scan() {
    val proj = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        MediaStore.MediaColumns.SIZE,
    )
    var total = 0
    var wa = 0
    contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
        "${MediaStore.MediaColumns.DATE_ADDED} DESC"
    )?.use { c ->
        val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        val iOwner = c.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        while (c.moveToNext()) {
            total++
            val path = c.getString(iPath) ?: ""
            val isWa = path.contains("com.whatsapp")
            if (isWa) wa++
            Log.i(
                TAG, "ROW id=${c.getLong(iId)} wa=$isWa name=${c.getString(iName)} " +
                    "bucket=${c.getString(iBucket)} owner=${c.getString(iOwner)} path=$path"
            )
        }
    } ?: Log.e(TAG, "query returned null cursor")

    Log.i(TAG, "SPIKE1 total_images=$total whatsapp_images=$wa")

    // Can we actually OPEN the bytes, not just see the row?
    val uris = waUris()
    var readable = 0
    for (u in uris) {
        try {
            contentResolver.openInputStream(u)?.use { s ->
                val head = ByteArray(2); s.read(head); readable++
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_DENIED $u ${e.javaClass.simpleName}")
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "READ_FNF $u")
        }
    }
    Log.i(TAG, "SPIKE1 whatsapp_bytes_readable=$readable/${uris.size}")
    // Called from a background dispatcher now; the View has to be touched
    // from the thread that owns it.
    runOnUiThread { tv.text = "images=$total whatsapp=$wa readable=$readable" }
}

// ---------- spike 2: batched trash with one system dialog ----------

internal fun MainActivity.waUris(): List<Uri> {
    val out = mutableListOf<Uri>()
    contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(WA_FILTER), null
    )?.use { c ->
        val i = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        while (c.moveToNext()) {
            out += ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(i)
            )
        }
    }
    return out
}

/** One system dialog for a batch; the user confirms, Android keeps them 30 days. */
internal fun MainActivity.trashUris(uris: List<android.net.Uri>) {
    if (uris.isEmpty()) return
    val pi = MediaStore.createTrashRequest(contentResolver, uris, true)
    trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
}

internal fun MainActivity.waUrisIncludingTrashed(): List<Uri> {
    val args = Bundle().apply {
        putString(
            android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        )
        putStringArray(
            android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(WA_FILTER)
        )
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }
    val out = mutableListOf<Uri>()
    contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns._ID), args, null
    )?.use { c ->
        val i = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        while (c.moveToNext()) {
            out += ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(i)
            )
        }
    }
    return out
}

// ---------- spike: gatekeeper write into a Photos-backed folder ----------

internal fun MainActivity.promote() {
    val src = waUris().firstOrNull() ?: run { Log.e(TAG, "no source image"); return }
    val values = android.content.ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "picfilter-keep-${System.currentTimeMillis()}.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PicFilter")
    }
    try {
        val dest = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: run { Log.e(TAG, "SPIKE_WRITE insert returned null"); return }
        var bytes = 0L
        contentResolver.openInputStream(src)!!.use { ins ->
            contentResolver.openOutputStream(dest)!!.use { outs ->
                bytes = ins.copyTo(outs)
            }
        }
        Log.i(TAG, "SPIKE_WRITE ok uri=$dest bytes=$bytes")
        // can we delete our OWN entry with no dialog?
        val n = contentResolver.delete(dest, null, null)
        Log.i(TAG, "SPIKE_WRITE own_delete_no_dialog rows=$n")
    } catch (e: Exception) {
        Log.e(TAG, "SPIKE_WRITE failed ${e.javaClass.simpleName}: ${e.message}")
    }
}

internal fun MainActivity.verify() {
    val args = Bundle().apply {
        putString(
            android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        )
        putStringArray(
            android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(WA_FILTER)
        )
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }
    var trashed = 0; var live = 0
    contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.IS_TRASHED,
        ), args, null
    )?.use { c ->
        val iT = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
        val iN = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        while (c.moveToNext()) {
            val t = c.getInt(iT) == 1
            if (t) trashed++ else live++
            Log.i(TAG, "VERIFY name=${c.getString(iN)} is_trashed=$t")
        }
    }
    Log.i(TAG, "SPIKE2 after_dialog trashed=$trashed live=$live")
    tv.text = "trashed=$trashed live=$live"
}
