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
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.launch
import com.picfilter.spike.engine.Pipeline
import com.picfilter.spike.engine.Sweep
import java.io.FileNotFoundException
import androidx.core.net.toUri

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
    private val vm: HomeViewModel by viewModels()

    private var pendingOp: String? = null
    /** Phase 0 diagnostics write here; see DevOps.kt. */
    internal val tv by lazy { TextView(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeHome()

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
            vm.relinkFaces()
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

    private fun maybeOnboard() {
        if (onboardChecked) return
        onboardChecked = true
        // adb-driven ops are not a user opening the app; never hijack those.
        if (pendingOp != null) return
        lifecycleScope.launch {
            if (!vm.shouldOnboard()) return@launch
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

    /**
     * Enrolling happens in another activity, so the figures are stale by the
     * time the user comes back. A rotation is NOT a return, which is why this
     * refreshes rather than rebuilding blindly: the ViewModel still holds the
     * state, and re-reading it on every configuration change was the cost this
     * screen used to pay.
     */
    override fun onResume() {
        super.onResume()
        if (homeBuilt) vm.refresh() else homeBuilt = true
    }

    /**
     * Draw whatever [HomeViewModel] currently holds.
     *
     * Collection is scoped to STARTED, so a screen in the background is not
     * rebuilding views, and the flow does not hold a reference to an Activity
     * that has gone away.
     */
    private fun observeHome() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.home.collect { data -> if (data != null) setContentView(renderHome(data)) }
            }
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
            col.addView(Ui.sectionHeader(
                this, getString(R.string.home_people_header), getString(R.string.home_add),
                actionDescription = getString(R.string.home_add_description),
            ) { managePeople() })
            col.addView(Ui.peopleRow(
                this,
                d.people.map { Ui.Face(it.name, it.subtitle, it.facePath) },
                onPerson = { i -> openPerson(d.people[i]) },
            ))
        } else {
            col.addView(Ui.body(this, getString(R.string.home_no_people)))
        }

        // Saved, the switch that governs it, and what was held back. The middle
        // cell used to be "to review", which counted every item the scorer ever
        // routed to a queue whether or not anyone had looked at it -- so it
        // read 435 next to queues the user had emptied.
        col.addView(Ui.cellRow(this, listOf(
            Ui.statCell(this, d.promoted, getString(R.string.stat_saved)),
            // The ViewModel owns this: it records the intent in the database
            // as well as scheduling the work, and it survives the rotation
            // that doing it here in a lifecycleScope did not.
            Ui.toggleCell(this, d.watchOn) { vm.toggleWatcher(d.watchOn) },
            Ui.statCell(this, d.notSaved, getString(R.string.stat_not_saved)),
        )))

        // No "Manage people" card: the faces are the control now, and the
        // pinned + beside them is how anyone is added. It only earns a place
        // when there is nobody to tap.
        if (people == 0) {
            col.addView(Ui.actionCard(this,
                getString(R.string.home_setup_title),
                getString(R.string.home_setup_body)) { managePeople() })
        }
        // Every caption counts what is actually inside the card. A card that
        // says a number and then opens an empty list is the specific thing
        // this screen got wrong.
        val q = d.queues
        preview(getString(R.string.card_saved_title),
            if (q.verify == 0) resources.getQuantityString(
                R.plurals.card_saved_all_checked, d.promoted, d.promoted)
            else resources.getQuantityString(
                R.plurals.card_saved_to_check, q.verify, q.verify, d.promoted),
            "verify", d.savedThumbs, col)
        preview(getString(R.string.card_rescue_title),
            if (q.rescue == 0) getString(R.string.card_nothing_left)
            else resources.getQuantityString(R.plurals.card_rescue_unsure, q.rescue, q.rescue),
            "rescue", d.rescueThumbs, col)
        preview(getString(R.string.card_noface_title),
            if (q.noface == 0) getString(R.string.card_nothing_left)
            else resources.getQuantityString(R.plurals.card_noface_body, q.noface, q.noface),
            "noface", d.nofaceThumbs, col)
        preview(getString(R.string.card_junk_title),
            if (q.junk == 0) getString(R.string.card_junk_empty)
            else resources.getQuantityString(R.plurals.card_junk_body, q.junk, q.junk),
            "junk", d.junkThumbs, col)
        preview(getString(R.string.card_dupes_title),
            if (q.dupes == 0) getString(R.string.card_dupes_empty)
            else resources.getQuantityString(R.plurals.card_dupes_body, q.dupes, q.dupes),
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
                t.load(u.toUri()) { size(GRID_THUMB_PX); scale(coil.size.Scale.FILL) }
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
