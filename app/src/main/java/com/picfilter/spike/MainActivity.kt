package com.picfilter.spike

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.content.ComponentName
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.picfilter.spike.engine.Pipeline
import com.picfilter.spike.engine.Sweep
import java.io.FileNotFoundException

private const val TAG = "PICFILTER"
private const val WA_FILTER = "%com.whatsapp%"

class MainActivity : ComponentActivity() {

    private lateinit var trashLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var permLauncher: ActivityResultLauncher<Array<String>>
    private var pendingOp: String = "scan"
    private val tv by lazy { TextView(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildHome())

        trashLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { res ->
            val ok = res.resultCode == Activity.RESULT_OK
            Log.i(TAG, "RESULT trash_dialog resultCode=${res.resultCode} accepted=$ok")
            verify()
        }

        permLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            grants.forEach { (k, v) -> Log.i(TAG, "PERM_RESULT $k=$v") }
            reportPermissions()
            dispatch(pendingOp)
        }

        Log.i(TAG, "INTENT action=${intent?.action} extras=${intent?.extras?.keySet()?.toList()} " +
            "op_raw=${intent?.getStringExtra("op")}")
        pendingOp = intent?.getStringExtra("op") ?: "scan"
        Log.i(TAG, "=== PicFilter spike start op=$pendingOp sdk=${Build.VERSION.SDK_INT} ===")
        reportPermissions()

        if (!hasFullImageAccess()) {
            Log.i(TAG, "requesting permissions...")
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.ACCESS_MEDIA_LOCATION,
                )
            )
        } else {
            dispatch(pendingOp)
        }
    }

    /**
     * adb-driven runs often target an already-running instance, in which case
     * Android delivers the intent here instead of calling onCreate — so without
     * this the "op" extra is silently dropped and the default op runs.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)                        // so dispatch() reads the new extras
        val op = intent.getStringExtra("op")
        Log.i(TAG, "=== onNewIntent op=$op extras=${intent.extras?.keySet()?.toList()} ===")
        if (op == null) return
        if (!hasFullImageAccess()) { pendingOp = op; return }
        dispatch(op)
    }

    /** Home screen: the review UI is used by hand, so it needs real buttons. */
    private fun buildHome(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        root.addView(TextView(this).apply { text = "PicFilter"; textSize = 26f })
        root.addView(tv.apply { textSize = 14f })
        fun launch(label: String, mode: String) = Button(this).apply {
            text = label
            textSize = 17f
            setOnClickListener {
                startActivity(
                    android.content.Intent().apply {
                        component = ComponentName(this@MainActivity, ReviewActivity::class.java)
                        putExtra("mode", mode)
                    }
                )
            }
        }
        root.addView(Button(this).apply {
            text = "Set up — find the people you care about"
            textSize = 17f
            setOnClickListener {
                startActivity(android.content.Intent().apply {
                    component = ComponentName(this@MainActivity, OnboardingActivity::class.java)
                })
            }
        })
        root.addView(launch("Rescue review  — find more family photos", "rescue"))
        root.addView(launch("Review items  (golden set)", "golden"))
        root.addView(launch("Verify duplicate groups", "dupes"))
        return root
    }

    private fun dispatch(op: String) {
        when (op) {
            "scan" -> scan()
            "trash" -> trash()
            "untrash" -> untrash()
            "verify" -> verify()
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
            "fixdates"  -> Thread { com.picfilter.spike.engine.Promoter.fixDates(this) }.start()
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
                    this, intent?.getStringExtra("t")?.toFloatOrNull() ?: 0.65f)
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

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasFullImageAccess() = granted(Manifest.permission.READ_MEDIA_IMAGES)

    private fun reportPermissions() {
        val full = granted(Manifest.permission.READ_MEDIA_IMAGES)
        val partial = granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        val loc = granted(Manifest.permission.ACCESS_MEDIA_LOCATION)
        Log.i(TAG, "PERM READ_MEDIA_IMAGES=$full VISUAL_USER_SELECTED=$partial ACCESS_MEDIA_LOCATION=$loc")
        val mode = when {
            full -> "FULL"
            partial -> "PARTIAL (only user-selected items visible!)"
            else -> "NONE"
        }
        Log.i(TAG, "ACCESS_MODE=$mode")
    }

    // ---------- spike 1: can an ordinary app see WhatsApp's folder? ----------

    private fun scan() {
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
        tv.text = "images=$total whatsapp=$wa readable=$readable"
    }

    // ---------- spike 2: batched trash with one system dialog ----------

    private fun waUris(): List<Uri> {
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

    private fun trash() {
        val uris = waUris()
        Log.i(TAG, "SPIKE2 requesting trash for ${uris.size} uris")
        if (uris.isEmpty()) { Log.w(TAG, "nothing to trash"); return }
        val pi = MediaStore.createTrashRequest(contentResolver, uris, true)
        trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    private fun untrash() {
        val uris = waUrisIncludingTrashed()
        Log.i(TAG, "requesting UNtrash for ${uris.size} uris")
        if (uris.isEmpty()) return
        val pi = MediaStore.createTrashRequest(contentResolver, uris, false)
        trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    private fun waUrisIncludingTrashed(): List<Uri> {
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

    private fun promote() {
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

    private fun verify() {
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
}
