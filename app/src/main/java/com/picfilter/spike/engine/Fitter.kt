package com.picfilter.spike.engine

import android.content.Context
import android.util.Log
import com.picfilter.spike.data.Db

private const val TAG = "PICFILTER"

/**
 * Fit a per-person match threshold from human verdicts.
 *
 * A single global threshold is wrong here because the centroids are not equally
 * reliable: measured at 0.55, אבי/אמא/מישי were 100% correct while גבע was 24.7%.
 * Raising the bar globally would throw away recall for the people the model
 * handles perfectly, in order to contain the ones it does not.
 */
object Fitter {

    private const val TARGET_PRECISION = 0.90
    private const val MIN_SAMPLES = 6

    fun fit(ctx: Context, target: Double = TARGET_PRECISION) {
        val db = Db(ctx)
        val rows = db.labelledMatches()
        if (rows.isEmpty()) {
            Log.w(TAG, "FIT no human-judged matches yet — review promoted items first")
            db.close(); return
        }
        Log.i(TAG, "FIT target_precision=${(target * 100).toInt()}% samples=${rows.size}")

        for ((pid, group) in rows.groupBy { it.personId }) {
            val name = group.first().name
            if (group.size < MIN_SAMPLES) {
                Log.i(TAG, "FIT  %-10s only %d samples — leaving at global %.2f".format(
                    name, group.size, FaceEngine.MATCH_THRESHOLD))
                continue
            }
            // lowest threshold that still reaches the target precision
            val candidates = (group.map { it.sim }.distinct() + FaceEngine.MATCH_THRESHOLD).sorted()
            var chosen: Float? = null
            for (t in candidates) {
                val kept = group.filter { it.sim >= t }
                if (kept.isEmpty()) continue
                val prec = kept.count { it.correct }.toDouble() / kept.size
                if (prec >= target) { chosen = t; break }
            }
            val baseline = group.count { it.correct }.toDouble() / group.size
            if (chosen == null) {
                Log.w(TAG, "FIT  %-10s CANNOT reach %.0f%% at any threshold (best base %.0f%%, n=%d)".format(
                    name, target * 100, baseline * 100, group.size))
                // fail safe: demand near-certainty rather than leaving it permissive
                db.setPersonThreshold(pid, 0.85f)
                Log.w(TAG, "FIT  %-10s -> 0.85 (fail-safe)".format(name))
            } else {
                val kept = group.filter { it.sim >= chosen }
                val prec = kept.count { it.correct }.toDouble() / kept.size
                db.setPersonThreshold(pid, chosen)
                Log.i(TAG, "FIT  %-10s -> %.2f  (was %.0f%% correct over %d, now %.0f%% over %d)".format(
                    name, chosen, baseline * 100, group.size, prec * 100, kept.size))
            }
        }
        db.close()
    }
}
