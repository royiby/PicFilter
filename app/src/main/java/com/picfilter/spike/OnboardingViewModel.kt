package com.picfilter.spike

import android.app.Application
import android.database.SQLException
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.picfilter.spike.data.Db
import com.picfilter.spike.data.DbQueue
import com.picfilter.spike.engine.Clusterer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PICFILTER"

/**
 * Where the user is in setup, and what the scan found.
 *
 * The step used to live only in which show*() had last been called, so the
 * Activity packed it into a Bundle by hand on every rotation. It lives here
 * now, with the candidate list beside it -- a list that costs four and a half
 * minutes of face detection to produce and was being re-read from the database
 * on every configuration change.
 */
class OnboardingViewModel(app: Application, private val state: SavedStateHandle) :
    AndroidViewModel(app) {

    private val db = Db(app)
    private val dbq = DbQueue(db)

    private val _step = MutableStateFlow(state[S_STEP] ?: STEP_INTRO)
    val step: StateFlow<String> = _step.asStateFlow()

    var candidates: List<Db.Candidate> = emptyList(); private set
    var idx: Int = state[S_IDX] ?: 0; private set
    var named: Int = state[S_NAMED] ?: 0; private set
    // state[S_FIRST] is NOT only what markFirstRun wrote. A SavedStateHandle is
    // seeded from the Activity's intent extras, and the key is the extra's name,
    // so whatever the launcher put under "first_run" arrives here first. It was
    // the string "1", and reading it as a Boolean threw ClassCastException in
    // this constructor -- before onCreate had drawn anything -- on the one path
    // a new user always takes. See [firstRunFlag].
    val firstRun: Boolean = firstRunFlag(state.get<Any>(S_FIRST))

    /** Read once per visit to the summary. */
    var skipped: List<Db.Candidate>? = null; private set

    /**
     * How many people are enrolled **in total**, which is not [named].
     *
     * [named] counts people named during this run of setup. The summary used
     * it for both its headline and its warning, so someone who already had
     * five people, tapped Add and claimed nobody new was told "0 people
     * enrolled" and that every group photo of their own family would be
     * treated as a deletion candidate. The warning is a claim about the
     * database; it has to be read from the database.
     */
    var enrolled: Int? = null; private set

    fun goTo(step: String) { _step.value = step; state[S_STEP] = step }

    private fun persist() {
        state[S_IDX] = idx
        state[S_NAMED] = named
    }

    fun markFirstRun(on: Boolean) { state[S_FIRST] = on }

    /** Whether anything is already extracted, for the "continue" button. */
    suspend fun hasCandidates(): Boolean = withContext(dbq.context) {
        try { db.candidates(limit = 1, thumbsEach = 1).isNotEmpty() }
        catch (e: SQLException) { false }
    }

    fun loadCandidates(keepIndex: Boolean = false, then: () -> Unit) {
        viewModelScope.launch {
            candidates = withContext(dbq.context) {
                try { db.candidates(limit = 20, thumbsEach = 6) }
                catch (e: SQLException) { emptyList() }
            }
            if (!keepIndex) idx = 0
            persist()
            then()
        }
    }

    /** Both of the summary's facts, on one trip to the database. */
    fun loadSkipped(then: () -> Unit) {
        viewModelScope.launch {
            val read = withContext(dbq.context) {
                try { db.skippedClusters(minSize = 5) to db.enrolledCount() }
                catch (e: SQLException) { emptyList<Db.Candidate>() to 0 }
            }
            skipped = read.first
            enrolled = read.second
            then()
        }
    }

    fun enrolCluster(clusterId: Long, name: String, then: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Clusterer.enrolCluster(getApplication(), clusterId, name) }
            named++; idx++; persist()
            then()
        }
    }

    fun decide(clusterId: Long, status: String, then: () -> Unit) {
        viewModelScope.launch {
            withContext(dbq.context) {
                try { db.setClusterStatus(clusterId, status, null) }
                catch (e: SQLException) { Log.w(TAG, "CLUSTER status: ${e.message}") }
            }
            idx++; persist()
            then()
        }
    }

    fun enrolFromUris(name: String, uris: List<Uri>, then: (Clusterer.PickOutcome) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                try { Clusterer.enrolFromUris(getApplication(), name, uris) }
                catch (e: CancellationException) {
                    // Cancellation is not a failure, and swallowing it here
                    // would leave the caller waiting on a coroutine that has
                    // already been told to stop.
                    throw e
                } catch (e: Exception) {
                    // Deliberately broad: decoding someone's arbitrary pick
                    // reaches MediaStore, the bitmap decoder, ML Kit and
                    // TFLite, each of which fails in its own way, and none
                    // should lose the whole setup flow.
                    Log.w(TAG, "PICKENROL ${e.javaClass.simpleName}: ${e.message}")
                    Clusterer.PickOutcome(-1, 0, uris.size, 0)
                }
            }
            if (r.ok) { named++; persist() }
            then(r)
        }
    }

    suspend fun whatsAppPreview(n: Int): Pair<Int, List<Uri>> = withContext(Dispatchers.IO) {
        Clusterer.whatsAppPreview(getApplication(), n)
    }

    suspend fun ensureDestFolder(): Boolean = withContext(Dispatchers.IO) {
        com.picfilter.spike.engine.Promoter.ensureDestFolder(getApplication())
    }

    override fun onCleared() {
        dbq.closeAfterPending()
    }

    companion object {
        const val STEP_INTRO = "intro"
        const val STEP_SCAN = "scan"
        const val STEP_NOFACES = "nofaces"
        const val STEP_CANDIDATE = "candidate"
        const val STEP_SUMMARY = "summary"
        const val STEP_BACKUP = "backup"

        /** Every step name, for the `step` extra that jumps straight to one. */
        val STEPS = setOf(
            STEP_INTRO, STEP_SCAN, STEP_NOFACES, STEP_CANDIDATE, STEP_SUMMARY, STEP_BACKUP,
        )

        private const val S_STEP = "step"
        private const val S_IDX = "idx"
        private const val S_NAMED = "named"
        private const val S_FIRST = "first_run"
    }
}

/**
 * Read a first-run flag that may have come from either side.
 *
 * A ViewModel's SavedStateHandle is seeded from the launching intent's extras,
 * so this value can be the Boolean [OnboardingViewModel.markFirstRun] stored or
 * the raw extra the Activity was started with -- and adb can only send strings,
 * so a dev op cannot pass a Boolean at all. Casting blind crashed the app on a
 * genuine first run. Total by construction: anything unrecognised is false,
 * because the cost of a wrong false is the intro copy reading slightly off and
 * the cost of a throw here is an app that cannot be opened.
 */
internal fun firstRunFlag(value: Any?): Boolean = when (value) {
    is Boolean -> value
    is String -> value == "1" || value.equals("true", ignoreCase = true)
    is Number -> value.toInt() == 1
    else -> false
}
