package com.picfilter.spike.engine

import android.content.Context
import android.util.Log
import com.picfilter.spike.data.Db
import kotlin.math.abs

private const val TAG = "PICFILTER"

/**
 * Threshold sensitivity sweep, derived entirely from stored per-face metrics —
 * no re-detection, no re-embedding, instant.
 *
 * Purpose: the shipped quality bar's sharpness and pose clauses are guesses, and
 * the first Phase 1 run showed they dominate the measured size clause. This maps
 * the tradeoff space so that when labels arrive, fitting is a lookup rather than
 * a search.
 *
 * Caveat: with nothing enrolled every quality face is NO_MATCH, so these counts
 * are the UPPER BOUND on discard candidates. Enrollment converts some to KEEP.
 */
object Sweep {

    fun run(ctx: Context) {
        val db = Db(ctx)
        val faces = db.facesForSweep()
        val text = db.textSignals()
        val itemIds = text.keys
        Log.i(TAG, "SWEEP items=${itemIds.size} faces=${faces.size}")

        val byItem = faces.groupBy { it.itemId }

        Log.i(TAG, "SWEEP --- quality gate sensitivity (px / sharpness / pose) ---")
        for (px in intArrayOf(30, 40, 50)) {
            for (sharp in doubleArrayOf(0.0, 6.0, 12.0, 25.0)) {
                for (pose in floatArrayOf(90f, 60f, 45f)) {
                    var indetFaces = 0; var okFaces = 0
                    var crowdDiscard = 0; var indetBlocked = 0
                    var textDiscard = 0; var fewFaces = 0; var plain = 0
                    for (id in itemIds) {
                        val fs = byItem[id] ?: emptyList()
                        var indet = 0
                        for (f in fs) {
                            val ok = f.origPx >= px && f.sharpness >= sharp &&
                                     abs(f.yaw) <= pose && abs(f.roll) <= pose
                            if (ok) okFaces++ else { indetFaces++; indet++ }
                        }
                        val (tf, tb) = text[id] ?: (0f to 0)
                        val heavy = tf > 0.08f || tb > 10
                        when {
                            indet >= 1 -> indetBlocked++
                            fs.size >= 3 -> crowdDiscard++
                            fs.size in 1..2 -> fewFaces++
                            heavy -> textDiscard++
                            else -> plain++
                        }
                    }
                    val total = indetFaces + okFaces
                    Log.i(TAG, "SWEEP px=$px sharp=$sharp pose=${pose.toInt()} " +
                        "indet_faces=${pc(indetFaces, total)} " +
                        "blocked=$indetBlocked crowd_discard=$crowdDiscard " +
                        "text_discard=$textDiscard few=$fewFaces plain=$plain " +
                        "total_discard=${crowdDiscard + textDiscard}")
                }
            }
        }

        Log.i(TAG, "SWEEP --- dupe hamming sensitivity ---")
        for (h in intArrayOf(2, 4, 6, 8, 10, 12, 16)) {
            val (groups, extra) = db.dupeGroupsAt(h)
            Log.i(TAG, "SWEEP hamming=$h groups=$groups redundant=$extra " +
                "(${pc(extra, itemIds.size)} of items)")
        }
        db.close()
    }

    private fun pc(n: Int, d: Int) = if (d == 0) "n/a" else "$n/$d=${"%.1f".format(n * 100.0 / d)}%"
}
