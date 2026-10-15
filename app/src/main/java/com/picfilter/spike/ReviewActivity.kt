package com.picfilter.spike

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
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
class ReviewActivity : ComponentActivity() {

    private lateinit var db: Db
    private lateinit var image: ImageView
    private lateinit var strip: LinearLayout
    private lateinit var info: TextView
    private lateinit var counter: TextView

    private sealed interface Card
    private data class ItemCard(val row: Db.ReviewRow) : Card
    private data class DupeCard(val groupId: Long, val members: List<Pair<Long, String>>) : Card

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

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#11131A"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        counter = TextView(this).apply {
            setTextColor(Color.parseColor("#9FB0C8")); textSize = 13f
        }
        root.addView(counter)

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(image)

        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110))
            visibility = View.GONE
        }
        root.addView(strip)

        info = TextView(this).apply {
            setTextColor(Color.parseColor("#E6EDF6")); textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(info)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72))
        }
        if (mode == "dupes") {
            buttons.addView(bigButton("DIFFERENT", "#B4453A") { verdict("DIFFERENT") })
            buttons.addView(bigButton("SAME", "#2F7D53") { verdict("SAME") })
        } else {
            buttons.addView(bigButton("DISCARD", "#B4453A") { label("DISCARD") })
            buttons.addView(bigButton("SKIP", "#3A4150") { label("SKIP") })
            buttons.addView(bigButton("KEEP", "#2F7D53") { label("KEEP") })
        }
        root.addView(buttons)

        val undo = Button(this).apply {
            text = "Undo last"
            setOnClickListener { undo() }
        }
        root.addView(undo)

        // the nav bar was covering the undo button
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(12) + bars.left, dp(12) + bars.top,
                         dp(12) + bars.right, dp(12) + bars.bottom)
            insets
        }
        return root
    }

    private fun bigButton(label: String, colour: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(colour))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            .apply { setMargins(dp(4), 0, dp(4), 0) }
        setOnClickListener { onClick() }
    }

    // --------------------------------------------------------------- queue

    private fun loadQueue() {
        queue = if (mode == "dupes") db.dupeGroups().map { DupeCard(it.first, it.second) }
                else db.goldenQueue(perReason = 40).map { ItemCard(it) }
        idx = 0
    }

    private fun render() {
        if (idx >= queue.size) {
            image.setImageDrawable(null); strip.visibility = View.GONE
            val summary = if (mode == "dupes")
                db.dupeVerdictCounts().joinToString("  ") { "${it.first}=${it.second}" }
            else db.labelCounts().joinToString("  ") { "${it.first}=${it.second}" }
            info.text = "Done.\n$summary"
            counter.text = "queue empty"
            return
        }
        counter.text = "${idx + 1} / ${queue.size}   [$mode]"

        val card = queue[idx]
        if (card is DupeCard) {
            val gid = card.groupId
            val members = card.members
            strip.visibility = View.VISIBLE
            strip.removeAllViews()
            image.setImageBitmap(thumb(Uri.parse(members[0].second), 1400))
            for ((_, u) in members) {
                strip.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(100), dp(100))
                        .apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                    setImageBitmap(thumb(Uri.parse(u), 240))
                })
            }
            info.text = "group $gid · ${members.size} members\nAre these the same picture?"
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
        val r = (queue.getOrNull(idx) as? ItemCard)?.row ?: return
        db.putLabel(r.id, v)
        history.addLast(idx to r.id)
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
