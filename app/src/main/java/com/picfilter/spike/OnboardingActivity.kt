package com.picfilter.spike

import android.app.AlertDialog
import android.graphics.BitmapFactory
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        firstRun = intent?.getStringExtra("first_run") == "1"
        // "step" jumps straight to one screen, for looking at it in isolation.
        val step = intent?.getStringExtra("step")
        // "resume" skips the scan and works from clusters already extracted —
        // used for adding people later, and for re-grouping experiments.
        if (step == "backup") {
            showBackup()
        } else if (intent?.getStringExtra("resume") == "1") {
            candidates = db.candidates(limit = 20, thumbsEach = 6)
            idx = 0
            if (candidates.isEmpty()) showNoFaces() else showCandidate()
        } else {
            showIntro()
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
        root.removeAllViews()
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        // Their own group-chat photos, not a paragraph about them.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(Ui.MD), dp(Ui.MD), dp(Ui.MD), 0)
            visibility = View.GONE
        }
        col.addView(hero)

        col.addView(headline("Who matters to you?"))
        col.addView(body("Group chats fill your phone with other people's photos. " +
            "PixKin keeps the ones your people are in."))

        val count = Ui.text(this, Ui.Type.BODY, "Reading your WhatsApp folders\u2026")
        col.addView(Ui.factRow(this, R.drawable.ic_photo_stack, count))
        col.addView(Ui.factRow(this, R.drawable.ic_timer,
            "About four minutes to find them, or seconds if you pick photos yourself"))
        col.addView(Ui.factRow(this, R.drawable.ic_lock, "Nothing leaves this phone"))

        col.addView(button("Find my people", Ui.Btn.PRIMARY) { runScan() })
        col.addView(button("Pick photos of someone instead", Ui.Btn.OUTLINED) {
            pickPhotos.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build())
        })
        if (db.candidates(limit = 1, thumbsEach = 1).isNotEmpty()) {
            col.addView(button("Continue with faces already found", Ui.Btn.OUTLINED) {
                candidates = db.candidates(limit = 20, thumbsEach = 6)
                idx = 0
                if (candidates.isEmpty()) showNoFaces() else showCandidate()
            })
        }
        col.addView(button("Not now", Ui.Btn.TEXT) { done() })
        root.addView(scroll)

        Thread {
            val (n, uris) = Clusterer.whatsAppPreview(this, 4)
            val bmps = uris.mapNotNull { u ->
                try {
                    contentResolver.loadThumbnail(u, android.util.Size(256, 256), null)
                } catch (e: Exception) { null }
            }
            runOnUiThread {
                count.text = if (n > 0) "$n photos from WhatsApp on this phone"
                             else "No WhatsApp photos found on this phone"
                for (b in bmps) hero.addView(Ui.previewTile(this).apply { setImageBitmap(b) })
                if (bmps.isNotEmpty()) hero.visibility = View.VISIBLE
            }
        }.start()
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
        root.removeAllViews()
        root.addView(title("Looking for faces"))
        val status = body("Starting…")
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            isIndeterminate = false
            max = 100
            trackCornerRadius = dp(4)
            setIndicatorColor(Ui.primary(this@OnboardingActivity))
            trackColor = Ui.surfaceVariant(this@OnboardingActivity)
            trackThickness = dp(8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(24), dp(8), dp(24), dp(8)) }
        }
        root.addView(bar)
        root.addView(status)
        root.addView(body("Keep the screen on until this finishes. Nothing leaves the phone."))

        Thread {
            Clusterer.bootstrap(this, Clusterer.SAMPLE_SIZE) { done, total, phase ->
                val pct = if (total > 0) done * 100 / total else 0
                runOnUiThread {
                    bar.progress = pct
                    status.text = "$phase…  $done / $total"
                }
            }
            runOnUiThread {
                candidates = db.candidates(limit = 20, thumbsEach = 6)
                idx = 0
                if (candidates.isEmpty()) showNoFaces() else showCandidate()
            }
        }.start()
    }

    private fun showNoFaces() {
        root.removeAllViews()
        root.addView(title("No recurring faces found"))
        root.addView(body(
            "Nothing in the sample had enough clear, front-facing photos of the same person.\n\n" +
            "That usually means these folders are mostly forwarded pictures rather than photos " +
            "of people you know. You can try again later, once there are more."
        ))
        root.addView(button("Done", Ui.Btn.TEXT) { done() })
    }

    // --------------------------------------------------------- 3. candidates

    private fun showCandidate() {
        if (idx >= candidates.size) { showSummary(); return }
        val c = candidates[idx]
        root.removeAllViews()
        root.addView(Ui.text(this, Ui.Type.CAPTION,
            "${idx + 1} of ${candidates.size}   ·   $named named",
            R.color.pk_on_surface_variant).apply {
            setPadding(dp(Ui.MD), dp(Ui.MD), dp(Ui.MD), 0)
        })
        root.addView(title("Do you know this person?"))

        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in c.thumbs) {
            val bmp = try { BitmapFactory.decodeFile(p) } catch (e: Exception) { null } ?: continue
            strip.addView(Ui.thumb(this, 96).apply { setImageBitmap(bmp) })
        }
        val scroll = android.widget.HorizontalScrollView(this).apply { addView(strip) }
        root.addView(scroll)

        root.addView(Ui.factRow(this, R.drawable.ic_photo_stack, "In ${c.size} of your photos"))
        root.addView(Ui.factRow(this, R.drawable.ic_lock,
            "Claim them and their photos are always kept"))
        root.addView(button("This is someone I care about", Ui.Btn.PRIMARY) { promptName(c) })
        root.addView(button("Not important", Ui.Btn.OUTLINED) { decide(c, "SKIPPED") })
        root.addView(button("These aren't the same person", Ui.Btn.TEXT) { decide(c, "MIXED") })
    }

    private fun promptName(c: Db.Candidate) {
        val input = EditText(this).apply {
            hint = "Name (optional)"
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        AlertDialog.Builder(this)
            .setTitle("Who is this?")
            .setMessage("A name just helps you recognise them later — you can leave it blank.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val typed = input.text.toString().trim()
                val name = if (typed.isNotEmpty()) typed else "Person ${named + 1}"
                Thread {
                    Clusterer.enrolCluster(this, c.id, name)
                    runOnUiThread { named++; idx++; showCandidate() }
                }.start()
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
            hint = "Name"
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        AlertDialog.Builder(this)
            .setTitle("Who is in these ${uris.size} photos?")
            .setMessage("Photos where this person is the only clear face work best. " +
                "A few taken at different times beat one good portrait.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val typed = input.text.toString().trim()
                val name = if (typed.isNotEmpty()) typed else "Person ${named + 1}"
                val busy = AlertDialog.Builder(this)
                    .setMessage("Reading those photos\u2026").setCancelable(false).show()
                Thread {
                    val r = try {
                        Clusterer.enrolFromUris(this, name, uris)
                    } catch (e: Exception) {
                        Clusterer.PickOutcome(-1, 0, uris.size, 0)
                    }
                    runOnUiThread {
                        busy.dismiss()
                        if (r.ok) named++
                        showPickResult(name, r)
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPickResult(name: String, r: Clusterer.PickOutcome) {
        val skipped = buildString {
            if (r.noFace > 0) append("\n${r.noFace} had no face clear enough.")
            if (r.tooManyFaces > 0)
                append("\n${r.tooManyFaces} had more than one clear face, so we could not " +
                    "tell which one is $name.")
        }
        val msg = if (r.ok)
            "Saved $name from ${r.used} photo${if (r.used == 1) "" else "s"}.$skipped" +
                if (r.used < 3) "\n\nMore photos of $name would help — with only a " +
                    "couple we miss roughly one face in five." else ""
        else "Could not use any of those photos.$skipped"

        AlertDialog.Builder(this)
            .setTitle(if (r.ok) "Added $name" else "Nothing to save")
            .setMessage(msg)
            .setPositiveButton("Add someone else") { _, _ ->
                pickPhotos.launch(
                    PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        .build())
            }
            .setNegativeButton(if (named > 0) "Done" else "Back") { _, _ ->
                if (named > 0) showSummary() else showIntro()
            }
            .show()
    }

    private fun decide(c: Db.Candidate, status: String) {
        Thread {
            db.setClusterStatus(c.id, status, null)
            runOnUiThread { idx++; showCandidate() }
        }.start()
    }

    // ----------------------------------------------------------- 4. summary

    private fun showSummary() {
        root.removeAllViews()
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        col.addView(title("$named ${if (named == 1) "person" else "people"} enrolled"))

        // The part that matters: who did they NOT claim?
        val skipped = db.skippedClusters(minSize = 5)
        if (skipped.isNotEmpty()) {
            col.addView(Ui.text(this, Ui.Type.TITLE_SMALL, "Worth a second look",
                R.color.pk_warning).apply {
                setPadding(dp(Ui.MD), dp(Ui.SM), dp(Ui.MD), dp(Ui.XXS))
            })
            col.addView(body(
                "These faces keep appearing but you didn't claim them. Photos of them can be " +
                "suggested for deletion. If any of these is family, go back and add them."
            ))
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (s in skipped.take(10)) {
                val p = s.thumbs.firstOrNull() ?: continue
                val bmp = try { BitmapFactory.decodeFile(p) } catch (e: Exception) { null } ?: continue
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(0, 0, dp(8), 0) }
                }
                cell.addView(Ui.thumb(this, 80).apply { setImageBitmap(bmp) })
                cell.addView(Ui.text(this, Ui.Type.CAPTION, "${s.size} photos",
                    R.color.pk_on_surface_variant))
                strip.addView(cell)
            }
            col.addView(android.widget.HorizontalScrollView(this).apply { addView(strip) })
            col.addView(button("Go back and add someone", Ui.Btn.PRIMARY) {
                candidates = db.candidates(limit = 20, thumbsEach = 6)
                idx = 0
                if (candidates.isEmpty()) showSummary() else showCandidate()
            })
        } else if (named > 0) {
            col.addView(body("No other recurring faces were left unclaimed."))
        }

        if (named == 0) {
            col.addView(body(
                "You haven't enrolled anyone. Until you do, every group photo — including " +
                "photos of your own family — will be treated as a deletion candidate. " +
                "Strongly consider adding at least the people closest to you."
            ))
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
            "Photos settings \u203a Backup \u203a Back up device folders"))

        val status = Ui.text(this, Ui.Type.BODY,
            "Making the ${Promoter.DEST_LABEL} folder\u2026")
        col.addView(Ui.factRow(this, R.drawable.ic_folder, status))
        // Google Photos only lists a folder that exists, and on a fresh install
        // nothing has been rescued into it yet.
        Thread {
            val ok = Promoter.ensureDestFolder(this)
            runOnUiThread {
                status.text = if (ok) "${Promoter.DEST_LABEL} exists, so it will be in that list"
                              else "PixKin makes that folder with the first photo it saves"
            }
        }.start()

        col.addView(body("Photos already backed up stay where they are."))

        val photos = packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
        if (photos != null) col.addView(button("Open Google Photos", Ui.Btn.PRIMARY) {
            startActivity(photos)
        })
        col.addView(button("Done", if (photos == null) Ui.Btn.PRIMARY else Ui.Btn.TEXT) { done() })
        root.addView(scroll)
    }

    override fun onDestroy() { super.onDestroy(); db.close() }
}
