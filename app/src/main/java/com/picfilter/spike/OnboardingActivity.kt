package com.picfilter.spike

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.picfilter.spike.Ui.setMarginsRelative
import com.picfilter.spike.data.Db
import com.picfilter.spike.engine.Clusterer
import com.picfilter.spike.engine.Promoter

/**
 * First run, end to end: look through the WhatsApp folders, group the faces
 * that keep coming back, let the user claim the ones that are family, then
 * hand over to Google Photos.
 *
 * Two screens carry the weight. The summary warns about recurring people the
 * user did NOT claim, because photos of those people will score as discard
 * candidates — the ice-cream-photo failure, surfaced before it can bite. The
 * backup screen is where the app actually becomes useful: until the folder is
 * backed up and the WhatsApp folders are not, nothing has changed for the user.
 */
private const val PICK_MAX = 10

class OnboardingActivity : ComponentActivity() {

    private lateinit var db: Db
    private lateinit var root: LinearLayout
    private var candidates: List<Db.Candidate> = emptyList()
    private var idx = 0
    private var named = 0
    private var firstRun = false

    /**
     * Which screen of the flow is showing.
     *
     * The step used to live only in which show*() had last been called, so a
     * rotation sent the user back to the intro with their naming progress gone.
     */
    private var step = STEP_INTRO

    /** Read once per visit to the summary, off the main thread. */
    private var skippedCache: List<Db.Candidate>? = null

    /** Every database touch from this screen, serialised; see [DbQueue]. */
    private val dbq by lazy { com.picfilter.spike.data.DbQueue(db) }

    private companion object {
        const val STEP_INTRO = "intro"
        const val STEP_SCAN = "scan"
        const val STEP_NOFACES = "nofaces"
        const val STEP_CANDIDATE = "candidate"
        const val STEP_SUMMARY = "summary"
        const val STEP_BACKUP = "backup"

        const val S_STEP = "step"
        const val S_IDX = "idx"
        const val S_NAMED = "named"
        const val S_FIRST = "first_run"
    }

