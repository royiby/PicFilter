package com.picfilter.spike

import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.ViewModelProvider
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.picfilter.spike.data.Db
import com.picfilter.spike.data.DbQueue
import com.picfilter.spike.engine.Clusterer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "PICFILTER"

/**
 * What the person screen knows, held where a rotation cannot reach it.
 *
 * The Activity used to own the database handle, the photo list and the
 * coroutines that filled it, which meant every rotation closed the handle,
 * threw the list away and read it again. The ViewModel outlives the Activity,
 * so the screen is rebuilt from state that is already there.
 *
 * [SavedStateHandle] carries the intent extras in, and survives process death
 * with them -- no factory, because the Activity's default one populates it.
 */
class PersonViewModel(
    app: Application,
    state: SavedStateHandle,
    private val deps: Deps,
) : AndroidViewModel(app) {

    val personId: Long = state["person_id"] ?: -1L
    /** Blank rather than null would put an empty title bar on the screen. */
    val personName: String = state["person_name"]
        ?: app.getString(R.string.person_fallback)

    private val db = deps.openDb()
    private val dbq = DbQueue(db)

    /** null while loading; the screen tells the two apart. */
    private val _photos = MutableStateFlow<List<Pair<Long, String>>?>(null)
    val photos: StateFlow<List<Pair<Long, String>>?> = _photos.asStateFlow()

    private val _removed = MutableStateFlow(false)
    val removed: StateFlow<Boolean> = _removed.asStateFlow()

    init { load() }

    private fun load() {
        if (personId <= 0) { _photos.value = emptyList(); return }
        viewModelScope.launch {
            _photos.value = withContext(dbq.context) { db.photosOf(personId) }
        }
    }

    /**
     * Take this person out of the enrolled set and drop their crops.
     *
     * On viewModelScope, not the Activity's: the user tapped Remove and the
     * screen closes immediately afterwards, so work tied to the Activity would
     * be cancelled halfway through -- leaving centroids deleted and the
     * cluster still claimed.
     */
    fun remove() {
        viewModelScope.launch {
            val requeued = withContext(dbq.context) {
                try { db.removePerson(personId) } catch (e: android.database.SQLException) {
                    Log.w(TAG, "REMOVEPERSON ${e.javaClass.simpleName}: ${e.message}")
                    -1
                }
            }
            if (requeued < 0) return@launch
            withContext(Dispatchers.IO) {
                Clusterer.personFaces(getApplication(), personId).forEach { File(it).delete() }
            }
            Log.i(TAG, "REMOVEPERSON '$personName' removed; $requeued items to score again")
            _removed.value = true
        }
    }

    override fun onCleared() {
        dbq.closeAfterPending()
    }

    companion object {
        /**
         * Explicit construction instead of the default factory's reflection.
         *
         * The default factory looks for an (Application[, SavedStateHandle])
         * constructor by reflection. Adding a third parameter with a default
         * value does NOT leave one behind -- Kotlin emits a synthetic
         * constructor taking a bitmask -- so the reflective lookup fails at
         * runtime in a build that compiled cleanly. This says what to build.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                PersonViewModel(app, createSavedStateHandle(), app.deps())
            }
        }
    }

}
