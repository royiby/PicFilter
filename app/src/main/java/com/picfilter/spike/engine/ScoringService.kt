package com.picfilter.spike.engine

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.google.mlkit.common.MlKit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "PICFILTER"
private const val MSG_RUN = 1
private const val MSG_DONE = 2
private const val MSG_FAILED = 3

/**
 * Runs the scan/score/rescue pass in its own process, and then ends that
 * process.
 *
 * ML Kit leaks native memory per call and we cannot fix that from here: a
 * stage bisect put ~84 MB per 100 images in face detection and ~44 MB in text
 * recognition, against under 1 MB for our own embedding, and closing the
 * detector returns only ~18 MB of ~106. Scoring a library therefore grew the
 * native heap to ~500 MB across four passes in one process and never gave it
 * back, which is how the process came to be killed in the middle of its own
 * run.
 *
 * Since the leak cannot be stopped, it is contained instead: the pass runs in
 * :scoring, and when the pass is over that process exits and the kernel takes
 * everything back. The process the user is actually looking at never
 * accumulates any of it.
 *
 * Note this means picfilter.db is opened from two processes. SQLite coordinates
 * with file locks so that is safe, but a concurrent UI read can still surface
 * as SQLITE_BUSY; the pass is retried by WorkManager if it does.
 */
class ScoringService : Service() {

    private val looperThread = HandlerThread("scoring").apply { start() }
    private val incoming by lazy {
        Messenger(object : Handler(looperThread.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != MSG_RUN) return
                val reply = msg.replyTo
                var what = MSG_FAILED
                try {
                    val r = runRescuePass(applicationContext)
                    Log.i(TAG, "SCORINGSVC pid=${Process.myPid()} done " +
                        "new=${r.newItems} scored=${r.scored} protected=${r.protectedNow}")
                    what = MSG_DONE
                } catch (e: Exception) {
                    Log.e(TAG, "SCORINGSVC failed ${e.javaClass.simpleName}: ${e.message}")
                }
                try { reply?.send(Message.obtain(null, what)) }
                catch (e: android.os.RemoteException) {
                    Log.w(TAG, "SCORINGSVC could not reply: the client is gone")
                }
                stopSelf()
            }
        })
    }

    override fun onCreate() {
        super.onCreate()
        // ML Kit bootstraps itself from a ContentProvider in its own manifest,
        // and providers are only instantiated in the DEFAULT process -- so in
        // :scoring nothing had initialised it and every pass died with
        // "MlKitContext has not been initialized". Secondary processes have to
        // do it by hand.
        try {
            MlKit.initialize(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "SCORINGSVC MlKit.initialize: ${e.javaClass.simpleName}: ${e.message}")
        }
        Log.i(TAG, "SCORINGSVC up in pid=${Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    /**
     * The whole point of the separate process. Killing here rather than after
     * the reply avoids a race: the client has already been answered and has
     * unbound, which is what destroyed this service.
     */
    override fun onDestroy() {
        super.onDestroy()
        looperThread.quitSafely()
        Log.i(TAG, "SCORINGSVC exiting pid=${Process.myPid()} to return its native heap")
        Process.killProcess(Process.myPid())
    }
}

/** Client side of the handoff, used from the WorkManager worker. */
object ScoringClient {

    /**
     * Runs a pass in :scoring and waits for it.
     *
     * Returns true on success, false if the pass itself failed, and null if the
     * handoff could not be made at all — the caller then does the work itself
     * rather than silently not rescuing anything.
     *
     * bindService, not startService: a background app is not allowed to start a
     * service on Android 8+, but it may bind to one, and binding keeps :scoring
     * alive for exactly as long as we are waiting on it.
     */
    fun runInScoringProcess(ctx: Context, timeoutMinutes: Long = 20): Boolean? {
        val connected = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val outcome = AtomicReference<Boolean?>(null)
        val binder = AtomicReference<IBinder?>(null)
        val cbThread = HandlerThread("scoring-reply").apply { start() }

        val replyTo = Messenger(object : Handler(cbThread.looper) {
            override fun handleMessage(msg: Message) {
                outcome.set(msg.what == MSG_DONE)
                finished.countDown()
            }
        })

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                binder.set(b); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                // :scoring exited. If that happened before the reply landed we
                // must not wait out the full timeout for a process that is gone.
                finished.countDown()
            }
            override fun onNullBinding(name: ComponentName?) { connected.countDown() }
        }

        val intent = Intent(ctx, ScoringService::class.java)
        return try {
            if (!ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
                Log.w(TAG, "WATCH bindService refused")
                return null
            }
            if (!connected.await(30, TimeUnit.SECONDS) || binder.get() == null) {
                Log.w(TAG, "WATCH :scoring did not connect")
                return null
            }
            Messenger(binder.get()).send(Message.obtain(null, MSG_RUN).apply { this.replyTo = replyTo })
            if (!finished.await(timeoutMinutes, TimeUnit.MINUTES)) {
                Log.w(TAG, "WATCH :scoring did not finish within ${timeoutMinutes}m")
                return null
            }
            outcome.get()
        } catch (e: Exception) {
            Log.w(TAG, "WATCH handoff ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            // Throws if the binding is already gone, which is the normal
            // outcome when :scoring exited before we unbound.
            try { ctx.unbindService(conn) } catch (e: IllegalArgumentException) { }
            cbThread.quitSafely()
        }
    }
}
