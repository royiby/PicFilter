package com.picfilter.spike

/**
 * Taking the names out of a crash report.
 *
 * This app's stack traces are not anonymous. A message from the review screen
 * can carry a child's name, because the names in this database are family --
 * גבע, אבי, אמא -- and a message from the promoter carries filenames like
 * IMG-20261024-WA0017.jpg, which say that someone uses WhatsApp and when. A
 * crash in the code that handles names is exactly the crash most likely to
 * quote one.
 *
 * So a report is scrubbed before it goes anywhere. The names cannot be a
 * constant -- they are whatever this user enrolled -- so they are passed in.
 *
 * Pure on purpose: this is the part that has to be right, and it is the part
 * that can be tested without a device, a network or a crash.
 */
internal object Scrub {

    /** IMG-20261024-WA0017.jpg and the rest of WhatsApp's naming. */
    private val WA_FILE = Regex("""IMG-\d{8}-WA\d+(_\d+)?\.(jpg|jpeg|png)""", RegexOption.IGNORE_CASE)

    /** Any media id: content://media/external/images/media/42057 */
    private val MEDIA_ID = Regex("""(content://media/[\w/]*?/)(\d+)""")

    /** An absolute path into the app's own storage, which embeds the user id. */
    private val USER_PATH = Regex("""/data/user/\d+/[\w.]+/""")

    /** A crop filename: 41409_17.jpg -- the number is a MediaStore id. */
    private val CROP_FILE = Regex("""\b\d{4,}_\d+\.jpg\b""")

    /**
     * Redact everything that identifies a person or a photo.
     *
     * [names] are the enrolled people, longest first so that a name which
     * contains another ("Dan" inside "Daniel") cannot leave half of it behind.
     * Matching is literal, not a word boundary: Hebrew has no \b that behaves,
     * and a name inside a longer token is still that name.
     */
    fun text(input: String?, names: Collection<String>): String {
        if (input.isNullOrEmpty()) return ""
        var out: String = input
        for (n in names.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            out = out.replace(n, "<name>", ignoreCase = true)
        }
        out = WA_FILE.replace(out, "<photo>")
        out = CROP_FILE.replace(out, "<crop>")
        out = MEDIA_ID.replace(out) { "${it.groupValues[1]}<id>" }
        out = USER_PATH.replace(out, "/data/user/<n>/<pkg>/")
        return out
    }

    /**
     * A throwable rebuilt with its messages scrubbed and its stack kept.
     *
     * The stack frames are class and method names, which carry nothing about
     * the user; the messages are where the names get in. Causes are walked so
     * a scrubbed outer exception cannot be undone by a raw inner one -- which
     * is how most of this app's messages reach a log, since the catch blocks
     * record `e.message`.
     */
    fun throwable(t: Throwable?, names: Collection<String>): Throwable? {
        if (t == null) return null
        val copy = RuntimeException(
            "${t.javaClass.name}: ${text(t.message, names)}",
            throwable(t.cause, names),
        )
        copy.stackTrace = t.stackTrace
        return copy
    }
}

/**
 * Catching a crash, taking the names out of it, and keeping it until the user
 * says what to do with it.
 *
 * Why the app owns the handler instead of letting a reporter install its own:
 * Crashlytics captures the raw throwable, and there is no hook that runs in
 * between. Scrubbing is only a promise you can keep if nothing else sees the
 * original -- so the report is built here, from [Scrub], and a reporter is a
 * transport for the sanitised copy rather than a collector in its own right.
 *
 * Nothing is sent. A report is written to the app's own storage and waits to
 * be asked about, because the screen the user agreed to says nothing leaves
 * the phone and that has to stay true until they say otherwise.
 */
object CrashReports {

    private const val DIR = "crash"
    private const val TAG = "PICFILTER"

