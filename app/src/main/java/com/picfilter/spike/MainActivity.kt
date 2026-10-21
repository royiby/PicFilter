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
import coil.load
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

    internal lateinit var trashLauncher: ActivityResultLauncher<IntentSenderRequest>
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
    /** Phase 0 diagnostics write here; see DevOps.kt. */
    internal val tv by lazy { TextView(this) }

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
        val id: Long, val name: String, val subtitle: String,
        val faces: List<String>, val facePath: String?,
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
            val crops = com.picfilter.spike.engine.Clusterer
                .personFaces(this, p.id).ifEmpty { p.thumbs }
            HomePerson(
                p.id, p.name,
                if (p.photos > 0) "${p.photos} photos" else "",
                faces = crops,
                // Chosen here, on the background pass that already reads this,
                // rather than by decoding six crops per person at draw time.
                facePath = com.picfilter.spike.engine.Clusterer.cleanestCrop(crops),
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
            // The header carries the meaning, so the sentence that used to sit
            // under the row -- "Photos of these people are kept automatically"
            // -- is gone. It restated what "Your people" plus the "saved" count
            // beside it already say.
            col.addView(Ui.sectionHeader(this, "Your people", "Add") { managePeople() })
            col.addView(Ui.peopleRow(
                this,
                d.people.map { Ui.Face(it.name, it.subtitle, it.facePath) },
                onPerson = { i -> openPerson(d.people[i]) },
            ))
        } else {
            col.addView(Ui.body(this, "Start by telling PixKin who matters to you."))
        }

        // Saved, the switch that governs it, and what was held back. The middle
        // cell used to be "to review", which counted every item the scorer ever
        // routed to a queue whether or not anyone had looked at it -- so it
        // read 435 next to queues the user had emptied.
        col.addView(Ui.cellRow(this, listOf(
            Ui.statCell(this, d.promoted, "saved"),
            Ui.toggleCell(this, d.watchOn) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        if (d.watchOn) com.picfilter.spike.engine.Watcher.disable(this@MainActivity)
                        else com.picfilter.spike.engine.Watcher.enable(this@MainActivity)
                    }
                    showHome()
                }
            },
            Ui.statCell(this, d.notSaved, "not saved"),
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
        for (u in uris) {
            card.strip.addView(Ui.previewTile(this).also { t ->
                t.load(Uri.parse(u)) { size(GRID_THUMB_PX); scale(coil.size.Scale.FILL) }
            })
        }
        card.strip.visibility = android.view.View.VISIBLE
    }



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
}
