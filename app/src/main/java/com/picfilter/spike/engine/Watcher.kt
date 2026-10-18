package com.picfilter.spike.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.picfilter.spike.MainActivity
import com.picfilter.spike.data.Db
import java.util.concurrent.TimeUnit

private const val TAG = "PICFILTER"
private const val WORK = "picfilter-watch"
private const val CHANNEL = "picfilter"

/**
 * Phase 3: keep the rescue running without anyone asking.
 *
 * New WhatsApp arrivals are scored and keepers are copied into the backed-up
 * folder automatically. It NEVER discards: copying is additive and reversible,
 * deleting is not, so discard candidates are only ever reported.
 */
object Watcher {

    fun enable(ctx: Context, hours: Long = 2) {
        val req = PeriodicWorkRequestBuilder<WatchWorker>(hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        Log.i(TAG, "WATCH enabled, every ${hours}h (battery-not-low)")
    }

    fun disable(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        Log.i(TAG, "WATCH disabled")
    }

    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<WatchWorker>().build())
        Log.i(TAG, "WATCH one-off run enqueued")
    }

    fun isEnabled(ctx: Context): Boolean = try {
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(WORK).get()
            .any { !it.state.isFinished }
    } catch (e: Exception) { false }

    fun notifyDigest(ctx: Context, newItems: Int, protectedNow: Int, candidates: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "PicFilter", NotificationManager.IMPORTANCE_LOW)
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
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("PicFilter")
                .setContentText(text)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
        )
    }
}

class WatchWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result = try {
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
            Result.success()
        } else {
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
            Result.success()
        }
    } catch (e: Exception) {
        Log.e(TAG, "WATCH failed ${e.javaClass.simpleName}: ${e.message}")
        Result.retry()
    }
}
