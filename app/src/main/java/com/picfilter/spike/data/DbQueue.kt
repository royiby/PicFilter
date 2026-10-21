package com.picfilter.spike.data

import android.util.Log
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private const val TAG = "PICFILTER"

/**
 * One thread that owns one screen's [Db] handle, for as long as that screen
 * lasts.
 *
 * Two problems, one answer.
 *
 * **Ordering.** A verdict and the undo that follows it are two writes that must
 * land in the order they were tapped. Dispatchers.IO is a pool, so they can
 * race, and the loser leaves the row that was just undone sitting in the table.
 *
 * **Closing.** A screen has to give its database handle back when it goes away,
 * and anything still reading when it does throws "attempt to re-open an
 * already-closed object". Waiting for the readers on the main thread would put
 * a disk wait in every rotation, which is the thing being fixed. So the close
 * is simply the last task in the queue: everything already asked for runs, the
 * handle shuts behind it, and nothing blocks a frame.
 *
 * Every database touch from a screen -- read or write -- goes through here, so
 * "last" means what it says.
 */
class DbQueue(private val db: Db) {

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "pixkin-db") }

    /** For `withContext(queue.context) { ... }` reads. */
    val context: ExecutorCoroutineDispatcher = exec.asCoroutineDispatcher()

    /**
     * A write that must not be cancelled.
     *
     * Deliberately not a coroutine on a lifecycle scope: a judgement about
     * someone's photo has to survive the screen rotating a frame after the tap.
     */
    fun submit(block: () -> Unit) {
        try {
            exec.execute {
                try { block() } catch (e: Exception) {
                    Log.w(TAG, "DB write failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } catch (e: RejectedExecutionException) {
            // The screen is already closing and its handle is going with it.
            Log.w(TAG, "DB work dropped: queue is shut")
        }
    }

    /** Queue the close behind everything already asked for, then stop taking work. */
    fun closeAfterPending() {
        submit { db.close() }
        exec.shutdown()
    }
}