    /**
     * The photo picker needs no permission at all: the user hands over exactly
     * the images they chose and nothing else.
     */
    private lateinit var pickPhotos: ActivityResultLauncher<PickVisualMediaRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        pickPhotos = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(PICK_MAX)
        ) { uris -> if (uris.isNotEmpty()) promptNameForPicked(uris) }
        root = Ui.screen(this)
        Ui.applyInsets(root, this, extraBottom = 8)
        setContentView(root)
        firstRun = savedInstanceState?.getBoolean(S_FIRST)
            ?: (intent?.getStringExtra("first_run") == "1")

        if (savedInstanceState != null) {
            idx = savedInstanceState.getInt(S_IDX)
            named = savedInstanceState.getInt(S_NAMED)
            restore(savedInstanceState.getString(S_STEP) ?: STEP_INTRO)
            return
        }

        // "step" jumps straight to one screen, for looking at it in isolation.
        val jump = intent?.getStringExtra("step")
        // "resume" skips the scan and works from clusters already extracted —
        // used for adding people later, and for re-grouping experiments.
        if (jump == "backup") {
            showBackup()
        } else if (intent?.getStringExtra("resume") == "1") {
            loadCandidates()
        } else {
            showIntro()
        }
    }

    /**
     * Put the user back where they were after a rotation or process death.
     *
     * A scan still running is re-attached to rather than restarted: [Scan] owns
     * it now, so the progress screen comes back showing where it has got to.
     * One that finished while the screen was gone goes straight to the faces it
     * found, which is where it would have gone anyway.
     */
    private fun restore(saved: String) {
        when (saved) {
            STEP_SCAN -> when {
                Scan.running -> showScanProgress()
                else -> loadCandidates()
            }
            STEP_CANDIDATE -> loadCandidates(keepIndex = true)
            STEP_SUMMARY -> showSummary()
            STEP_BACKUP -> showBackup()
            STEP_NOFACES -> showNoFaces()
            else -> showIntro()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(S_STEP, step)
        outState.putInt(S_IDX, idx)
        outState.putInt(S_NAMED, named)
        outState.putBoolean(S_FIRST, firstRun)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Must happen, or Scan keeps this Activity alive for the whole process.
        Scan.observe(null)
        dbq.closeAfterPending()
    }

    /** Read the candidate clusters off the main thread, then show one. */
    private fun loadCandidates(keepIndex: Boolean = false) {
        lifecycleScope.launch {
            candidates = withContext(dbq.context) { db.candidates(limit = 20, thumbsEach = 6) }
            if (!keepIndex) idx = 0
            if (candidates.isEmpty()) showNoFaces() else showCandidate()
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun title(t: String) = Ui.title(this, t)

    /** Onboarding headlines are the one place the display size is used. */
    private fun headline(t: String) = Ui.title(this, t, Ui.Type.DISPLAY)

    private fun body(t: String) = Ui.body(this, t)

    private fun button(t: String, role: Ui.Btn, onClick: () -> Unit) =
        Ui.wideButton(this, t, role, onClick)

    // ------------------------------------------------------------- 1. intro

    private fun showIntro() {
        step = STEP_INTRO
        root.removeAllViews()
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        // Their own group-chat photos, not a paragraph about them.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingRelative(dp(Ui.MD), dp(Ui.MD), dp(Ui.MD), 0)
            visibility = View.GONE
        }
        col.addView(hero)

        col.addView(headline(getString(R.string.onboard_who_matters)))
        col.addView(body(getString(R.string.onboard_intro_body)))

        val count = Ui.text(this, Ui.Type.BODY, getString(R.string.onboard_reading))
        col.addView(Ui.factRow(this, R.drawable.ic_photo_stack, count))
        col.addView(Ui.factRow(this, R.drawable.ic_timer, getString(R.string.onboard_time)))
        col.addView(Ui.factRow(this, R.drawable.ic_lock, getString(R.string.onboard_private)))

        col.addView(button(getString(R.string.onboard_find), Ui.Btn.PRIMARY) { runScan() })
        // Secondary here: the scan is the headline action on first run.
        col.addView(pickButton(getString(R.string.onboard_pick_instead), Ui.Btn.OUTLINED))
        // Placed now, shown only if there is anything to continue from — the
        // check is a database read and does not belong on this thread.
        val continueBtn = button(getString(R.string.onboard_continue_found), Ui.Btn.OUTLINED) {
            loadCandidates()
        }.apply { visibility = View.GONE }
        col.addView(continueBtn)
        col.addView(button(getString(R.string.onboard_not_now), Ui.Btn.TEXT) { done() })
        root.addView(scroll)

        lifecycleScope.launch {
            val already = withContext(dbq.context) {
                db.candidates(limit = 1, thumbsEach = 1).isNotEmpty()
            }
            if (already) continueBtn.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            val (n, uris) = withContext(Dispatchers.IO) {
                Clusterer.whatsAppPreview(this@OnboardingActivity, 4)
            }
            count.text = if (n > 0)
                resources.getQuantityString(R.plurals.onboard_whatsapp_count, n, n)
            else getString(R.string.onboard_no_whatsapp)
            for (u in uris) hero.addView(
                Ui.previewTile(this@OnboardingActivity).also { it.load(u) })
            if (uris.isNotEmpty()) hero.visibility = View.VISIBLE
        }
    }

    /**
     * Leaving setup counts as having seen it, however little was enrolled —
     * otherwise opening the app again drops the user straight back in here.
     */
    private fun done() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ONBOARDED, true).apply()
        finish()
    }

    // -------------------------------------------------------------- 2. scan

    private fun runScan() {
        Scan.start(applicationContext)
        showScanProgress()
    }

    /**
     * Draw whatever [Scan] is currently doing.
     *
     * Reachable twice: when the scan is started, and when a recreated Activity
     * finds one already running.
     */
    private fun showScanProgress() {
        step = STEP_SCAN
        root.removeAllViews()
        root.addView(title(getString(R.string.onboard_looking)))
        val status = body("${Scan.phase}\u2026")
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            isIndeterminate = false
            max = 100
            trackCornerRadius = dp(4)
            setIndicatorColor(Ui.primary(this@OnboardingActivity))
            trackColor = Ui.surfaceVariant(this@OnboardingActivity)
            trackThickness = dp(8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMarginsRelative(dp(24), dp(8), dp(24), dp(8)) }
        }
        bar.progress = Scan.percent
        root.addView(bar)
        root.addView(status)
        root.addView(body(getString(R.string.onboard_scan_keeps_going)))

        Scan.observe {
            if (Scan.running) {
                bar.progress = Scan.percent
                status.text = getString(
                    R.string.onboard_progress, Scan.phase, Scan.done, Scan.total)
            } else {
                Scan.observe(null)
                Scan.consumeFinished()
                loadCandidates()
            }
        }
        // The scan may have finished between start and here.
        if (!Scan.running) {
            Scan.observe(null)
            Scan.consumeFinished()
            loadCandidates()
        }
    }

    private fun showNoFaces() {
        step = STEP_NOFACES
        root.removeAllViews()
        root.addView(title(getString(R.string.onboard_no_faces_title)))
        root.addView(body(getString(R.string.onboard_no_faces_body)))
        root.addView(pickButton(getString(R.string.onboard_pick_someone)))
        root.addView(button(getString(R.string.onboard_look_again), Ui.Btn.OUTLINED) { runScan() })
        root.addView(button(getString(R.string.onboard_done), Ui.Btn.TEXT) { done() })
    }

    /** The picker, offered wherever someone might want to add a person. */
    private fun pickButton(label: String, role: Ui.Btn = Ui.Btn.PRIMARY) = button(label, role) {
        pickPhotos.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                .build())
    }

    // --------------------------------------------------------- 3. candidates

    private fun showCandidate() {
        if (idx >= candidates.size) { showSummary(); return }
        step = STEP_CANDIDATE
        val c = candidates[idx]
        root.removeAllViews()
        root.addView(Ui.text(this, Ui.Type.CAPTION,
            getString(R.string.onboard_candidate_progress, idx + 1, candidates.size, named),
            R.color.pk_on_surface_variant).apply {
            setPaddingRelative(dp(Ui.MD), dp(Ui.MD), dp(Ui.MD), 0)
        })
        root.addView(title(getString(R.string.onboard_know_person)))

        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (path in c.thumbs) {
            strip.addView(Ui.thumb(this, 96).also { it.load("file://$path") })
        }
        root.addView(android.widget.HorizontalScrollView(this).apply { addView(strip) })

        root.addView(Ui.factRow(this, R.drawable.ic_photo_stack,
            resources.getQuantityString(R.plurals.onboard_in_photos, c.size, c.size)))
        root.addView(Ui.factRow(this, R.drawable.ic_lock, getString(R.string.onboard_claim_note)))
        root.addView(button(getString(R.string.onboard_someone_i_care), Ui.Btn.PRIMARY) {
            promptName(c)
        })
        root.addView(button(getString(R.string.onboard_not_important), Ui.Btn.OUTLINED) {
            decide(c, "SKIPPED")
        })
        root.addView(button(getString(R.string.onboard_not_same_person), Ui.Btn.TEXT) {
            decide(c, "MIXED")
        })
    }

    private fun promptName(c: Db.Candidate) {
        val input = EditText(this).apply {
            hint = getString(R.string.onboard_name_hint_optional)
            setPaddingRelative(dp(20), dp(20), dp(20), dp(20))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.onboard_who_is_this)
            .setMessage(R.string.onboard_name_help)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val typed = input.text.toString().trim()
                val name = typed.ifEmpty { getString(R.string.onboard_default_person, named + 1) }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        Clusterer.enrolCluster(this@OnboardingActivity, c.id, name)
                    }
                    named++; idx++; showCandidate()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Enrol from photos the user picked, rather than from a cluster the scan
     * found. Faster to reach -- seconds against four minutes -- at some cost in
     * recall, which is why it asks for a few varied photos rather than one.
     */
    private fun promptNameForPicked(uris: List<android.net.Uri>) {
        val input = EditText(this).apply {
            hint = getString(R.string.onboard_name_hint)
            setPaddingRelative(dp(20), dp(20), dp(20), dp(20))
        }
        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(
                R.plurals.onboard_who_in_photos, uris.size, uris.size))
            .setMessage(getString(R.string.onboard_pick_help))
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val typed = input.text.toString().trim()
                val name = typed.ifEmpty { getString(R.string.onboard_default_person, named + 1) }
                val busy = AlertDialog.Builder(this)
                    .setMessage(getString(R.string.onboard_reading_photos))
                    .setCancelable(false).show()
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        try {
                            Clusterer.enrolFromUris(this@OnboardingActivity, name, uris)
                        } catch (e: Exception) {
                            Clusterer.PickOutcome(-1, 0, uris.size, 0)
                        }
                    }
                    busy.dismiss()
                    if (r.ok) named++
                    showPickResult(name, r)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showPickResult(name: String, r: Clusterer.PickOutcome) {
        val skipped = buildString {
            if (r.noFace > 0) append(resources.getQuantityString(
                R.plurals.onboard_no_face_clear, r.noFace, r.noFace))
            if (r.tooManyFaces > 0) append(resources.getQuantityString(
                R.plurals.onboard_too_many_faces, r.tooManyFaces, r.tooManyFaces, name))
        }
        val msg = if (r.ok)
            resources.getQuantityString(R.plurals.onboard_saved_from, r.used, name, r.used) +
                skipped +
                if (r.used < 3) getString(R.string.onboard_more_photos_help, name) else ""
        else getString(R.string.onboard_could_not_use) + skipped

        AlertDialog.Builder(this)
            .setTitle(if (r.ok) getString(R.string.onboard_added, name)
                      else getString(R.string.onboard_nothing_to_save))
            .setMessage(msg)
            .setPositiveButton(R.string.onboard_add_someone_else) { _, _ ->
                pickPhotos.launch(
                    PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        .build())
            }
            .setNegativeButton(
                if (named > 0) R.string.onboard_done else R.string.onboard_back) { _, _ ->
                if (named > 0) showSummary() else showIntro()
            }
            .show()
    }

    private fun decide(c: Db.Candidate, status: String) {
        lifecycleScope.launch {
            withContext(dbq.context) { db.setClusterStatus(c.id, status, null) }
            idx++; showCandidate()
        }
    }

    // ----------------------------------------------------------- 4. summary

    private fun showSummary() {
        step = STEP_SUMMARY
        root.removeAllViews()
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        col.addView(title(resources.getQuantityString(R.plurals.onboard_enrolled, named, named)))

        // The part that matters: who did they NOT claim? Read on this thread
        // only because the whole screen is already built synchronously; the
        // decode below is what actually cost frames and that is now scoped.
        val skipped = skippedCache
        if (skipped == null) {
            lifecycleScope.launch {
                skippedCache = withContext(dbq.context) { db.skippedClusters(minSize = 5) }
                showSummary()
            }
            root.addView(scroll)
            return
        }
        if (skipped.isNotEmpty()) {
            col.addView(Ui.text(this, Ui.Type.TITLE_SMALL,
                getString(R.string.onboard_second_look),
                R.color.pk_warning).apply {
                setPaddingRelative(dp(Ui.MD), dp(Ui.SM), dp(Ui.MD), dp(Ui.XXS))
            })
            col.addView(body(getString(R.string.onboard_second_look_body)))
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (s in skipped.take(10)) {
                val p = s.thumbs.firstOrNull() ?: continue
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { setMarginsRelative(0, 0, dp(8), 0) }
                }
                cell.addView(Ui.thumb(this, 80).also { it.load("file://$p") })
                cell.addView(Ui.text(this, Ui.Type.CAPTION, "${s.size} photos",
                    R.color.pk_on_surface_variant))
                strip.addView(cell)
            }
            col.addView(android.widget.HorizontalScrollView(this).apply { addView(strip) })
            col.addView(button(getString(R.string.onboard_go_back_add), Ui.Btn.PRIMARY) {
                loadCandidates()
            })
        } else if (named > 0) {
            col.addView(body(getString(R.string.onboard_none_unclaimed)))
        }

        if (named == 0) {
            col.addView(body(getString(R.string.onboard_nobody_warning)))
        }

        col.addView(button(
            if (named > 0) "Next: where the photos go" else "Finish setup", Ui.Btn.PRIMARY
        ) { showBackup() })
        if (!firstRun) col.addView(button("Done", Ui.Btn.TEXT) { done() })
        root.addView(scroll)
    }

    // ------------------------------------------------- 5. hand to the backup

    /**
     * The last step, and the one that makes any of this matter: Google Photos
     * backs up the folders you pick, so PixKin's folder has to be one of them
     * and the WhatsApp folders have to stop being one.
     */
    private fun showBackup() {
        step = STEP_BACKUP
        root.removeAllViews()
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        col.addView(headline("Two switches, and you're set"))
        col.addView(body("In Google Photos, back up the folder PixKin saves to and stop " +
            "backing up the WhatsApp ones."))

        // Name the folders the way Google Photos lists them, not the way PixKin
        // thinks of them: the user is about to look for these exact rows.
        col.addView(Ui.stateRow(this, R.drawable.ic_logo_mark, "PicFilter",
            "Back up", good = true, tintIcon = false))
        col.addView(Ui.stateRow(this, R.drawable.ic_folder, "WhatsApp Images",
            "Don't", good = false))

        col.addView(Ui.factRow(this, R.drawable.ic_photo_stack,
            getString(R.string.backup_photos_settings)))

        val status = Ui.text(this, Ui.Type.BODY,
            getString(R.string.backup_making_folder, Promoter.DEST_LABEL))
        col.addView(Ui.factRow(this, R.drawable.ic_folder, status))
        // Google Photos only lists a folder that exists, and on a fresh install
        // nothing has been rescued into it yet.
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                Promoter.ensureDestFolder(this@OnboardingActivity)
            }
            status.text = if (ok)
                getString(R.string.backup_folder_exists, Promoter.DEST_LABEL)
            else getString(R.string.backup_folder_later)
        }

        col.addView(body(getString(R.string.backup_already_safe)))

        val photos = packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
        if (photos != null) col.addView(button(
            getString(R.string.backup_open_photos), Ui.Btn.PRIMARY) {
            startActivity(photos)
        })
        col.addView(button(getString(R.string.onboard_done),
            if (photos == null) Ui.Btn.PRIMARY else Ui.Btn.TEXT) { done() })
        root.addView(scroll)
    }

}
