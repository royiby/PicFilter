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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.picfilter.spike.data.Db
import com.picfilter.spike.engine.Clusterer

/**
 * End-to-end enrolment. The app finds the recurring faces in the user's own
 * library and asks which of them matter, instead of asking the user to assemble
 * folders of photos by hand.
 *
 * The final screen is the important one: it warns about recurring people the
 * user did NOT claim, because photos of those people will score as discard
 * candidates. That is the ice-cream-photo failure, surfaced before it can bite.
 */
class OnboardingActivity : ComponentActivity() {

    private lateinit var db: Db
    private lateinit var root: LinearLayout
    private var candidates: List<Db.Candidate> = emptyList()
    private var idx = 0
    private var named = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#11131A"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(16) + b.left, dp(16) + b.top, dp(16) + b.right, dp(16) + b.bottom)
            insets
        }
        setContentView(root)
        // "resume" skips the scan and works from clusters already extracted —
        // used for adding people later, and for re-grouping experiments.
        if (intent?.getStringExtra("resume") == "1") {
            candidates = db.candidates(limit = 15, thumbsEach = 6)
            idx = 0
            if (candidates.isEmpty()) showNoFaces() else showCandidate()
        } else {
            showIntro()
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 24f; setTextColor(Color.WHITE)
        setPadding(0, 0, 0, dp(10))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(Color.parseColor("#C7D2E0"))
        setPadding(0, 0, 0, dp(14))
    }

    private fun button(t: String, colour: String, onClick: () -> Unit) = Button(this).apply {
        text = t; textSize = 16f
        setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor(colour))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { setMargins(0, dp(6), 0, dp(6)) }
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------- 1. intro

    private fun showIntro() {
        root.removeAllViews()
        root.addView(title("Who matters to you?"))
        root.addView(body(
            "PicFilter will look through about ${Clusterer.SAMPLE_SIZE} of your recent photos " +
            "and find the faces that keep appearing.\n\n" +
            "You tell it which ones are family and friends. Photos of those people are then " +
            "protected and never suggested for deletion.\n\n" +
            "This takes about four minutes. Everything happens on this phone — no photos are " +
            "uploaded anywhere."
        ))
        root.addView(button("Start", "#2F6FD0") { runScan() })
        if (db.candidates(limit = 1, thumbsEach = 1).isNotEmpty()) {
            root.addView(button("Continue with faces already found", "#3A6EA5") {
                candidates = db.candidates(limit = 15, thumbsEach = 6)
                idx = 0
                if (candidates.isEmpty()) showNoFaces() else showCandidate()
            })
        }
        root.addView(button("Cancel", "#3A4150") { finish() })
    }

    // -------------------------------------------------------------- 2. scan

    private fun runScan() {
        root.removeAllViews()
        root.addView(title("Finding faces"))
        val status = body("Starting…")
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false; max = 100
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14))
        }
        root.addView(bar)
        root.addView(status)
        root.addView(body("Keep the screen on until this finishes."))

        Thread {
            Clusterer.bootstrap(this, Clusterer.SAMPLE_SIZE) { done, total, phase ->
                val pct = if (total > 0) done * 100 / total else 0
                runOnUiThread {
                    bar.progress = pct
                    status.text = "$phase…  $done / $total"
                }
            }
            runOnUiThread {
                candidates = db.candidates(limit = 15, thumbsEach = 6)
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
            "That usually means the sample was mostly forwarded images rather than your own " +
            "camera photos. You can try again, or enrol people manually later."
        ))
        root.addView(button("Done", "#3A4150") { finish() })
    }

    // --------------------------------------------------------- 3. candidates

    private fun showCandidate() {
        if (idx >= candidates.size) { showSummary(); return }
        val c = candidates[idx]
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = "${idx + 1} of ${candidates.size}   ·   $named named"
            textSize = 13f; setTextColor(Color.parseColor("#9FB0C8"))
        })
        root.addView(title("Do you know this person?"))

        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in c.thumbs) {
            val bmp = try { BitmapFactory.decodeFile(p) } catch (e: Exception) { null } ?: continue
            strip.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(92), dp(92))
                    .apply { setMargins(0, 0, dp(6), 0) }
            })
        }
        val scroll = android.widget.HorizontalScrollView(this).apply { addView(strip) }
        root.addView(scroll)

        root.addView(body("Appears in ${c.size} photos in the sample."))
        root.addView(button("This is someone I care about", "#2F7D53") { promptName(c) })
        root.addView(button("Not important", "#3A4150") { decide(c, "SKIPPED") })
        root.addView(button("These aren't the same person", "#8A5A2B") { decide(c, "MIXED") })
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
            col.addView(TextView(this).apply {
                text = "Worth a second look"
                textSize = 18f; setTextColor(Color.parseColor("#E8B84B"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10), 0, dp(6))
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
                cell.addView(ImageView(this).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(80), dp(80))
                })
                cell.addView(TextView(this).apply {
                    text = "${s.size} photos"; textSize = 11f
                    setTextColor(Color.parseColor("#9FB0C8"))
                })
                strip.addView(cell)
            }
            col.addView(android.widget.HorizontalScrollView(this).apply { addView(strip) })
            col.addView(button("Go back and add someone", "#2F6FD0") {
                candidates = db.candidates(limit = 15, thumbsEach = 6)
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

        col.addView(button("Done", "#3A4150") { finish() })
        root.addView(scroll)
    }

    override fun onDestroy() { super.onDestroy(); db.close() }
}
