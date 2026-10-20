package com.picfilter.spike.engine

import android.content.Context
import android.util.Log
import com.picfilter.spike.data.Db

private const val TAG = "PICFILTER"

/**
 * One labelled point on a person's similarity axis: a face at [sim] that a
 * human says either is ([isPerson]) or is not that person.
 */
data class ThresholdPoint(val personId: Long, val name: String, val sim: Float, val isPerson: Boolean)

/** What [fitThreshold] decided, and the evidence it decided on. */
data class Fit(
    val threshold: Float,
    /** Precision over the points the threshold keeps; null when it keeps none. */
    val precision: Double?,
    val kept: Int,
    val belowDefault: Int,
    val note: String,
)

/**
 * Fit a per-person match threshold from human verdicts.
 *
 * A single global threshold is wrong here because the centroids are not equally
 * reliable: measured at 0.55, אבי/אמא/מישי were 100% correct while גבע was 24.7%.
 * Raising the bar globally would throw away recall for the people the model
 * handles perfectly, in order to contain the ones it does not.
 *
 * ## The ratchet, and why it was a defect
 *
 * The first version fitted from [Db.labelledMatches] alone -- faces the scorer
 * had already called a MATCH. Every one of those sits at or above the person's
 * current bar, so the lowest candidate the search could ever consider WAS that
 * bar. The fitted threshold could rise and never fall, whatever the evidence,
 * because the evidence that a lower bar is safe lives entirely in faces the
 * current bar rejects and the query filtered out.
 *
 * That is not a tuning weakness, it is the wrong estimator: precision measured
 * only over what you kept cannot see a false negative, and a threshold is a
 * trade between the two. The consequence was concrete -- thresholds fitted at
 * 0.55-0.65 for three people became unrecoverable the moment the global bar
 * rose to 0.70.
 *
 * So the points now come from both sides: [Db.labelledMatches] above the bar
 * and [Db.labelledNearMisses] below it.
 */
object Fitter {

    private const val TARGET_PRECISION = 0.90
    private const val MIN_SAMPLES = 6

    /**
     * How many labelled points must sit BELOW the global default before a fit
     * is allowed to go below it.
     *
     * Lowering a bar is the direction that admits strangers, and the sample
     * down there is biased -- the rescue queue is ordered by near-miss
     * similarity and the attribution rule drops crowded photos. Three points
     * can reach 90% precision by being three points. This demands the claim be
     * made on more than that.
     */
    private const val MIN_BELOW_SAMPLES = 5

    /**
     * No fit goes under this, on any evidence.
     *
     * Calibration over 247 human labels found the keep-rate flat across the
     * bands beneath 0.55 and ZERO in the two closest to it -- 15 photos whose
     * faces came nearest to matching an enrolled person, none of them family.
     * Below here a near miss is not weak evidence, it is evidence of a
     * stranger. It is also the floor on the points themselves: nothing under
     * [Clusterer.VOUCH_MIN_SIM] is collected, so a lower threshold would be
     * fitted on an empty band.
     */
    const val ABSOLUTE_FLOOR = Clusterer.VOUCH_MIN_SIM

    /**
     * The lowest threshold reaching [target] precision, given labelled points
     * on both sides of the boundary.
     *
     * Pure arithmetic, lifted out of [fit] so it can be tested without a device
     * -- the same move [matchFace] got, and for the same reason: this decides
     * whose photos survive and it was trapped behind a Context and a database.
     */
    fun fitThreshold(
        points: List<ThresholdPoint>,
        target: Double = TARGET_PRECISION,
        default: Float = FaceEngine.MATCH_THRESHOLD,
        floor: Float = ABSOLUTE_FLOOR,
        minBelow: Int = MIN_BELOW_SAMPLES,
    ): Fit? {
        if (points.isEmpty()) return null
        val candidates = (points.map { it.sim } + default)
            .filter { it >= floor }
            .distinct()
            .sorted()
        var thinEvidence = false
        for (t in candidates) {
            val kept = points.filter { it.sim >= t }
            if (kept.isEmpty()) continue
            val prec = kept.count { it.isPerson }.toDouble() / kept.size
            if (prec < target) continue
            val below = points.count { it.sim >= t && it.sim < default }
            // Reaching the target is necessary and not sufficient below the
            // default: going down needs enough points down there to mean it.
            if (t < default && below < minBelow) { thinEvidence = true; continue }
            return Fit(
                t, prec, kept.size, below,
                when {
                    t < default -> "lowered on $below point(s) below the default"
                    t > default -> "raised to contain false matches"
                    thinEvidence -> "held at the default — too little evidence below it"
                    else -> "held at the default"
                },
            )
        }

        // Nothing reached the target. That means two different things, and
        // telling them apart matters more than it looks.
        //
        // With labelled points ABOVE the default, it is the finding the
        // fail-safe was written for: this centroid is wrong even where it is
        // most confident, so the caller should demand near-certainty.
        //
        // With none, we have simply never seen how this person behaves above
        // the bar. Every point is a near miss, and near misses say only that
        // the bar is not too LOW. Reading that as "the centroid is unreliable"
        // and clamping to 0.85 would punish a person for evidence collected
        // entirely in the band the bar already rejects -- which is the real
        // library's current state, where one person has 25 discarded near
        // misses and no reviewed match at all.
        if (points.none { it.sim >= default }) {
            return Fit(
                default, null, 0, points.size,
                "held at the default — nothing labelled above it to judge",
            )
        }
        return null
    }

