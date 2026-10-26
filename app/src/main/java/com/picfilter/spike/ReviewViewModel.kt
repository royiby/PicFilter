package com.picfilter.spike

import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.ViewModelProvider
import android.app.Application
import android.database.SQLException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.picfilter.spike.data.Db
import com.picfilter.spike.data.*
import com.picfilter.spike.data.RescueRow
import com.picfilter.spike.data.ReviewRow
import com.picfilter.spike.data.DbQueue
import com.picfilter.spike.engine.Promoter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PICFILTER"

/**
 * The review queue, the place in it, and the undo history.
 *
 * All three used to be Activity fields hand-packed into a Bundle on every
 * rotation. The ViewModel holds them for as long as the screen exists in any
 * orientation, and [SavedStateHandle] carries the two that must also survive
 * the process being killed -- the position and the undo stack, because they
 * are the user's work rather than something re-derivable from the database.
 *
 * The queue itself is deliberately NOT saved: it is derivable, and reloading
 * is more correct anyway, since a verdict recorded before the rotation has
 * taken that item out of it.
 */
class ReviewViewModel(
    app: Application,
    private val state: SavedStateHandle,
    private val deps: Deps,
) :
    AndroidViewModel(app) {

    sealed interface Card
    data class ItemCard(val row: ReviewRow) : Card
    data class DupeCard(val groupId: Long, val members: List<Pair<Long, String>>) : Card
    data class RescueCard(val row: RescueRow) : Card

    val mode: String = state["mode"] ?: "golden"

    private val repo = deps.reviewRepo()
    private val dbq = DbQueue(repo)

    private val _queue = MutableStateFlow<List<Card>?>(null)
    val queue: StateFlow<List<Card>?> = _queue.asStateFlow()

    private val _idx = MutableStateFlow(state[S_IDX] ?: 0)
    val idx: StateFlow<Int> = _idx.asStateFlow()

    /** Grid first for the screens you come to look at; card first to decide. */
    private val _grid = MutableStateFlow(state[S_GRID] ?: (mode == "verify"))
    val grid: StateFlow<Boolean> = _grid.asStateFlow()

    private val history = ArrayDeque<Pair<Int, Long>>().apply {
        val at = state.get<IntArray>(S_HIST_AT)
        val ids = state.get<LongArray>(S_HIST_ID)
        if (at != null && ids != null && at.size == ids.size) {
            for (i in at.indices) addLast(at[i] to ids[i])
        }
    }

    val canUndo: Boolean get() = history.isNotEmpty()
    val historyEmpty: Boolean get() = history.isEmpty()

    init { load() }

    private fun persist() {
        state[S_IDX] = _idx.value
        state[S_GRID] = _grid.value
        state[S_HIST_AT] = history.map { it.first }.toIntArray()
        state[S_HIST_ID] = history.map { it.second }.toLongArray()
    }

    fun load() {
        viewModelScope.launch {
            val loaded = withContext(dbq.context) {
                try {
                    when (mode) {
                        "dupes" -> repo.dupeGroups().map { DupeCard(it.first, it.second) }
                        "rescue" -> repo.rescueQueue().map { RescueCard(it) }
                        "noface" -> repo.rescueQueue(listOf("NO_FACE_PLAIN")).map { RescueCard(it) }
                        "verify" -> repo.promotedForReview().map { RescueCard(it) }
                        "junk" -> repo.junkQueue().map { RescueCard(it) }
                        else -> repo.goldenQueue(perReason = 40).map { ItemCard(it) }
                    }
                } catch (e: SQLException) {
                    Log.w(TAG, "REVIEW queue load failed: ${e.message}")
                    emptyList()
                }
            }
            // A restored index can be past the end: verdicts recorded before
            // the rotation have taken their items out of the queue.
            _idx.value = _idx.value.coerceIn(0, maxOf(loaded.size, 0))
            _queue.value = loaded
            persist()
        }
    }

    fun setGrid(on: Boolean) { _grid.value = on; persist() }

    fun goTo(position: Int) { _idx.value = position; _grid.value = false; persist() }

    /**
     * Record a verdict and move on.
     *
     * The screen advances immediately and the row is written behind it, in tap
     * order on [DbQueue]. Un-promoting goes to [Background] rather than
     * viewModelScope, because it must finish even if the user leaves the
     * screen straight afterwards: half an un-promotion leaves a copy in the
     * backed-up folder with no record that it is there.
     */
    fun label(verdict: String) {
        val card = _queue.value?.getOrNull(_idx.value) ?: return
        val id = when (card) {
            is ItemCard -> card.row.id
            is RescueCard -> card.row.id
            // Exhaustive on purpose: an unhandled card type must shout, not
            // silently return. A missing branch once made every button a no-op.
            is DupeCard -> { Log.w(TAG, "label() on a DupeCard"); return }
        }
        dbq.submit { repo.putLabel(id, verdict) }
        if (mode == "verify" && verdict == "DISCARD") {
            val app = getApplication<Application>()
            Background.io.execute {
                try { Promoter.unpromote(app, id) }
                catch (e: Exception) {
                    // Unpromote reaches MediaStore, the file system and the
                    // database; any of them can fail in its own way and none
                    // of them should take the process down.
                    Log.w(TAG, "UNPROMOTE ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        history.addLast(_idx.value to id)
        _idx.value += 1
        persist()
    }

    fun verdict(v: String) {
        val card = _queue.value?.getOrNull(_idx.value) as? DupeCard ?: return
        dbq.submit { repo.putDupeVerdict(card.groupId, v) }
        history.addLast(_idx.value to card.groupId)
        _idx.value += 1
        persist()
    }

    /** @return false when there was nothing to undo. */
    fun undo(): Boolean {
        val last = history.removeLastOrNull() ?: return false
        val dupes = mode == "dupes"
        // Ordered after the write it undoes: [DbQueue] is one thread.
        dbq.submit {
            if (dupes) repo.deleteDupeVerdict(last.second)
            else repo.deleteLabel(last.second)
        }
        _idx.value = last.first
        persist()
        return true
    }

    /**
     * What has been decided so far, as a sentence.
     *
     * This used to be the database talking to itself -- "DISCARD=490 SKIP=33
     * KEEP=13" -- shown at the one moment the screen is telling someone they
     * are finished.
     *
     * The order is fixed here rather than taken from the query, which returns
     * counts descending: a tally that opens on whichever number happens to be
     * biggest reads differently every time, and in practice that is always the
     * discards. A row whose key is not in this list is dropped rather than
     * printed raw, so a new label added later goes missing from the tally
     * instead of leaking its internal name into the UI.
     */
    suspend fun summary(): String = withContext(dbq.context) {
        val ctx = getApplication<Application>()
        fun qty(plural: Int, n: Int) = ctx.resources.getQuantityString(plural, n, n)
        try {
            val order = if (mode == "dupes") listOf(
                "SAME" to R.plurals.tally_same,
                "DIFFERENT" to R.plurals.tally_different,
            ) else listOf(
                "KEEP" to R.plurals.tally_kept,
                "DISCARD" to R.plurals.tally_not_saved,
                "SKIP" to R.plurals.tally_skipped,
            )
            val counts =
                (if (mode == "dupes") repo.dupeVerdictCounts() else repo.labelCounts()).toMap()
            val parts = order.mapNotNull { (key, plural) ->
                val n = counts[key] ?: 0
                if (n <= 0) null else qty(plural, n)
            }
            if (parts.isEmpty()) ""
            else ctx.getString(R.string.tally_so_far, parts.joinToString(", "))
        } catch (e: SQLException) { "" }
    }

    override fun onCleared() {
        dbq.closeAfterPending()
    }

    companion object {
        /**
         * Explicit construction instead of the default factory's reflection.
         *
         * The default factory looks for an (Application[, SavedStateHandle])
         * constructor by reflection. Adding a parameter with a default value
         * does NOT leave one behind -- Kotlin emits a synthetic constructor
         * taking a bitmask -- so the reflective lookup fails at runtime in a
         * build that compiled cleanly. This says what to build.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                ReviewViewModel(app, createSavedStateHandle(), app.deps())
            }
        }

        const val S_IDX = "idx"
        const val S_GRID = "grid"
        const val S_HIST_AT = "hist_at"
        const val S_HIST_ID = "hist_id"
    }
}
