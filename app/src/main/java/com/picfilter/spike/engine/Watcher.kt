package com.picfilter.spike.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.picfilter.spike.MainActivity
import com.picfilter.spike.data.Db
import java.util.concurrent.TimeUnit

private const val TAG = "PICFILTER"
private const val NET = "picfilter-watch"
private const val DRAIN = "picfilter-drain"
private const val KEY_SLOT = "slot"
private const val SLOT_A = "a"
private const val SLOT_B = "b"
private const val CHANNEL = "picfilter"

/**
 * Phase 3: keep the rescue running without anyone asking.
 *
 * New WhatsApp arrivals are scored and keepers are copied into the backed-up
 * folder automatically. It NEVER discards: copying is additive and reversible,
 * deleting is not, so discard candidates are only ever reported.
 *
 * Two triggers, because neither alone is enough:
 *
 *  1. A content-URI trigger on the images collection. The OS wakes the job when
 *     media changes, so a new photo is handled in minutes rather than hours.
 *     Content triggers only exist on one-time work, so each run arms the next.
 *  2. A long periodic job as a safety net, for anything the trigger missed --
 *     changes while the device was off, a Doze window that swallowed the
 *     callback, or a trigger chain broken by a force-stop.
 *
 * The trigger fires on ANY image change, not just WhatsApp's. That is fine:
 * [Pipeline.scanMedia] filters to WhatsApp paths, and a run with nothing new
 * costs one query.
 *
 * The two trigger slots exist to avoid self-cancellation. A worker cannot
 * REPLACE the unique work it is itself running, so a run under slot A arms
 * slot B and vice versa.
 */
object Watcher {

    fun enable(ctx: Context, hours: Long = 6) {
        val net = PeriodicWorkRequestBuilder<WatchWorker>(hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(NET, ExistingPeriodicWorkPolicy.UPDATE, net)
        arm(ctx, SLOT_A)
        Log.i(TAG, "WATCH enabled: media trigger + ${hours}h net (battery-not-low)")
    }

    /**
     * Queue the next chunk of a backlog.
     *
     * Delayed on purpose: one :scoring process serves whatever work is already
     * queued and only exits when the queue drains, so firing the next chunk
     * immediately would accumulate the leak in that one process and defeat the
     * containment. The delay lets it exit first.
     */
    fun scheduleDrain(ctx: Context, delaySeconds: Long = 90) {
        val req = OneTimeWorkRequestBuilder<WatchWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(DRAIN, ExistingWorkPolicy.KEEP, req)
        Log.i(TAG, "WATCH drain queued in ${delaySeconds}s")
    }

    fun disable(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(NET)
        wm.cancelUniqueWork(DRAIN)
        wm.cancelUniqueWork(slotWork(SLOT_A))
        wm.cancelUniqueWork(slotWork(SLOT_B))
        Log.i(TAG, "WATCH disabled")
    }

    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<WatchWorker>().build())
        Log.i(TAG, "WATCH one-off run enqueued")
    }

    fun isEnabled(ctx: Context): Boolean = try {
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(NET).get()
            .any { !it.state.isFinished }
    } catch (e: Exception) { false }

    /** True when a content trigger is waiting. Enabling heals a broken chain. */
    fun isArmed(ctx: Context): Boolean = try {
        val wm = WorkManager.getInstance(ctx)
        listOf(SLOT_A, SLOT_B).any { slot ->
            wm.getWorkInfosForUniqueWork(slotWork(slot)).get().any { !it.state.isFinished }
        }
    } catch (e: Exception) { false }

    /** Logs what WorkManager actually holds, for `op=watchstat`. */
    fun status(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        fun states(name: String) = try {
            wm.getWorkInfosForUniqueWork(name).get().map { it.state.name }
        } catch (e: Exception) { listOf("ERR ${e.javaClass.simpleName}") }
        Log.i(TAG, "WATCH stat net=${states(NET)} " +
            "a=${states(slotWork(SLOT_A))} b=${states(slotWork(SLOT_B))} " +
            "enabled=${isEnabled(ctx)} armed=${isArmed(ctx)}")
    }

    private fun slotWork(slot: String) = "picfilter-trigger-$slot"

    private fun arm(ctx: Context, slot: String) {
        val c = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            // Let a WhatsApp batch finish landing before scoring it, but cap the
            // wait so a steady trickle cannot defer the job indefinitely.
            .setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(10, TimeUnit.MINUTES)
            .build()
        val req = OneTimeWorkRequestBuilder<WatchWorker>()
            .setConstraints(c)
            .setInputData(workDataOf(KEY_SLOT to slot))
            .build()
        // KEEP, not REPLACE: enable() is called again on every app open to heal a
        // broken chain, and REPLACE would cancel a trigger that is already waiting
        // (resetting its content baseline) or, worse, a run in progress. KEEP still
        // arms a slot whose last run has finished, which is the re-arm case.
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(slotWork(slot), ExistingWorkPolicy.KEEP, req)
        Log.i(TAG, "WATCH trigger armed, slot=$slot")
    }

    /** Called by a finished trigger run to arm the other slot. */
    internal fun rearm(ctx: Context, finishedSlot: String) =
        arm(ctx, if (finishedSlot == SLOT_A) SLOT_B else SLOT_A)

    fun notifyDigest(ctx: Context, newItems: Int, protectedNow: Int, candidates: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "PixKin", NotificationManager.IMPORTANCE_LOW)
        )
        val tap = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = buildString {
            append("$newItems new photo${if (newItems == 1) "" else "s"}")
            if (protectedNow > 0) append(" · $protectedNow backed up")
            if (candidates > 0) append(" · $candidates to review")
        }
        nm.notify(
            1001,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(com.picfilter.spike.R.drawable.ic_notification)
                .setContentTitle("PixKin")
                .setContentText(text)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
        )
    }
}

