package com.picfilter.spike

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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.picfilter.spike.Ui.setMarginsRelative
import com.picfilter.spike.data.Db
import com.picfilter.spike.data.DbQueue
import kotlinx.coroutines.Dispatchers
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
/** Decode target for the single large card. */
private const val CARD_PX = 1400

class ReviewActivity : ComponentActivity() {

    private lateinit var db: Db
    private lateinit var image: ImageView
    private lateinit var empty: LinearLayout
    private lateinit var bar: android.widget.FrameLayout
    private lateinit var gridView: RecyclerView
    private val gridAdapter by lazy {
        PhotoGridAdapter(
            this, gridTileSide(this, dp(Ui.GRID_GAP)),
            kept = mode == "verify",   // everything here is already saved
            dimmed = mode == "junk",   // and everything here is not
        ) { at -> idx = at; grid = false; buildBar(); render() }
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
    private var queue: List<Card> = emptyList()
    private var idx = 0
    private val history = ArrayDeque<Pair<Int, Long>>()   // (index, id) for undo

    /** Every database touch from this screen, serialised; see [DbQueue]. */
    private val dbq by lazy { DbQueue(db) }

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
        dbq.closeAfterPending()
    }

    // ------------------------------------------------------------------ ui

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** What this queue is, for the top bar. Inner screens get a text title. */
    private fun screenTitle() = getString(when (mode) {
        "dupes" -> R.string.queue_dupes
        "junk" -> R.string.queue_junk
        "verify" -> R.string.queue_verify
        "rescue" -> R.string.queue_rescue
        "noface" -> R.string.queue_noface
        else -> R.string.queue_golden
    })

