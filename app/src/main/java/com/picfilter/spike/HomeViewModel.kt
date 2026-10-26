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
import androidx.lifecycle.viewModelScope
import com.picfilter.spike.data.Db
import com.picfilter.spike.data.*
import com.picfilter.spike.data.PersonChip
import com.picfilter.spike.data.QueueSizes
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
    val queues: QueueSizes,
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
class HomeViewModel(
    app: Application,
    private val deps: Deps,
) : AndroidViewModel(app) {

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

    /**
     * Turn auto-save on or off, where [currentlyOn] is the state the user just
     * tapped. The intent is written before the scheduling, so a crash in
     * between leaves something [Watcher.healSchedule] can finish.
     *
     * The Activity used to inline this, which left this function dead and the
     * intent recorded nowhere.
     */
    fun toggleWatcher(currentlyOn: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                val want = !currentlyOn
                val repo = deps.homeRepo()
                try { repo.setAutoSaveWanted(want) }
                catch (e: SQLException) { Log.w(TAG, "WATCH intent not saved: ${e.message}") }
                finally { repo.close() }
                if (want) Watcher.enable(ctx) else Watcher.disable(ctx)
            }
            refresh()
        }
    }

    private var crashChecked = false

    /**
     * Deal with any crash report waiting from last time.
     *
     * Asked once, and only when there is actually something to ask about: a
     * user whose app never crashes is never interrupted, and the question is
     * answerable because it is about a thing that just happened to them.
     *
     * [ask] is called on the main thread when consent has never been given or
     * refused. Everything else happens without bothering anyone.
     */
    fun crashReports(ask: () -> Unit) {
        if (crashChecked) return
        crashChecked = true
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (CrashReports.pending(ctx).isEmpty()) return@launch
            val wanted = withContext(Dispatchers.IO) {
                val repo = deps.homeRepo()
                try { repo.crashReportsWanted() }
                catch (e: SQLException) { Log.w(TAG, "CRASH consent: ${e.message}"); false }
                finally { repo.close() }
            }
            when (wanted) {
                null -> ask()
                true -> withContext(Dispatchers.IO) {
                    // The manifest keeps collection off, so a consent given
                    // before the last restart has to be re-applied.
                    CrashReports.setCollection(true)
                    CrashReports.send(ctx)
                }
                false -> withContext(Dispatchers.IO) { CrashReports.clear(ctx) }
            }
        }
    }

    /** Record the answer, and act on it for the report already waiting. */
    fun setCrashConsent(on: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                val repo = deps.homeRepo()
                try { repo.setCrashReportsWanted(on) }
                catch (e: SQLException) { Log.w(TAG, "CRASH consent save: ${e.message}") }
                finally { repo.close() }
                CrashReports.setCollection(on)
                if (on) CrashReports.send(ctx) else CrashReports.clear(ctx)
            }
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
                val repo = deps.homeRepo()
                val missing = try {
                    persons.count { it.faces.isEmpty() && repo.matchedItemsFor(it.id, 1).isNotEmpty() }
                } catch (e: SQLException) {
                    Log.w(TAG, "AVATAR count: ${e.javaClass.simpleName}"); 0
                } finally { repo.close() }
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
        val repo = deps.homeRepo()
        val people = try { repo.enrolledCount() } catch (e: SQLException) { 0 } finally { repo.close() }
        people == 0
    }

    /** Disk and WorkManager only. Must not touch a View. */
    private fun read(): HomeData {
        val ctx = getApplication<Application>()
        val repo = deps.homeRepo()
        // One try for the whole read: these queries fail together or not at
        // all -- the database is either openable or it is not -- and wrapping
        // each one separately only hid which had failed.
        val r = try {
            Read(
                promoted = repo.promotionCount(),
                notSaved = repo.notSavedCount(),
                undecided = repo.undecidedCount(),
                persons = repo.enrolledPeople(),
                queues = repo.queueSizes(),
                junk = repo.junkQueue(limit = 5).map { it.uri },
                dupes = repo.dupePreview(limit = 5),
                saved = repo.promotedForReview(limit = 5).map { it.uri },
                rescue = repo.rescueQueue(limit = 5).map { it.uri },
                noface = repo.rescueQueue(listOf("NO_FACE_PLAIN"), limit = 5).map { it.uri },
                wanted = repo.autoSaveWanted(),
            )
        } catch (e: SQLException) {
            Log.w(TAG, "HOME read failed, showing an empty screen: ${e.message}")
            Read()
        } finally {
            repo.close()
        }

        // A re-scan wipes the cluster crops, so prefer the copies enrolment
        // kept; fall back for people enrolled before that existed.
        //
        // BOTH sources must contain only files that exist. personFaces lists a
        // directory so it already does; personThumbs reads rows and filters
        // them. That invariant is what maybeRebuildAvatars tests to decide
        // whether anyone needs repairing, and when it was broken every person
        // looked as though they already had a face.
        // The crash handler must not read the database while the process is
        // dying, so it is told the names on a pass that reads them regardless.
        CrashReports.rememberNames(ctx, r.persons.map { it.name })

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

        // The old heal here was `if (watchOn && !armed) enable()`, which could
        // repair a half-broken chain but never a missing one, because it needed
        // auto-save to be on already. A restored database arrives with the
        // intent and no schedule at all, which is exactly the case it could not
        // see. healSchedule reads the intent instead of the schedule.
        val watchOn = Watcher.healSchedule(ctx, r.wanted)
        val armed = Watcher.isArmed(ctx)
        // First launch after this shipped, or a genuinely new install: write
        // down what WorkManager already held so the question is answered from
        // then on. Once per install, hence the short-lived handle.
        if (r.wanted == null) {
            val repo = deps.homeRepo()
            try { repo.setAutoSaveWanted(watchOn) }
            catch (e: SQLException) { Log.w(TAG, "WATCH intent not recorded: ${e.message}") }
            finally { repo.close() }
        }

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
        val persons: List<PersonChip> = emptyList(),
        val queues: QueueSizes = QueueSizes(0, 0, 0, 0, 0),
        val junk: List<String> = emptyList(),
        val dupes: List<String> = emptyList(),
        val saved: List<String> = emptyList(),
        val rescue: List<String> = emptyList(),
        val noface: List<String> = emptyList(),
        /** Null until someone has said either way; see [Db.autoSaveWanted]. */
        val wanted: Boolean? = null,
    )

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
                HomeViewModel(app, app.deps())
            }
        }
    }

}
