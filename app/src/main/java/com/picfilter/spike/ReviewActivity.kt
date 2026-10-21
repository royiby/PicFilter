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
import androidx.lifecycle.lifecycleScope
import com.picfilter.spike.data.Db
import com.picfilter.spike.data.DbQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 2 review UI. Six queues, chosen by the "mode" extra:
 *   golden — unlabelled items, stratified 40 per reason, to build the golden set
 *   verify — everything promoted, WEAKEST MATCH FIRST, so false keeps surface
 *   rescue — REVIEW items with faces, ordered by near-miss similarity
 *   noface — NO_FACE_PLAIN items: places, drawings, documents
 *   junk   — unjudged DISCARD_CANDIDATEs
 *   dupes  — proposed near-duplicate groups (SAME / DIFFERENT)
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
    private lateinit var bar: android.widget.FrameLayout
    private lateinit var gridScroll: android.widget.ScrollView
    private val gridRows by lazy {
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    }
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
    /** Grid first for the screens you come to look at; card first for the ones
     *  you come to decide. Either way the toggle in the bar switches. */
    private var grid = false
    private var gridGeneration = 0
    private var queue: List<Card> = emptyList()
    private var idx = 0
    private val history = ArrayDeque<Pair<Int, Long>>()   // (index, id) for undo

    /** Every database touch from this screen, serialised; see [DbQueue]. */
    private val dbq by lazy { DbQueue(db) }

    /** The card currently being decoded, so a fast swipe cancels the last one. */
    private var decode: Job? = null

    private companion object {
        const val S_MODE = "mode"
        const val S_GRID = "grid"
        const val S_IDX = "idx"
        const val S_HIST_AT = "hist_at"
        const val S_HIST_ID = "hist_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        mode = savedInstanceState?.getString(S_MODE)
            ?: intent?.getStringExtra("mode") ?: "golden"
        grid = savedInstanceState?.getBoolean(S_GRID) ?: (mode == "verify")
        idx = savedInstanceState?.getInt(S_IDX) ?: 0
        val at = savedInstanceState?.getIntArray(S_HIST_AT)
        val ids = savedInstanceState?.getLongArray(S_HIST_ID)
        if (at != null && ids != null && at.size == ids.size) {
            for (i in at.indices) history.addLast(at[i] to ids[i])
        }
        setContentView(buildUi())
        loadQueue()
    }

    /**
     * Survive a rotation, and process death.
     *
     * Without this the Activity was rebuilt from scratch: queue reloaded, index
     * back to zero, undo history gone. Turning the phone sideways halfway
     * through a review of 111 photos started it again from the first one, and
     * the only reason it was never reported is that the person reviewing was
     * the person who wrote it.
     *
     * The queue itself is not saved -- it is derivable from the database, and
     * reloading is the right thing anyway, since a verdict recorded before the
     * rotation has removed that item from it.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(S_MODE, mode)
        outState.putBoolean(S_GRID, grid)
        outState.putInt(S_IDX, idx)
        outState.putIntArray(S_HIST_AT, history.map { it.first }.toIntArray())
        outState.putLongArray(S_HIST_ID, history.map { it.second }.toLongArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        decode?.cancel()
        dbq.closeAfterPending()
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
        bar = android.widget.FrameLayout(this)
        root.addView(bar)
        buildBar()

        counter = Ui.text(this, Ui.Type.CAPTION, "", R.color.pk_on_surface_variant).apply {
            setPadding(dp(Ui.MD), dp(Ui.XS), dp(Ui.MD), dp(Ui.XXS))
        }
        root.addView(counter)

        image = Ui.photo(this).apply {
            // The one photo on screen IS the content of this screen, and unlike
            // a grid tile there is something true to say about it. renderCard
            // sets the description per card; see [describe].
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isScreenReaderFocusable = true
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

        gridScroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            visibility = View.GONE
            addView(gridRows)
        }
        root.addView(gridScroll)

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
            // WRAP_CONTENT, not TOUCH. The buttons are themselves TOUCH tall
            // (Ui.filled), so a row fixed at TOUCH with XXS of bottom padding
            // left them 4dp short and sliced the bottom off every one of them --
            // the rounded edge of the pill simply ended in a straight line.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
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

    /**
     * Read the queue off the main thread, then draw it.
     *
     * Every one of these is a multi-table query with sub-selects per row --
     * rescueQueue sorts by a correlated MAX(similarity) -- and it ran inside
     * onCreate on the thread that draws frames.
     */
    private fun loadQueue(keepIndex: Boolean = true) {
        lifecycleScope.launch {
            val loaded = withContext(dbq.context) {
                when (mode) {
                    "dupes" -> db.dupeGroups().map { DupeCard(it.first, it.second) }
                    "rescue" -> db.rescueQueue().map { RescueCard(it) }
                    "noface" -> db.rescueQueue(listOf("NO_FACE_PLAIN")).map { RescueCard(it) }
                    "verify" -> db.promotedForReview().map { RescueCard(it) }
                    "junk" -> db.junkQueue().map { RescueCard(it) }
                    else -> db.goldenQueue(perReason = 40).map { ItemCard(it) }
                }
            }
            queue = loaded
            // A restored index can be past the end: verdicts recorded before
            // the rotation have taken their items out of the queue.
            idx = if (keepIndex) idx.coerceIn(0, maxOf(loaded.size, 0)) else 0
            gridRows.removeAllViews()
            render()
        }
    }

    private fun buildBar() {
        bar.removeAllViews()
        bar.addView(Ui.titleBar(
            this, screenTitle(),
            actionIcon = if (grid) R.drawable.ic_single_photo else R.drawable.ic_grid,
            actionLabel = if (grid) "Show one photo at a time" else "Show all as a grid",
            onAction = { grid = !grid; buildBar(); render() },
        ) { finish() })
    }

    /** The uri a card shows in the grid — for a dupe group, its first member. */
    private fun uriOf(card: Card): String? = when (card) {
        is ItemCard -> card.row.uri
        is RescueCard -> card.row.uri
        is DupeCard -> card.members.firstOrNull()?.second
    }

    /**
     * Three columns, 2dp gaps, square tiles, nothing rounded inside the grid.
     * Thumbnails arrive off the main thread; [gridGeneration] stops a stale
     * pass from drawing into a grid that has already been rebuilt.
     */
    private fun buildGrid() {
        gridRows.removeAllViews()
        val gen = ++gridGeneration
        val cols = 3
        val gap = dp(Ui.GRID_GAP)
        val side = (resources.displayMetrics.widthPixels - gap * (cols - 1)) / cols
        val kept = mode == "verify"          // everything here is already saved
        val dimmed = mode == "junk"          // and everything here is not
        val tiles = ArrayList<ImageView>(queue.size)

        var row: LinearLayout? = null
        for ((i, card) in queue.withIndex()) {
            if (i % cols == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = gap }
                }
                gridRows.addView(row)
            }
            val tile = Ui.gridTile(
                this, side, kept, dimmed,
                label = "${describe(card)} Item ${i + 1} of ${queue.size}. Tap to open.",
            ).apply {
                if (i % cols != 0) (layoutParams as LinearLayout.LayoutParams).marginStart = gap
                setOnClickListener { idx = i; grid = false; buildBar(); render() }
            }
            row!!.addView(tile)
            tiles += tile.getChildAt(0) as ImageView
        }

        lifecycleScope.launch {
            for ((i, card) in queue.withIndex()) {
                if (gen != gridGeneration) return@launch
                val u = uriOf(card) ?: continue
                val bmp = withContext(Dispatchers.IO) { quickThumb(u) } ?: continue
                if (gen == gridGeneration) tiles[i].setImageBitmap(bmp)
            }
        }
    }

    private fun render() {
        if (grid && queue.isNotEmpty()) {
            if (gridRows.childCount == 0) buildGrid()
            gridScroll.visibility = View.VISIBLE
            image.visibility = View.GONE
            empty.visibility = View.GONE
            strip.visibility = View.GONE
            actionBar.visibility = View.GONE
            undoBtn.visibility = View.GONE
            counter.text = "${queue.size} photos"
            info.text = when (mode) {
                "verify" -> "Tap one to check it."
                "junk" -> "Tap one to decide."
                else -> "Tap one to look at it."
            }
            return
        }
        gridScroll.visibility = View.GONE
        renderCard()
    }

    private fun renderCard() {
        if (idx >= queue.size) {
            // Empty state: the mark, one line, nothing to decide.
            image.setImageDrawable(null); strip.visibility = View.GONE
            actionBar.visibility = View.GONE
            undoBtn.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
            if (empty.childCount == 0) empty.addView(Ui.emptyState(this,
                if (history.isEmpty()) "Nothing here to look at." else "All done here."))
            empty.visibility = View.VISIBLE
            image.visibility = View.GONE
            counter.text = ""
            info.text = ""
            lifecycleScope.launch {
                info.text = withContext(dbq.context) {
                    if (mode == "dupes")
                        db.dupeVerdictCounts().joinToString("  ") { "${it.first}=${it.second}" }
                    else db.labelCounts().joinToString("  ") { "${it.first}=${it.second}" }
                }
            }
            return
        }
        empty.visibility = View.GONE
        image.visibility = View.VISIBLE
        actionBar.visibility = View.VISIBLE
        undoBtn.visibility = View.VISIBLE
        counter.text = "${idx + 1} of ${queue.size}"

        val card = queue[idx]
        image.contentDescription = "${describe(card)} ${idx + 1} of ${queue.size}."
        if (card is DupeCard) {
            val gid = card.groupId
            val members = card.members
            showImage(members[0].second)
            fillStrip(members.map { it.second })
            info.text = "group $gid · ${members.size} members\nAre these the same picture?"
        } else if (card is RescueCard) {
            val r = card.row
            strip.visibility = View.GONE
            showImage(r.uri)
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
            showImage(r.uri)
            info.text = "${r.decision}  ·  ${r.reason}\n" +
                "faces=${r.faces}  matched=${r.matched}  indeterminate=${r.indet}  " +
                "text=${"%.3f".format(r.textFrac)}" +
                (r.dupeGroup?.let { "  dupe_group=$it" } ?: "")
        }
    }

    /**
     * Put a photo on screen without decoding it on the thread that draws.
     *
     * This was `image.setImageBitmap(thumb(uri, 1400))` inline in renderCard:
     * two full passes over the file (bounds, then pixels) for a 1400px bitmap,
     * per card, on the UI thread. It is the single heaviest main-thread call in
     * the app and the reason moving through a queue felt sticky.
     *
     * One decode at a time: moving on cancels the previous card's, so holding
     * the Keep button down does not queue up a dozen full-size decodes.
     */
    private fun showImage(uriStr: String, target: Int = 1400) {
        decode?.cancel()
        image.setImageDrawable(null)
        val u = Uri.parse(uriStr)
        decode = lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { thumb(u, target) }
            if (bmp != null) image.setImageBitmap(bmp)
        }
    }

    /** A row of small thumbnails, each decoded off the main thread. */
    private fun fillStrip(uris: List<String>, onTap: ((Int) -> Unit)? = null) {
        strip.removeAllViews()
        if (uris.isEmpty()) { strip.visibility = View.GONE; return }
        strip.visibility = View.VISIBLE
        val tiles = uris.mapIndexed { i, _ ->
            Ui.thumb(this, 80).also { t ->
                if (onTap != null) t.setOnClickListener { onTap(i) }
                strip.addView(t)
            }
        }
        lifecycleScope.launch {
            for ((i, u) in uris.withIndex()) {
                val bmp = withContext(Dispatchers.IO) { quickThumb(u) } ?: continue
                tiles[i].setImageBitmap(bmp)
            }
        }
    }

    /**
     * What a screen reader should say about the photo on screen.
     *
     * The picture itself cannot be described -- PixKin does not know what is in
     * someone's photograph -- but everything around the decision can be, and
     * that is what the person is being asked about. Without this the review
     * flow was a row of buttons with no subject.
     */
    private fun describe(card: Card): String = when (card) {
        is DupeCard -> "A group of ${card.members.size} photos that may be the same picture."
        is RescueCard -> {
            val r = card.row
            val who = r.nearName?.let { n ->
                r.nearSim?.let { v -> " Closest match $n, ${"%.2f".format(v)}." } ?: " Closest match $n."
            } ?: ""
            when (mode) {
                "verify" -> "A photo PixKin saved as family.$who"
                "junk" -> "A photo PixKin thinks is not yours.$who"
                "noface" -> "A photo with no recognisable face in it."
                else -> "A photo PixKin was unsure about.$who ${r.faces} faces found."
            }
        }
        is ItemCard -> "A photo scored ${card.row.decision}, ${card.row.reason}, " +
            "with ${card.row.faces} faces found."
    }

    /** MediaStore's own thumbnail — fast enough to fill a strip on the fly. */
    private fun quickThumb(uri: String) = try {
        contentResolver.loadThumbnail(Uri.parse(uri), android.util.Size(256, 256), null)
    } catch (e: Exception) { null }

    /** The next few items in the queue, so the screen shows more than one
     *  suspect at a time. Tapping one jumps straight to it. */
    private fun showUpcoming() {
        val next = queue.drop(idx + 1).take(8)
        val uris = next.mapNotNull { (it as? RescueCard)?.row?.uri }
        val base = idx + 1
        fillStrip(uris) { offset -> idx = base + offset; render() }
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
        // The screen advances now and the row is written behind it, in tap
        // order on [DbQueue]. A verdict that took 30ms of disk to record used
        // hold the frame that showed the next photo.
        //
        // Un-promoting runs on the same queue rather than its own thread: it
        // must not overtake the DISCARD label that explains it, and it must
        // still finish if the screen closes straight afterwards -- which is why
        // it is NOT on lifecycleScope.
        // NOT lifecycleScope: it cancels on destroy, and a rotation right after
        // a tap would throw the verdict away.
        dbq.submit { db.putLabel(id, v) }
        if (mode == "verify" && v == "DISCARD") {
            // Outlives this screen on purpose; see [Background].
            val app = applicationContext
            Background.io.execute {
                try { com.picfilter.spike.engine.Promoter.unpromote(app, id) }
                catch (e: Exception) {
                    Log.w(TAG, "UNPROMOTE ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        gridRows.removeAllViews()        // the queue changed under the grid
        history.addLast(idx to id)
        idx++; render()
    }


    private fun verdict(v: String) {
        val g = queue.getOrNull(idx) as? DupeCard ?: return
        dbq.submit { db.putDupeVerdict(g.groupId, v) }
        history.addLast(idx to g.groupId)
        idx++; render()
    }

    private fun undo() {
        val last = history.removeLastOrNull() ?: run {
            Toast.makeText(this, "nothing to undo", Toast.LENGTH_SHORT).show(); return
        }
        val dupes = mode == "dupes"
        // Ordered after the write it undoes: [DbQueue] is one thread.
        dbq.submit {
            if (dupes) db.writableDatabase.execSQL(
                "DELETE FROM dupe_verdict WHERE group_id=?", arrayOf<Any>(last.second))
            else db.deleteLabel(last.second)
        }
        idx = last.first
        render()
    }

}
