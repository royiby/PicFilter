package com.picfilter.spike

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
class PersonViewModel(app: Application, state: SavedStateHandle) : AndroidViewModel(app) {

    val personId: Long = state["person_id"] ?: -1L
    val personName: String = state["person_name"] ?: ""

    private val db = Db(app)
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
}
