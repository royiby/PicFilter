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

    fun disable(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(NET)
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

class WatchWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        // Null for the periodic net and for a manual run; only trigger runs re-arm.
        val slot = inputData.getString(KEY_SLOT)
        val result = try {
            scanScorePromote()
        } catch (e: Exception) {
            Log.e(TAG, "WATCH failed ${e.javaClass.simpleName}: ${e.message}")
            // Retry keeps this request, which still carries the slot, so the
            // chain continues from the eventual success. Do not arm here.
            return Result.retry()
        }
        if (slot != null) Watcher.rearm(applicationContext, slot)
        return result
    }

    private fun scanScorePromote(): Result {
        val app = applicationContext
        val db0 = Db(app)
        val before = db0.itemCount()
        val promotedBefore = db0.promotionCount()
        db0.close()

        Pipeline.scanMedia(app, 5000)

        val db1 = Db(app)
        val newItems = db1.itemCount() - before
        val pending = db1.unscoredCount()
        db1.close()

        if (pending == 0) {
            Log.i(TAG, "WATCH nothing new")
            return Result.success()
        }

        Pipeline.score(app, 5000)
        // Auto-promote keepers only. Auto-discard stays off by construction:
        // the promoter refuses DISCARD_CANDIDATE and nothing here trashes.
        Promoter.run(app, dryRun = false, decisions = listOf("KEEP"))

        val db2 = Db(app)
        val protectedNow = db2.promotionCount() - promotedBefore
        val candidates = db2.decisionCounts()
            .firstOrNull { it.first == "DISCARD_CANDIDATE" }?.second ?: 0
        db2.close()

        Log.i(TAG, "WATCH new=$newItems scored=$pending protected=$protectedNow")
        if (newItems > 0 || protectedNow > 0) {
            Watcher.notifyDigest(app, newItems, protectedNow, candidates)
        }
        return Result.success()
    }
}
