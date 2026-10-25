package com.picfilter.spike

import android.app.Application
import android.content.Context
import com.picfilter.spike.data.Db

/**
 * What a screen needs and should not build for itself.
 *
 * Every ViewModel used to call `Db(app)` in its own constructor, which reads
 * as harmless and is the reason this app had no UI tests at all: a screen that
 * opens the real database cannot be exercised without the user's real photo
 * library in front of it, and the one rule this project will not break is
 * never to drive the review screen against someone's family. So the screens
 * went untested, and every bug found in them was found by looking.
 *
 * One seam fixes that. A test swaps [PixKinApp.deps] for a container that
 * hands out a Db with its own name, launches the Activity, and asserts on
 * rows -- with nothing of the user's within reach.
 *
 * Deliberately a hand-written container rather than Hilt. It is the shape
 * Google's own manual-dependency-injection guidance describes, the app is one
 * module with one thing worth injecting, and the project already avoids
 * annotation processing elsewhere (raw SQLite rather than Room). Hilt would
 * buy generated factories for a single dependency and cost a build plugin, a
 * custom test runner and a second way to be wrong in a `:scoring` process that
 * is deliberately not the main one.
 */
interface Deps {
    /** A new handle. The caller owns it and closes it. */
    fun openDb(): Db
}

/** The real one: the app's own database, under its own name. */
class AppDeps(private val ctx: Context) : Deps {
    override fun openDb(): Db = Db(ctx)
}

/**
 * The container for this process, or the real one if nothing replaced it.
 *
 * `var` on purpose: an instrumented test sets it before the Activity starts.
 * Nothing in the app writes to it.
 */
internal fun Application.deps(): Deps =
    (this as? PixKinApp)?.deps ?: AppDeps(this)