    fun fit(ctx: Context, target: Double = TARGET_PRECISION) {
        val db = Db(ctx)
        val existing = db.personThresholds()
        val matches = db.labelledMatches()
        val nearMisses = db.labelledNearMisses(ABSOLUTE_FLOOR)
        val rows = (matches + nearMisses).map {
            ThresholdPoint(it.personId, it.name, it.sim, it.correct)
        }
        if (rows.isEmpty()) {
            Log.w(TAG, "FIT no human-judged faces yet. Matches come from the " +
                "'Saved photos' queue and near misses from 'More family photos' — " +
                "a fit needs both sides of the boundary, so review some of each.")
            db.close(); return
        }
        Log.i(TAG, "FIT target_precision=${(target * 100).toInt()}% " +
            "points=${rows.size} (above=${matches.size} below=${nearMisses.size}) " +
            "floor=${"%.2f".format(ABSOLUTE_FLOOR)} default=${"%.2f".format(FaceEngine.MATCH_THRESHOLD)}")
        if (nearMisses.isEmpty()) {
            Log.w(TAG, "FIT  no labelled faces below the bar, so this fit can only " +
                "raise thresholds — review 'More family photos' to let it lower them")
        }

        /**
         * Write only when the bar moves. Storing a number equal to the bar
         * already in force changes no verdict, and [Db.setPersonThreshold]
         * bumps the centroid revision, which restales every score in the
         * database. A fit that agrees with the status quo must be free.
         */
        fun apply(pid: Long, name: String, t: Float): Boolean {
            val effective = existing[pid] ?: FaceEngine.MATCH_THRESHOLD
            if (t == effective) {
                Log.i(TAG, "FIT  %-10s stays at %.2f — nothing to rescore".format(name, t))
                return false
            }
            db.setPersonThreshold(pid, t)
            return true
        }

        for ((pid, group) in rows.groupBy { it.personId }) {
            val name = group.first().name
            val below = group.count { it.sim < FaceEngine.MATCH_THRESHOLD }
            if (group.size < MIN_SAMPLES) {
                Log.i(TAG, "FIT  %-10s only %d point(s) — leaving at global %.2f".format(
                    name, group.size, FaceEngine.MATCH_THRESHOLD))
                continue
            }
            val baseline = group.count { it.isPerson }.toDouble() / group.size
            val fitted = fitThreshold(group, target)
            if (fitted == null) {
                Log.w(TAG, "FIT  %-10s CANNOT reach %.0f%% at any threshold (best base %.0f%%, n=%d)".format(
                    name, target * 100, baseline * 100, group.size))
                // fail safe: demand near-certainty rather than leaving it permissive
                if (apply(pid, name, 0.85f)) Log.w(TAG, "FIT  %-10s -> 0.85 (fail-safe)".format(name))
            } else {
                val moved = apply(pid, name, fitted.threshold)
                val prec = fitted.precision
                    ?.let { "now %.0f%% over %d".format(it * 100, fitted.kept) }
                    ?: "nothing above it to measure"
                Log.i(TAG, "FIT  %-10s %s %.2f  (was %.0f%% correct over %d, %s; %d below the default) — %s".format(
                    name, if (moved) "->" else "==", fitted.threshold,
                    baseline * 100, group.size, prec, below, fitted.note))
            }
        }
        db.close()
    }
}
