package com.picfilter.spike

import android.app.Application
import android.database.SQLException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.picfilter.spike.data.Db
import com.picfilter.spike.engine.Clusterer
import com.picfilter.spike.engine.Watcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PICFILTER"

/** One person as the home row needs them. */
data class HomePerson(
    val id: Long, val name: String, val subtitle: String,
    val faces: List<String>, val facePath: String?,
)

/** Everything the home screen shows, read in one background pass. */
data class HomeData(
    val promoted: Int,
    val notSaved: Int,
    val undecided: Int,
    val queues: Db.QueueSizes,
    val people: List<HomePerson>,
    val watchOn: Boolean,
    val armed: Boolean,
    val savedThumbs: List<String>,
    val rescueThumbs: List<String>,
    val nofaceThumbs: List<String>,
    val junkThumbs: List<String>,
    val dupeThumbs: List<String>,
)

/**
 * The home screen's figures, and the two background jobs that repair them.
 *
 * Previously the Activity read all of this in onCreate and again on every
 * onResume, and every rotation paid for it twice over. Here it is read once
 * and kept; [refresh] is called when something has actually changed.
 *
 * The avatar rebuild in particular belongs here rather than in the Activity:
 * it runs face detection over a handful of photos per person, and tying that
 * to a screen that can rotate away mid-pass is how it used to be abandoned.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _home = MutableStateFlow<HomeData?>(null)
    val home: StateFlow<HomeData?> = _home.asStateFlow()

    private var avatarsChecked = false
    private var relinkChecked = false

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) { read() }
            _home.value = data
            maybeRebuildAvatars(data.people)
        }
    }

    fun toggleWatcher(on: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                if (on) Watcher.disable(ctx) else Watcher.enable(ctx)
            }
            refresh()
        }
    }

    /** Once per process: give anyone whose crops a re-scan orphaned theirs back. */
    fun relinkFaces() {
        if (relinkChecked) return
        relinkChecked = true
        viewModelScope.launch {
            val fixed = withContext(Dispatchers.IO) {
                try { Clusterer.relinkFaces(getApplication()) }
                catch (e: SQLException) { Log.w(TAG, "RELINK ${e.javaClass.simpleName}"); 0 }
            }
            if (fixed > 0) refresh()
        }
    }

    /**
     * Put a face back on anyone showing an initial.
     *
     * The crops live in the app's own storage while the database holds only
     * their paths, so clearing app data or reinstalling takes the files and
     * leaves the paths. The library survives all of that, so it is the source
     * to rebuild from. Only for people who have no crop AND photos to rebuild
     * it from, so someone with no matches yet does not make every launch pay
     * for a face detector.
     */
    private fun maybeRebuildAvatars(persons: List<HomePerson>) {
        if (avatarsChecked || persons.isEmpty()) return
        avatarsChecked = true
        // faces is empty only when there is genuinely no crop on disk; see the
        // invariant in read().
        if (persons.none { it.faces.isEmpty() }) return
        viewModelScope.launch {
            val rebuilt = withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                val db = Db(ctx)
                val missing = try {
                    persons.count { it.faces.isEmpty() && db.matchedItemsFor(it.id, 1).isNotEmpty() }
                } catch (e: SQLException) {
                    Log.w(TAG, "AVATAR count: ${e.javaClass.simpleName}"); 0
                } finally { db.close() }
                if (missing == 0) 0 else {
                    Log.i(TAG, "AVATAR $missing of ${persons.size} have no face to show; rebuilding")
                    Clusterer.rebuildAvatars(ctx)
                }
            }
            if (rebuilt > 0) refresh()
        }
    }

    /**
     * Is this a first run with nobody enrolled?
     *
     * With nobody enrolled there is nothing for PixKin to protect, so the home
     * screen has nothing to say and setup is the honest destination. Once the
     * user has been through it the flag stays set, so someone who deliberately
     * enrolled no one is not dragged back in on every launch.
     */
    suspend fun shouldOnboard(): Boolean = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val prefs = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONBOARDED, false)) return@withContext false
        val db = Db(ctx)
        val people = try { db.enrolledCount() } catch (e: SQLException) { 0 } finally { db.close() }
        people == 0
    }

    /** Disk and WorkManager only. Must not touch a View. */
    private fun read(): HomeData {
        val ctx = getApplication<Application>()
        val db = Db(ctx)
        // One try for the whole read: these queries fail together or not at
        // all -- the database is either openable or it is not -- and wrapping
        // each one separately only hid which had failed.
        val r = try {
            Read(
                promoted = db.promotionCount(),
                notSaved = db.notSavedCount(),
                undecided = db.undecidedCount(),
                persons = db.enrolledPeople(),
                queues = db.queueSizes(),
                junk = db.junkQueue(limit = 5).map { it.uri },
                dupes = db.dupePreview(limit = 5),
                saved = db.promotedForReview(limit = 5).map { it.uri },
                rescue = db.rescueQueue(limit = 5).map { it.uri },
                noface = db.rescueQueue(listOf("NO_FACE_PLAIN"), limit = 5).map { it.uri },
            )
        } catch (e: SQLException) {
            Log.w(TAG, "HOME read failed, showing an empty screen: ${e.message}")
            Read()
        } finally {
            db.close()
        }

        // A re-scan wipes the cluster crops, so prefer the copies enrolment
        // kept; fall back for people enrolled before that existed.
        //
        // BOTH sources must contain only files that exist. personFaces lists a
        // directory so it already does; personThumbs reads rows and filters
        // them. That invariant is what maybeRebuildAvatars tests to decide
        // whether anyone needs repairing, and when it was broken every person
        // looked as though they already had a face.
        val people = r.persons.map { p ->
            val crops = Clusterer.personFaces(ctx, p.id).ifEmpty { p.thumbs }
            HomePerson(
                p.id, p.name,
                if (p.photos > 0) ctx.resources.getQuantityString(
                    R.plurals.person_photo_count, p.photos, p.photos) else "",
                faces = crops,
                // Chosen here, on the pass that already reads this, rather than
                // by decoding six crops per person at draw time.
                facePath = Clusterer.cleanestCrop(crops),
            )
        }

        val watchOn = Watcher.isEnabled(ctx)
        // A force-stop or a failed run can break the content-trigger chain
        // while the periodic net keeps going. Re-arm whenever the app opens.
        val armed = Watcher.isArmed(ctx)
        if (watchOn && !armed) Watcher.enable(ctx)

        return HomeData(
            promoted = r.promoted, notSaved = r.notSaved, undecided = r.undecided,
            queues = r.queues, people = people, watchOn = watchOn, armed = armed,
            savedThumbs = r.saved, rescueThumbs = r.rescue, nofaceThumbs = r.noface,
            junkThumbs = r.junk, dupeThumbs = r.dupes,
        )
    }

    /** The raw read, before the file system is consulted about faces. */
    private data class Read(
        val promoted: Int = 0,
        val notSaved: Int = 0,
        val undecided: Int = 0,
        val persons: List<Db.PersonChip> = emptyList(),
        val queues: Db.QueueSizes = Db.QueueSizes(0, 0, 0, 0, 0),
        val junk: List<String> = emptyList(),
        val dupes: List<String> = emptyList(),
        val saved: List<String> = emptyList(),
        val rescue: List<String> = emptyList(),
        val noface: List<String> = emptyList(),
    )
}
