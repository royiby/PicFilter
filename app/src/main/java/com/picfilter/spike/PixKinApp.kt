package com.picfilter.spike

import android.app.Application
import android.os.StrictMode
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One thread that lives as long as the process, for work that must finish even
 * though the screen that started it is gone.
 *
 * Un-promoting a photo is the case: the user said "not mine", which deletes the
 * copy PixKin made and drops its record. Half of that leaves a file in the
 * backed-up folder with nothing recording that it is there, and the next pass
 * will not copy it again because the folder is the authority. So it cannot be
 * cancelled when the review screen closes -- which is exactly what
 * lifecycleScope would do.
 *
 * Single-threaded, so these stay in the order they were asked for.
 */
object Background {
    val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pixkin-bg")
    }
}

/**
 * Exists for one reason: to make main-thread disk work fail loudly in debug.
 *
 * The home screen opened the database and ran eight queries inside onCreate and
 * again on every onResume, and the review screen loaded its whole queue the
 * same way. Nothing ever appeared to be wrong -- the database is small and the
 * phone is fast -- so the only way this class of bug announces itself is if
 * something is watching for it.
 *
 * Logged rather than fatal. A death penalty would take the app down on a
 * violation inside a library we do not control, and the point is to be told,
 * not to be punished.
 */
class PixKinApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build()
        )
        Log.i("PICFILTER", "STRICTMODE armed (debug build)")
    }
}
