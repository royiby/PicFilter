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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.picfilter.spike.engine.Pipeline
import com.picfilter.spike.engine.Sweep
import java.io.FileNotFoundException

private const val TAG = "PICFILTER"
private const val WA_FILTER = "%com.whatsapp%"
const val PREFS = "pixkin"
const val KEY_ONBOARDED = "onboarding_done"

class MainActivity : ComponentActivity() {

    private lateinit var trashLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var permLauncher: ActivityResultLauncher<Array<String>>
    /**
     * The op an adb run asked for, or null when a person just opened the app.
     *
     * This used to default to "scan" -- the Phase 0 diagnostic -- so opening
     * PixKin from the launcher queried every image on the device and logged
     * them, on the main thread. StrictMode measured it at 1,460 ms per launch,
     * for a result nobody reads outside a spike. A user opening the app wants
     * the home screen and nothing else.
     */
    private var pendingOp: String? = null
    private val tv by lazy { TextView(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()

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
            if (hasFullImageAccess()) maybeOnboard()
            pendingOp?.let { dispatch(it) }
        }

        Log.i(TAG, "INTENT action=${intent?.action} extras=${intent?.extras?.keySet()?.toList()} " +
            "op_raw=${intent?.getStringExtra("op")}")
        pendingOp = intent?.getStringExtra("op")
        Log.i(TAG, "=== PixKin start op=${pendingOp ?: "(none, home screen)"} " +
            "sdk=${Build.VERSION.SDK_INT} ===")
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
            maybeOnboard()
            relinkFaces()
            pendingOp?.let { dispatch(it) }
        }
    }

    /**
     * With nobody enrolled there is nothing for PixKin to protect, so the home
     * screen has nothing to say — the app is new, whatever the install date.
     * Send the user to setup instead of a screen of zeroes.
     *
     * Once they have been through it the flag stays set, so someone who
     * deliberately enrolled no one is not dragged back in on every launch.
     */
    private var onboardChecked = false
    private var avatarsChecked = false

    /**
     * Put a face back on anyone showing an initial.
     *
     * The crops live in the app's own storage while the database only holds
     * their paths, so clearing app data or reinstalling takes the files and
     * leaves the paths — and the people row quietly falls back to initials with
     * nothing appearing broken. Rebuilding reads the library, which survives
     * all of that.
     *
     * Only for people who have no face AND have photos to rebuild it from, so
     * someone with no matches yet does not make every launch pay for a face
     * detector. Once per process, off the main thread, and the home screen is
     * rebuilt only if something was actually recovered.
     */
    private fun maybeRebuildAvatars(persons: List<HomePerson>) {
        if (avatarsChecked || persons.isEmpty()) return
        avatarsChecked = true
        // faces is empty only when there is genuinely no crop on disk; see the
        // invariant in readHome.
        if (persons.none { it.faces.isEmpty() }) return
        lifecycleScope.launch {
            val rebuilt = withContext(Dispatchers.IO) {
                try {
                    val db = com.picfilter.spike.data.Db(this@MainActivity)
                    val missing = persons.count { p ->
                        p.faces.isEmpty() && db.matchedItemsFor(p.id, 1).isNotEmpty()
                    }
                    db.close()
                    if (missing == 0) return@withContext 0
                    Log.i(TAG, "AVATAR $missing of ${persons.size} have no face to show; rebuilding")
                    com.picfilter.spike.engine.Clusterer.rebuildAvatars(this@MainActivity)
                } catch (e: Exception) {
                    Log.w(TAG, "AVATAR rebuild: ${e.javaClass.simpleName}: ${e.message}")
                    0
                }
            }
            // No isFinishing check needed: lifecycleScope has already cancelled
            // this coroutine if the screen went away.
            if (rebuilt > 0) showHome()
        }
    }

    private fun maybeOnboard() {
        if (onboardChecked) return
        onboardChecked = true
        // adb-driven ops are not a user opening the app; never hijack those.
        if (pendingOp != null) return
        lifecycleScope.launch {
            val go = withContext(Dispatchers.IO) {
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                if (prefs.getBoolean(KEY_ONBOARDED, false)) return@withContext false
                val db = com.picfilter.spike.data.Db(this@MainActivity)
                val people = try { db.enrolledCount() } catch (e: Exception) { 0 }
                db.close()
                people == 0
            }
            if (!go) return@launch
            Log.i(TAG, "FIRSTRUN nobody enrolled - starting onboarding")
            startActivity(android.content.Intent().apply {
                component = ComponentName(this@MainActivity, OnboardingActivity::class.java)
                putExtra("first_run", "1")
            })
        }
    }

    /**
     * Enrolling happens in another activity, so the home built in onCreate is
     * already stale by the time the user comes back — no people row, and a
     * "Set up" card for people they just added. Rebuild on every return.
     */
    private var homeBuilt = false

    override fun onResume() {
        super.onResume()
        if (homeBuilt) showHome() else homeBuilt = true
    }

    /**
     * Read the home screen's figures off the main thread, then draw them on it.
     *
     * This used to be one synchronous buildHome(): eight database queries, a
     * directory listing per enrolled person, and two blocking WorkManager
     * futures, all inside onCreate and again on every onResume. It never looked
     * broken -- the database is a megabyte and the phone has eight cores -- but
     * it is disk work on the thread that draws frames, and StrictMode in
     * [PixKinApp] now says so.
     *
     * lifecycleScope, so a slow read cannot come back to an Activity that has
     * already gone away. The previous version guarded that with an isFinishing
     * check in one of the three places that needed one.
     */
    private fun showHome() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { readHome() }
            setContentView(renderHome(data))
            maybeRebuildAvatars(data.people)
        }
    }

    /** Once per launch: anyone whose face crops a re-scan orphaned gets them
     *  back from the new clusters, and the people row redraws if that worked. */
    private fun relinkFaces() {
        lifecycleScope.launch {
            val fixed = withContext(Dispatchers.IO) {
                try { com.picfilter.spike.engine.Clusterer.relinkFaces(this@MainActivity) }
                catch (e: Exception) { 0 }
            }
            if (fixed > 0) showHome()
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

    /** Everything the home screen shows, read in one background pass. */
    private data class HomePerson(
        val id: Long, val name: String, val subtitle: String, val faces: List<String>,
    )

    private data class HomeData(
        val promoted: Int,
        val notSaved: Int,
        val undecided: Int,
        val queues: com.picfilter.spike.data.Db.QueueSizes,
        val people: List<HomePerson>,
        val watchOn: Boolean,
        val armed: Boolean,
        val savedThumbs: List<String>,
        val rescueThumbs: List<String>,
        val nofaceThumbs: List<String>,
        val junkThumbs: List<String>,
        val dupeThumbs: List<String>,
    )

    /** Disk and WorkManager only. Must not touch a View. */
    private fun readHome(): HomeData {
        val db = com.picfilter.spike.data.Db(this)
        val promoted = try { db.promotionCount() } catch (e: Exception) { 0 }
        val notSaved = try { db.notSavedCount() } catch (e: Exception) { 0 }
        val undecided = try { db.undecidedCount() } catch (e: Exception) { 0 }
        val persons = try { db.enrolledPeople() } catch (e: Exception) { emptyList() }
        val queues = try { db.queueSizes() } catch (e: Exception) {
            com.picfilter.spike.data.Db.QueueSizes(0, 0, 0, 0, 0)
        }
        val junkThumbs = try { db.junkQueue(limit = 5).map { it.uri } } catch (e: Exception) { emptyList() }
        val dupeThumbs = try { db.dupePreview(limit = 5) } catch (e: Exception) { emptyList() }
        val savedThumbs = try { db.promotedForReview(limit = 5).map { it.uri } } catch (e: Exception) { emptyList() }
        val rescueThumbs = try { db.rescueQueue(limit = 5).map { it.uri } } catch (e: Exception) { emptyList() }
        val nofaceThumbs = try {
            db.rescueQueue(listOf("NO_FACE_PLAIN"), limit = 5).map { it.uri }
        } catch (e: Exception) { emptyList() }
        db.close()

        // A re-scan wipes the cluster crops, so prefer the copies enrolment
        // kept; fall back for people enrolled before that existed. This lists
        // a directory per person, which is why it belongs here and not in the
        // render pass.
        //
        // BOTH sources must contain only files that exist. personFaces lists a
        // directory so it already does; personThumbs reads rows and now filters
        // them. That invariant is what [maybeRebuildAvatars] tests to decide
        // whether anyone needs repairing, and when it was broken -- the row
        // list went in unfiltered -- every person looked as though they already
        // had a face, so the repair never ran and the row stayed on initials.
        val people = persons.map { p ->
            HomePerson(
                p.id, p.name,
                if (p.photos > 0) "${p.photos} photos" else "",
                com.picfilter.spike.engine.Clusterer.personFaces(this, p.id).ifEmpty { p.thumbs },
            )
        }

        val watchOn = try { com.picfilter.spike.engine.Watcher.isEnabled(this) } catch (e: Exception) { false }
        // A force-stop or a failed run can break the content-trigger chain while
        // the periodic net keeps going. Re-arm it whenever the app is opened.
        val armed = try { com.picfilter.spike.engine.Watcher.isArmed(this) } catch (e: Exception) { false }
        if (watchOn && !armed) com.picfilter.spike.engine.Watcher.enable(this)

        return HomeData(
            promoted = promoted,
            notSaved = notSaved,
            undecided = undecided,
            queues = queues,
            people = people,
            watchOn = watchOn,
            armed = armed,
            savedThumbs = savedThumbs,
            rescueThumbs = rescueThumbs,
            nofaceThumbs = nofaceThumbs,
            junkThumbs = junkThumbs,
            dupeThumbs = dupeThumbs,
        )
    }

    /** Home. Photos-style: the people row, a stat row, then tappable cards. */
    private fun renderHome(d: HomeData): android.view.View {
        val people = d.people.size
        val col = Ui.screen(this)
        val managePeople = {
            startActivity(android.content.Intent().apply {
                component = ComponentName(this@MainActivity, OnboardingActivity::class.java)
                putExtra("resume", if (people == 0) "0" else "1")
            })
        }
        if (people > 0) {
            col.addView(Ui.sectionHeader(this, "People", "Add") { managePeople() })
            col.addView(Ui.peopleRow(
                this,
                d.people.map { Ui.Face(it.name, it.subtitle, it.faces) },
                onPerson = { i -> openPerson(d.people[i]) },
            ))
            col.addView(Ui.body(this, "Photos of these people are kept automatically."))
        } else {
            col.addView(Ui.body(this, "Start by telling PixKin who matters to you."))
        }

        // Saved, the switch that governs it, and what was held back. The middle
        // cell used to be "to review", which counted every item the scorer ever
        // routed to a queue whether or not anyone had looked at it -- so it
        // read 435 next to queues the user had emptied.
        col.addView(Ui.cellRow(this, listOf(
            Ui.statCell(this, "${d.promoted}", "saved"),
            Ui.toggleCell(this, d.watchOn) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        if (d.watchOn) com.picfilter.spike.engine.Watcher.disable(this@MainActivity)
                        else com.picfilter.spike.engine.Watcher.enable(this@MainActivity)
                    }
                    showHome()
                }
            },
            Ui.statCell(this, "${d.notSaved}", "not saved"),
        )))

        // No "Manage people" card: the faces are the control now, and the
        // pinned + beside them is how anyone is added. It only earns a place
        // when there is nobody to tap.
        if (people == 0) {
            col.addView(Ui.actionCard(this,
                "Set up — find your people",
                "Scans your library and finds recurring faces") { managePeople() })
        }
        // Every caption counts what is actually inside the card. A card that
        // says a number and then opens an empty list is the specific thing
        // this screen got wrong.
        val q = d.queues
        preview("Saved for you",
            if (q.verify == 0) "All ${d.promoted} checked"
            else "${q.verify} of ${d.promoted} still to check",
            "verify", d.savedThumbs, col)
        preview("Find more family photos",
            if (q.rescue == 0) "Nothing left to look at"
            else "${q.rescue} we were unsure about",
            "rescue", d.rescueThumbs, col)
        preview("Photos with no faces",
            if (q.noface == 0) "Nothing left to look at"
            else "${q.noface} places, drawings, documents",
            "noface", d.nofaceThumbs, col)
        preview("Suspected junk",
            if (q.junk == 0) "Nothing waiting"
            else "${q.junk} look like forwards, memes or screenshots",
            "junk", d.junkThumbs, col)
        preview("Suspected duplicates",
            if (q.dupes == 0) "No near-identical copies found"
            else "${q.dupes} ${if (q.dupes == 1) "group" else "groups"} of near-identical copies",
            "dupes", d.dupeThumbs, col)

        // The logo lockup is the home screen's top bar: it carries the brand,
        // so there is no title text and no colored bar.
        val root = Ui.screen(this)
        root.addView(Ui.logoBar(this))
        root.addView(android.widget.ScrollView(this).apply {
            addView(col)
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        Ui.applyInsets(root, this, extraBottom = Ui.SM)
        tv.visibility = android.view.View.GONE
        return root
    }

    /** Tapping a face opens that person: their photos, and the way out. */
    private fun openPerson(p: HomePerson) {
        startActivity(android.content.Intent().apply {
            component = ComponentName(this@MainActivity, PersonActivity::class.java)
            putExtra("person_id", p.id)
            putExtra("person_name", p.name)
        })
    }

    /** A card that previews what is inside it, so junk and duplicates are
     *  visible without opening the review screen first. */
    private fun preview(
        label: String, supporting: String, mode: String,
        uris: List<String>, col: LinearLayout,
    ) {
        val card = Ui.previewCard(this, label, supporting) {
            startActivity(android.content.Intent().apply {
                component = ComponentName(this@MainActivity, ReviewActivity::class.java)
                putExtra("mode", mode)
            })
        }
        col.addView(card.view)
        if (uris.isEmpty()) return
        val tiles = uris.map { Ui.previewTile(this).also { t -> card.strip.addView(t) } }
        lifecycleScope.launch {
            for ((i, u) in uris.withIndex()) {
                val bmp = withContext(Dispatchers.IO) { mediaThumb(u) } ?: continue
                tiles[i].setImageBitmap(bmp)
                card.strip.visibility = android.view.View.VISIBLE
            }
        }
    }

    /** MediaStore's own thumbnail — cheap, and already the right size. */
    private fun mediaThumb(uri: String): android.graphics.Bitmap? = try {
        contentResolver.loadThumbnail(Uri.parse(uri), android.util.Size(256, 256), null)
    } catch (e: Exception) { null }

    /**
     * The adb-driven diagnostics.
     *
     * Most of these stay on a bare Thread on purpose, which is the one place in
     * the app that is still right. They are fire-and-forget measurements that
     * only write to logcat, and a long score or bootstrap must finish even if
     * the screen goes away -- lifecycleScope would cancel it halfway and report
     * nothing. The two that come back to the UI are scoped; see below.
     */
    private fun dispatch(op: String) {
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
                        this@MainActivity, hrs, dryRun = !live)
                }
                if (live && gone.isNotEmpty()) {
                    Log.i(TAG, "RECLAIM asking to trash ${gone.size} local copies")
                    trashUris(gone.map { it.first })
                }
            }
            "tidydest"  -> lifecycleScope.launch {
                val live = intent?.getStringExtra("live") == "1"
                val extras = withContext(Dispatchers.IO) {
                    com.picfilter.spike.engine.Promoter.tidyDest(this@MainActivity, dryRun = !live)
                    if (live) com.picfilter.spike.engine.Promoter.redundantCopies(this@MainActivity)
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
                component = ComponentName(this@MainActivity, OnboardingActivity::class.java)
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
                val uris = d.matchedItemsFor(pid, n).map { android.net.Uri.parse(it.second) }
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
        // Called from a background dispatcher now; the View has to be touched
        // from the thread that owns it.
        runOnUiThread { tv.text = "images=$total whatsapp=$wa readable=$readable" }
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

    /** One system dialog for a batch; the user confirms, Android keeps them 30 days. */
    private fun trashUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val pi = MediaStore.createTrashRequest(contentResolver, uris, true)
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