    private fun buildUi(): View {
        val root = Ui.screen(this)
        bar = android.widget.FrameLayout(this)
        root.addView(bar)
        buildBar()

        counter = Ui.text(this, Ui.Type.CAPTION, "", R.color.pk_on_surface_variant).apply {
            setPaddingRelative(dp(Ui.MD), dp(Ui.XS), dp(Ui.MD), dp(Ui.XXS))
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
            ).apply { setMarginsRelative(dp(Ui.MD), 0, dp(Ui.MD), 0) }
        }
        root.addView(image)

        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { setMarginsRelative(dp(Ui.MD), 0, dp(Ui.MD), 0) }
            visibility = View.GONE
        }
        root.addView(empty)

        gridView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            visibility = View.GONE
            layoutManager = GridLayoutManager(this@ReviewActivity, GRID_COLS)
            addItemDecoration(GridGaps(dp(Ui.GRID_GAP)))
            setHasFixedSize(true)
            adapter = gridAdapter
        }
        root.addView(gridView)

        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingRelative(dp(Ui.MD), dp(Ui.SM), dp(Ui.MD), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(104))
            visibility = View.GONE
        }
        root.addView(strip)

        info = Ui.text(this, Ui.Type.BODY_SMALL).apply {
            setPaddingRelative(dp(Ui.MD), dp(Ui.SM), dp(Ui.MD), dp(Ui.SM))
        }
        root.addView(info)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingRelative(dp(Ui.SM), 0, dp(Ui.SM), dp(Ui.XXS))
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
                buttons.addView(Ui.filled(this, getString(R.string.action_different), Ui.Btn.OUTLINED) { verdict("DIFFERENT") })
                buttons.addView(Ui.filled(this, getString(R.string.action_same_photo), Ui.Btn.PRIMARY) { verdict("SAME") })
            }
            "verify" -> {
                buttons.addView(Ui.filled(this, getString(R.string.action_not_mine), Ui.Btn.OUTLINED) { label("DISCARD") })
                buttons.addView(Ui.filled(this, getString(R.string.action_skip), Ui.Btn.TEXT) { label("SKIP") })
                buttons.addView(Ui.filled(this, getString(R.string.action_correct), Ui.Btn.PRIMARY) { label("KEEP") })
            }
            "junk" -> {
                buttons.addView(Ui.filled(this, getString(R.string.action_its_junk), Ui.Btn.OUTLINED) { label("DISCARD") })
                buttons.addView(Ui.filled(this, getString(R.string.action_skip), Ui.Btn.TEXT) { label("SKIP") })
                buttons.addView(Ui.filled(this, getString(R.string.action_keep), Ui.Btn.PRIMARY) { label("KEEP") })
            }
            "rescue", "noface" -> {
                buttons.addView(Ui.filled(this, getString(R.string.action_not_mine), Ui.Btn.OUTLINED) { label("DISCARD") })
                buttons.addView(Ui.filled(this, getString(R.string.action_skip), Ui.Btn.TEXT) { label("SKIP") })
                buttons.addView(Ui.filled(this, getString(R.string.action_keep), Ui.Btn.PRIMARY) { label("KEEP") })
            }
            else -> {
                buttons.addView(Ui.filled(this, getString(R.string.action_discard), Ui.Btn.OUTLINED) { label("DISCARD") })
                buttons.addView(Ui.filled(this, getString(R.string.action_skip), Ui.Btn.TEXT) { label("SKIP") })
                buttons.addView(Ui.filled(this, getString(R.string.action_keep), Ui.Btn.PRIMARY) { label("KEEP") })
            }
        }
        actionBar = buttons
        root.addView(buttons)

        undoBtn = Ui.wideButton(this, getString(R.string.action_undo_last), Ui.Btn.LINK) { undo() }
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
            gridAdapter.submitList(gridItems())
            render()
        }
    }

    private fun buildBar() {
        bar.removeAllViews()
        bar.addView(Ui.titleBar(
            this, screenTitle(),
            actionIcon = if (grid) R.drawable.ic_single_photo else R.drawable.ic_grid,
            actionLabel = getString(
                if (grid) R.string.grid_show_single else R.string.grid_show_grid),
            onAction = { grid = !grid; buildBar(); render() },
        ) { finish() })
    }

    /** The uri a card shows in the grid — for a dupe group, its first member. */
    private fun uriOf(card: Card): String? = when (card) {
        is ItemCard -> card.row.uri
        is RescueCard -> card.row.uri
        is DupeCard -> card.members.firstOrNull()?.second
    }

    /** What the grid shows, derived from the queue. */
    private fun gridItems(): List<PhotoItem> = queue.mapIndexed { i, card ->
        PhotoItem(
            id = when (card) {
                is ItemCard -> card.row.id
                is RescueCard -> card.row.id
                is DupeCard -> card.groupId
            },
            uri = uriOf(card),
            label = getString(R.string.a11y_grid_item, describe(card), i + 1, queue.size),
        )
    }


    private fun render() {
        if (grid && queue.isNotEmpty()) {
            gridView.visibility = View.VISIBLE
            image.visibility = View.GONE
            empty.visibility = View.GONE
            strip.visibility = View.GONE
            actionBar.visibility = View.GONE
            undoBtn.visibility = View.GONE
            counter.text = resources.getQuantityString(
                R.plurals.grid_photo_count, queue.size, queue.size)
            info.text = getString(when (mode) {
                "verify" -> R.string.grid_tap_to_check
                "junk" -> R.string.grid_tap_to_decide
                else -> R.string.grid_tap_to_look
            })
            return
        }
        gridView.visibility = View.GONE
        renderCard()
    }

    private fun renderCard() {
        if (idx >= queue.size) {
            // Empty state: the mark, one line, nothing to decide.
            image.setImageDrawable(null); strip.visibility = View.GONE
            actionBar.visibility = View.GONE
            undoBtn.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
            if (empty.childCount == 0) empty.addView(Ui.emptyState(this, getString(
                if (history.isEmpty()) R.string.empty_nothing_here
                else R.string.empty_all_done)))
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
        counter.text = getString(R.string.counter_position, idx + 1, queue.size)

        val card = queue[idx]
        image.contentDescription =
            getString(R.string.a11y_card_position, describe(card), idx + 1, queue.size)
        if (card is DupeCard) {
            val gid = card.groupId
            val members = card.members
            showImage(members[0].second)
            fillStrip(members.map { it.second })
            info.text = getString(R.string.dupe_prompt, gid, members.size)
        } else if (card is RescueCard) {
            val r = card.row
            strip.visibility = View.GONE
            showImage(r.uri)
            if (mode == "verify") {
                info.text = getString(R.string.verify_prompt, matchSuffix(r, verify = true))
                return
            }
            if (mode == "junk") {
                showUpcoming()
                info.text = getString(
                    R.string.detail_faces,
                    getString(R.string.junk_prompt, matchSuffix(r, verify = false)),
                    r.faces, r.indet, r.reason)
                return
            }
            if (mode == "noface") {
                info.text = getString(R.string.noface_prompt)
                return
            }
            val near = when {
                r.nearName != null && r.nearSim != null ->
                    getString(R.string.rescue_closest, r.nearName, sim(r.nearSim)) +
                        (if (r.nearSim >= 0.50f) getString(R.string.rescue_near_miss) else "")
                r.nearSim != null -> getString(R.string.rescue_closest_similarity, sim(r.nearSim))
                else -> getString(R.string.rescue_no_face)
            }
            info.text = getString(R.string.detail_faces, near, r.faces, r.indet, r.reason)
        } else if (card is ItemCard) {
            val r = card.row
            strip.visibility = View.GONE
            showImage(r.uri)
            info.text = getString(
                R.string.detail_item, r.decision, r.reason,
                r.faces, r.matched, r.indet, sim(r.textFrac),
            ) + (r.dupeGroup?.let { getString(R.string.detail_dupe_group, it) } ?: "")
        }
    }

    /**
     * Put a photo on screen without decoding it on the thread that draws.
     *
     * This was `image.setImageBitmap(thumb(uri, 1400))` inline in renderCard:
     * two full passes over the file for a 1400px bitmap, per card, on the UI
     * thread. It is the heaviest main-thread call the app had and the reason
     * moving through a queue felt sticky.
     *
     * Coil now owns it, which also means the previous card's decode is
     * cancelled when this one starts, and a card you come back to is already
     * in memory.
     */
    private fun showImage(uriStr: String, target: Int = CARD_PX) {
        image.load(Uri.parse(uriStr)) { size(target) }
    }

    /** A row of small thumbnails. */
    private fun fillStrip(uris: List<String>, onTap: ((Int) -> Unit)? = null) {
        strip.removeAllViews()
        if (uris.isEmpty()) { strip.visibility = View.GONE; return }
        strip.visibility = View.VISIBLE
        for ((i, u) in uris.withIndex()) {
            strip.addView(Ui.thumb(this, 80).also { t ->
                if (onTap != null) t.setOnClickListener { onTap(i) }
                t.load(Uri.parse(u)) { size(GRID_THUMB_PX); scale(Scale.FILL) }
            })
        }
    }

    /** Similarities are read, not computed from, so they format the same everywhere. */
    private fun sim(v: Float) = "%.3f".format(v)

    /** ", matching גבע at 0.752" — or nothing, when there is nobody close. */
    private fun matchSuffix(r: Db.RescueRow, verify: Boolean): String {
        val name = r.nearName ?: return ""
        val v = r.nearSim
        return when {
            v != null && verify -> getString(R.string.verify_matching_at, name, sim(v))
            v != null -> getString(R.string.junk_closest_at, name, sim(v))
            verify -> getString(R.string.verify_matching, name)
            else -> getString(R.string.junk_closest, name)
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
        is DupeCard -> getString(R.string.a11y_dupe_group, card.members.size)
        is RescueCard -> {
            val r = card.row
            val who = r.nearName?.let { n ->
                r.nearSim?.let { v -> getString(R.string.a11y_closest_match, n, "%.2f".format(v)) }
                    ?: getString(R.string.a11y_closest_match_plain, n)
            } ?: ""
            when (mode) {
                "verify" -> getString(R.string.a11y_saved_as_family, who)
                "junk" -> getString(R.string.a11y_not_yours, who)
                "noface" -> getString(R.string.a11y_no_face)
                else -> getString(R.string.a11y_unsure, who, r.faces)
            }
        }
        is ItemCard -> getString(
            R.string.a11y_scored, card.row.decision, card.row.reason, card.row.faces)
    }

    /** The next few items in the queue, so the screen shows more than one
     *  suspect at a time. Tapping one jumps straight to it. */
    private fun showUpcoming() {
        val next = queue.drop(idx + 1).take(8)
        val uris = next.mapNotNull { (it as? RescueCard)?.row?.uri }
        val base = idx + 1
        fillStrip(uris) { offset -> idx = base + offset; render() }
    }

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
        gridAdapter.submitList(gridItems())   // the queue changed under the grid
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
            Toast.makeText(this, R.string.nothing_to_undo, Toast.LENGTH_SHORT).show(); return
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