/**
 * How many images one pass will score.
 *
 * Containment does not make a pass cheaper, only disposable -- the peak still
 * scales with the batch, so the batch is a ceiling. Measured in :scoring:
 * ~170 MB of fixed cost plus ~0.26 MB per image, so 100 images peaked at 189 MB
 * and 253 at 236 MB. The code used to ask for 5000 in one go, which
 * extrapolates to ~1.5 GB: killed, retried, and then abandoned, leaving a large
 * first-install library permanently unscored. 300 is ~250 MB, which a
 * throwaway process can afford.
 *
 * A pass that hits the cap schedules the next one, so a backlog drains over
 * several passes instead of dying in one.
 */
const val MAX_PER_PASS = 300

/** What one pass did, for the digest notification and the logs. */
data class PassOutcome(
    val newItems: Int, val scored: Int, val protectedNow: Int, val candidates: Int,
)

/**
 * Scan, score whatever is new, copy the keepers.
 *
 * A top-level function because it runs in two different processes: normally in
 * :scoring (see [ScoringService], which exits afterwards to give ML Kit's
 * leaked native heap back), and in this one only as a fallback when the handoff
 * cannot be made.
 */
fun runRescuePass(ctx: Context): PassOutcome {
    val db0 = Db(ctx)
    val before = db0.itemCount()
    val promotedBefore = db0.promotionCount()
    db0.close()

    Pipeline.scanMedia(ctx, 5000)

    val db1 = Db(ctx)
    val newItems = db1.itemCount() - before
    val pending = db1.unscoredCount()
    db1.close()

    if (pending == 0) {
        Log.i(TAG, "WATCH nothing new")
        return PassOutcome(newItems, 0, 0, 0)
    }

    Pipeline.score(ctx, MAX_PER_PASS)
    // Auto-promote keepers only. Auto-discard stays off by construction:
    // the promoter refuses DISCARD_CANDIDATE and nothing here trashes.
    Promoter.run(ctx, dryRun = false, decisions = listOf("KEEP"))

    // Learn from anything the user vouched for since the last pass. Monotonic,
    // so it requeues only the items that matched nobody; the drain picks those
    // up on a later wake.
    try {
        Clusterer.extendFromLabels(ctx)
    } catch (e: Exception) {
        Log.w(TAG, "EXTEND skipped: ${e.javaClass.simpleName}: ${e.message}")
    }

    val db2 = Db(ctx)
    val protectedNow = db2.promotionCount() - promotedBefore
    val candidates = db2.decisionCounts()
        .firstOrNull { it.first == "DISCARD_CANDIDATE" }?.second ?: 0
    db2.close()

    Log.i(TAG, "WATCH new=$newItems scored=$pending protected=$protectedNow")
    if (newItems > 0 || protectedNow > 0) {
        Watcher.notifyDigest(ctx, newItems, protectedNow, candidates)
    }
    return PassOutcome(newItems, pending, protectedNow, candidates)
}

class WatchWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        // Null for the periodic net and for a manual run; only trigger runs re-arm.
        val slot = inputData.getString(KEY_SLOT)
        val app = applicationContext

        // Hand the work to :scoring so this process never accumulates ML Kit's
        // leak. null means the handoff itself failed, which must not mean the
        // rescue quietly stops happening.
        val ok = when (ScoringClient.runInScoringProcess(app)) {
            true -> true
            false -> false
            null -> {
                Log.w(TAG, "WATCH handoff unavailable, running the pass in-process")
                try { runRescuePass(app); true } catch (e: Exception) {
                    Log.e(TAG, "WATCH failed ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
            }
        }

        // Retry keeps this request, which still carries the slot, so the chain
        // continues from the eventual success. Do not arm on a failure. Bound
        // the retries: a pass that is broken rather than unlucky otherwise
        // respawns :scoring and re-reads the whole library indefinitely.
        if (ok) {
            // Deliberately in this process. Touching WorkManager from :scoring
            // would initialise a second copy of it against the same database.
            val left = try {
                val d = Db(app); val n = d.unscoredCount(); d.close(); n
            } catch (e: Exception) { 0 }
            if (left > 0) {
                Log.i(TAG, "WATCH $left still unscored, draining across passes")
                Watcher.scheduleDrain(app)
            }
        }

        if (!ok) {
            return if (runAttemptCount >= 3) {
                Log.e(TAG, "WATCH pass failed ${runAttemptCount + 1}x, giving up on this wake")
                // Success only in the sense that WorkManager should stop; the
                // trigger is re-armed so a later change gets another chance.
                if (slot != null) Watcher.rearm(app, slot)
                Result.success()
            } else Result.retry()
        }
        if (slot != null) Watcher.rearm(app, slot)
        return Result.success()
    }
}
