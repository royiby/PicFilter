package com.picfilter.spike

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import com.google.android.material.button.MaterialButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.picfilter.spike.data.Db

/**
 * Phase 2 review UI. Two modes:
 *   golden — label individual items KEEP/DISCARD to build the golden set
 *   dupes  — verify proposed near-duplicate groups (SAME / DIFFERENT)
 *
 * This is the surface a human actually uses, so it is launched from the app
 * icon, not adb. Nothing here deletes or moves anything: it only records
 * judgements, which is what the thresholds are then fitted against.
 */
private const val TAG = "PICFILTER"

class ReviewActivity : ComponentActivity() {

    private lateinit var db: Db
    private lateinit var image: ImageView
    private lateinit var empty: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var info: TextView
    private lateinit var counter: TextView
    private lateinit var actionBar: LinearLayout
    private lateinit var undoBtn: View

    private sealed interface Card
    private data class ItemCard(val row: Db.ReviewRow) : Card
    private data class DupeCard(val groupId: Long, val members: List<Pair<Long, String>>) : Card
    private data class RescueCard(val row: Db.RescueRow) : Card

    private var mode = "golden"
    private var queue: List<Card> = emptyList()
    private var idx = 0
    private val history = ArrayDeque<Pair<Int, Long>>()   // (index, id) for undo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        mode = intent?.getStringExtra("mode") ?: "golden"
        setContentView(buildUi())
        loadQueue()
        render()
    }

    // ------------------------------------------------------------------ ui

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** What this queue is, for the top bar. Inner screens get a text title. */
    private fun screenTitle() = when (mode) {
        "dupes" -> "Suspected duplicates"
        "junk" -> "Suspected junk"
        "verify" -> "Saved photos"
        "rescue" -> "More family photos"
        "noface" -> "Photos with no faces"
        else -> "Review"
    }

    private fun buildUi(): View {
        val root = Ui.screen(this)
        root.addView(Ui.titleBar(this, screenTitle()) { finish() })

        counter = Ui.text(this, Ui.Type.CAPTION, "", R.color.pk_on_surface_variant).apply {
            setPadding(dp(Ui.MD), dp(Ui.XS), dp(Ui.MD), dp(Ui.XXS))
        }
        root.addView(counter)

        image = Ui.photo(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { setMargins(dp(Ui.MD), 0, dp(Ui.MD), 0) }
        }
        root.addView(image)

        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { setMargins(dp(Ui.MD), 0, dp(Ui.MD), 0) }
            visibility = View.GONE
        }
        root.addView(empty)

        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(Ui.MD), dp(Ui.SM), dp(Ui.MD), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(104))
            visibility = View.GONE
        }
        root.addView(strip)

        info = Ui.text(this, Ui.Type.BODY_SMALL).apply {
            setPadding(dp(Ui.MD), dp(Ui.SM), dp(Ui.MD), dp(Ui.SM))
        }
        root.addView(info)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(Ui.SM), 0, dp(Ui.SM), dp(Ui.XXS))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(Ui.TOUCH))
        }
        when (mode) {
            "dupes" -> {
                buttons.addView(Ui.filled(this, "Different", Ui.Btn.OUTLINED) { verdict("DIFFERENT") })
                buttons.addView(Ui.filled(this, "Same photo", Ui.Btn.PRIMARY) { verdict("SAME") })
            }
            "verify" -> {
                buttons.addView(Ui.filled(this, "Not mine", Ui.Btn.OUTLINED) { label("DISCARD") })
                buttons.addView(Ui.filled(this, "Skip", Ui.Btn.TEXT) { label("SKIP") })
                buttons.addView(Ui.filled(this, "Correct", Ui.Btn.PRIMARY) { label("KEEP") })
            }
            "junk" -> {
                buttons.addView(Ui.filled(this, "It's junk", Ui.Btn.OUTLINED) { label("DISCARD") })
                buttons.addView(Ui.filled(this, "Skip", Ui.Btn.TEXT) { label("SKIP") })
                buttons.addView(Ui.filled(this, "Keep", Ui.Btn.PRIMARY) { label("KEEP") })
            }
            "rescue", "noface" -> {
                buttons.addView(Ui.filled(this, "Not mine", Ui.Btn.OUTLINED) { label("DISCARD") })
                buttons.addView(Ui.filled(this, "Skip", Ui.Btn.TEXT) { label("SKIP") })
                buttons.addView(Ui.filled(this, "Keep", Ui.Btn.PRIMARY) { label("KEEP") })
            }
            else -> {
                buttons.addView(Ui.filled(this, "Discard", Ui.Btn.OUTLINED) { label("DISCARD") })
                buttons.addView(Ui.filled(this, "Skip", Ui.Btn.TEXT) { label("SKIP") })
                buttons.addView(Ui.filled(this, "Keep", Ui.Btn.PRIMARY) { label("KEEP") })
            }
        }
        actionBar = buttons
        root.addView(buttons)

        undoBtn = Ui.wideButton(this, "Undo last", Ui.Btn.LINK) { undo() }
        root.addView(undoBtn)

        Ui.applyInsets(root, this, extraBottom = 4)
        return root
    }

    // --------------------------------------------------------------- queue

    private fun loadQueue() {
        queue = when (mode) {
            "dupes" -> db.dupeGroups().map { DupeCard(it.first, it.second) }
            "rescue" -> db.rescueQueue().map { RescueCard(it) }
            "noface" -> db.rescueQueue(listOf("NO_FACE_PLAIN")).map { RescueCard(it) }
            "verify" -> db.promotedForReview().map { RescueCard(it) }
            "junk" -> db.junkQueue().map { RescueCard(it) }
            else -> db.goldenQueue(perReason = 40).map { ItemCard(it) }
        }
        idx = 0
    }

    private fun render() {
        if (idx >= queue.size) {
            // Empty state: the mark, one line, nothing to decide.
            image.setImageDrawable(null); strip.visibility = View.GONE
            actionBar.visibility = View.GONE
            undoBtn.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
            if (empty.childCount == 0) empty.addView(Ui.emptyState(this,
                if (history.isEmpty()) "Nothing here to look at." else "All done here."))
            empty.visibility = View.VISIBLE
            image.visibility = View.GONE
            val summary = if (mode == "dupes")
                db.dupeVerdictCounts().joinToString("  ") { "${it.first}=${it.second}" }
            else db.labelCounts().joinToString("  ") { "${it.first}=${it.second}" }
            info.text = summary
            counter.text = ""
            return
        }
        empty.visibility = View.GONE
        image.visibility = View.VISIBLE
        actionBar.visibility = View.VISIBLE
        undoBtn.visibility = View.VISIBLE
        counter.text = "${idx + 1} of ${queue.size}"

        val card = queue[idx]
        if (card is DupeCard) {
            val gid = card.groupId
            val members = card.members
            strip.visibility = View.VISIBLE
            strip.removeAllViews()
            image.setImageBitmap(thumb(Uri.parse(members[0].second), 1400))
            for ((_, u) in members) {
                strip.addView(Ui.thumb(this, 80).apply { setImageBitmap(quickThumb(u)) })
            }
            info.text = "group $gid · ${members.size} members\nAre these the same picture?"
        } else if (card is RescueCard) {
            val r = card.row
            strip.visibility = View.GONE
            image.setImageBitmap(thumb(Uri.parse(r.uri), 1400))
            if (mode == "verify") {
                info.text = "PixKin saved this as family" +
                    (r.nearName?.let { n -> ", matching $n" } ?: "") +
                    (r.nearSim?.let { v -> " at ${"%.3f".format(v)}" } ?: "") +
                    ".\nIs that right?"
                return
            }
            if (mode == "junk") {
                showUpcoming()
                info.text = "No one you enrolled is in this photo" +
                    (r.nearName?.let { n -> ", closest was $n" } ?: "") +
                    (r.nearSim?.let { v -> " at ${"%.3f".format(v)}" } ?: "") +
                    ".\nfaces=${r.faces}  unidentifiable=${r.indet}  \u00b7  ${r.reason}"
                return
            }
            if (mode == "noface") {
                info.text = "No recognisable face in this photo.\n" +
                    "Keep it if it still matters — a place, a drawing, a document."
                return
            }
            val near = when {
                r.nearName != null && r.nearSim != null ->
                    "closest match:  ${r.nearName}  ${"%.3f".format(r.nearSim)}" +
                        (if (r.nearSim >= 0.50f) "   ← near miss" else "")
                r.nearSim != null -> "closest similarity ${"%.3f".format(r.nearSim)}"
                else -> "no measurable face (too small or turned away)"
            }
            info.text = "$near\nfaces=${r.faces}  unidentifiable=${r.indet}  ·  ${r.reason}"
        } else if (card is ItemCard) {
            val r = card.row
            strip.visibility = View.GONE
            image.setImageBitmap(thumb(Uri.parse(r.uri), 1400))
            info.text = "${r.decision}  ·  ${r.reason}\n" +
                "faces=${r.faces}  matched=${r.matched}  indeterminate=${r.indet}  " +
                "text=${"%.3f".format(r.textFrac)}" +
                (r.dupeGroup?.let { "  dupe_group=$it" } ?: "")
        }
    }

    /** MediaStore's own thumbnail — fast enough to fill a strip on the fly. */
    private fun quickThumb(uri: String) = try {
        contentResolver.loadThumbnail(Uri.parse(uri), android.util.Size(256, 256), null)
    } catch (e: Exception) { null }

    /** The next few items in the queue, so the screen shows more than one
     *  suspect at a time. Tapping one jumps straight to it. */
    private fun showUpcoming() {
        strip.removeAllViews()
        val next = queue.drop(idx + 1).take(8)
        if (next.isEmpty()) { strip.visibility = View.GONE; return }
        strip.visibility = View.VISIBLE
        for ((offset, card) in next.withIndex()) {
            val uri = (card as? RescueCard)?.row?.uri ?: continue
            val at = idx + 1 + offset
            strip.addView(Ui.thumb(this, 80).apply {
                setImageBitmap(quickThumb(uri))
                setOnClickListener { idx = at; render() }
            })
        }
    }

    private fun thumb(u: Uri, target: Int) = try {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, b) }
        var s = 1
        val maxDim = maxOf(b.outWidth, b.outHeight)
        while (maxDim / (s * 2) >= target) s *= 2
        contentResolver.openInputStream(u)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (e: Exception) { null }

    // ------------------------------------------------------------ actions

    private fun label(v: String) {
        // Exhaustive on purpose: an unhandled card type must shout, not silently
        // return. A missing branch here made every rescue button a no-op.
        val id = when (val c = queue.getOrNull(idx)) {
            is ItemCard -> c.row.id
            is RescueCard -> c.row.id
            is DupeCard -> { Log.w(TAG, "label() on a DupeCard"); return }
            null -> return
        }
        db.putLabel(id, v)
        if (mode == "verify" && v == "DISCARD") {
            Thread { com.picfilter.spike.engine.Promoter.unpromote(this, id) }.start()
        }
        history.addLast(idx to id)
        idx++; render()
    }

    private fun verdict(v: String) {
        val g = queue.getOrNull(idx) as? DupeCard ?: return
        db.putDupeVerdict(g.groupId, v)
        history.addLast(idx to g.groupId)
        idx++; render()
    }

    private fun undo() {
        val last = history.removeLastOrNull() ?: run {
            Toast.makeText(this, "nothing to undo", Toast.LENGTH_SHORT).show(); return
        }
        if (mode == "dupes") {
            db.writableDatabase.execSQL("DELETE FROM dupe_verdict WHERE group_id=?", arrayOf<Any>(last.second))
        } else {
            db.deleteLabel(last.second)
        }
        idx = last.first
        render()
    }

    override fun onDestroy() { super.onDestroy(); db.close() }
}
