package com.picfilter.spike

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.picfilter.spike.engine.Clusterer

private const val TAG = "PICFILTER"

/**
 * The onboarding library scan, held outside the screen that watches it.
 *
 * It used to run on a bare Thread owned by [OnboardingActivity], which meant
 * turning the phone sideways during it destroyed the Activity, abandoned the
 * scan and started the user over -- four and a half minutes of face detection,
 * thrown away by a rotation. The progress callback also posted into an Activity
 * that no longer existed.
 *
 * So the scan belongs to the process. A recreated screen re-attaches to the one
 * already running and draws its current progress, and a scan that finishes
 * while no screen is watching simply leaves its clusters in the database, which
 * is where the next screen reads them from anyway.
 *
 * Only one at a time: [start] on a running scan is a no-op rather than a second
 * pass competing for the same detector.
 */
object Scan {

    @Volatile var running = false; private set
    @Volatile var done = 0; private set
    @Volatile var total = 0; private set
    @Volatile var phase = ""; private set

    /** True once a scan has run in this process, so a watcher knows to move on. */
    @Volatile var finished = false; private set

    private val main = Handler(Looper.getMainLooper())

    /**
     * Who to tell when something changes, or null.
     *
     * Held as a plain reference, so the watching screen MUST clear it when it
     * goes away -- otherwise this object keeps that Activity alive for the life
     * of the process.
     */
    @Volatile private var watcher: (() -> Unit)? = null

    fun observe(cb: (() -> Unit)?) { watcher = cb }

    private fun notifyWatcher() = main.post { watcher?.invoke() }

    /** Pass the APPLICATION context: this outlives any screen. */
    fun start(appCtx: Context) {
        if (running) { Log.i(TAG, "SCAN already running, re-attaching"); return }
        running = true
        finished = false
        done = 0; total = 0
        notifyWatcher()
        Background.io.execute {
            try {
                Clusterer.bootstrap(appCtx, Clusterer.SAMPLE_SIZE) { d, t, p ->
                    done = d; total = t; phase = p
                    notifyWatcher()
                }
            } catch (e: Exception) {
                Log.w(TAG, "SCAN failed ${e.javaClass.simpleName}: ${e.message}")
            }
            running = false
            finished = true
            notifyWatcher()
        }
    }

    /** Consume the finished flag, so re-entering setup does not skip the intro. */
    fun consumeFinished(): Boolean {
        val f = finished
        finished = false
        return f
    }

    val percent: Int get() = if (total > 0) done * 100 / total else 0
}