    /**
     * Enrolled names, remembered across process death.
     *
     * Not read from the database at crash time: the database may be what just
     * failed, and a handler that throws replaces a useful report with none at
     * all. But an in-memory cache alone is not enough, and this was caught by
     * testing it rather than by reasoning about it -- a deliberate crash fired
     * before the home screen's first read finished, the cache was still empty,
     * and the report went out with a child's name in it while the filename and
     * the media id beside it were correctly redacted.
     *
     * **The crashes most likely to beat that read are the early ones**, which
     * are also the ones most worth reporting -- last week's first-run crash
     * happened in a ViewModel constructor. So the names are written to a
     * preferences file whenever a screen reads them, and the handler reads
     * that: no SQLite, and correct from the second launch onwards. Before any
     * enrolment there are no names to leak.
     */
    private const val PREFS = "crash_scrub"
    private const val KEY_NAMES = "names"

    @Volatile private var names: List<String>? = null

    fun rememberNames(ctx: android.content.Context, n: Collection<String>) {
        val clean = n.filter { it.isNotBlank() }
        names = clean
        try {
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_NAMES, clean.toSet()).apply()
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "CRASH names not persisted: ${e.javaClass.simpleName}")
        }
    }

    private fun namesFor(ctx: android.content.Context): List<String> =
        names ?: try {
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getStringSet(KEY_NAMES, emptySet())!!.toList().also { names = it }
        } catch (e: Throwable) {
            emptyList()
        }

    fun install(ctx: android.content.Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            try { write(ctx, t) } catch (e: Throwable) {
                android.util.Log.w(TAG, "CRASH report failed: ${e.javaClass.simpleName}")
            }
            // Always hand on: the process still has to die the way the system
            // expects, and swallowing it would turn a crash into a freeze.
            prev?.uncaughtException(thread, t)
        }
    }

    private fun dir(ctx: android.content.Context) =
        java.io.File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun write(ctx: android.content.Context, t: Throwable) {
        val safe = Scrub.throwable(t, namesFor(ctx)) ?: return
        val sw = java.io.StringWriter()
        safe.printStackTrace(java.io.PrintWriter(sw))
        val body = buildString {
            append("PixKin ").append(BuildConfig.VERSION_NAME)
            append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("android ").append(android.os.Build.VERSION.SDK_INT).append('\n')
            // The device model is the one identifier worth keeping: half the
            // bugs in this project were specific to one manufacturer's
            // framework, and it says nothing about who owns the phone.
            append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
            append("\n\n").append(sw.toString())
        }
        java.io.File(dir(ctx), "${System.currentTimeMillis()}.txt").writeText(body)
        android.util.Log.w(TAG, "CRASH report written, scrubbed")
    }

    /** Reports waiting to be asked about, newest first. */
    fun pending(ctx: android.content.Context): List<java.io.File> =
        dir(ctx).listFiles()?.sortedByDescending { it.name } ?: emptyList()

    fun clear(ctx: android.content.Context) {
        pending(ctx).forEach { it.delete() }
    }

    /**
     * Hand the waiting reports to whatever reporter this build has, if any.
     *
     * Reflection because the Crashlytics dependency is only present when a
     * google-services.json is -- a build without one carries no Google code at
     * all, which is the only way "nothing leaves this phone" is true by
     * construction rather than by configuration. Returns what it managed to
     * send. (If R8 is ever turned on this needs a keep rule.)
     */
    fun send(ctx: android.content.Context): Int {
        val reports = pending(ctx)
        if (reports.isEmpty()) return 0
        val crashlytics = try {
            val c = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
            c.getMethod("getInstance").invoke(null) to c
        } catch (e: Throwable) {
            android.util.Log.i(TAG, "CRASH no reporter in this build; ${reports.size} kept locally")
            return 0
        }
        val (instance, klass) = crashlytics
        var sent = 0
        for (f in reports) {
            try {
                klass.getMethod("log", String::class.java).invoke(instance, f.readText())
                sent++
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "CRASH send failed: ${e.javaClass.simpleName}")
            }
        }
        if (sent > 0) clear(ctx)
        android.util.Log.i(TAG, "CRASH sent=$sent")
        return sent
    }
}
