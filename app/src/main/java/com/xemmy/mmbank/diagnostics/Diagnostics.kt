package com.xemmy.mmbank.diagnostics

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device crash/error log, readable from Settings → Diagnostics without adb.
 * Two files:
 *  - [CRASH_FILE]: the single most recent uncaught (fatal) exception.
 *  - [ERRORS_FILE]: a rolling log of caught (non-fatal) exceptions from
 *    ViewModel data pipelines — these are turned into a safe empty/error UI
 *    state instead of crashing, but are still recorded so the root cause can
 *    be diagnosed and reported.
 */
object Diagnostics {

    private const val CRASH_FILE = "last_crash.txt"
    private const val ERRORS_FILE = "recent_errors.txt"
    private const val MAX_ERRORS_BYTES = 40_000
    private const val PREFS_NAME = "mmbank_diagnostics"
    private const val KEY_CRASH_STREAK = "crash_streak"

    private val stamp: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    fun recordCrash(context: Context, threadName: String, throwable: Throwable) {
        runCatching {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            crashFile(context).writeText("Crash at $stamp on thread $threadName\n\n$sw")
        }
        runCatching { bumpCrashStreak(context) }
    }

    /**
     * Tracks consecutive crashes so the app can break a crash loop: if it just
     * crashed, the next launch skips Home (whose data pipeline or rendering
     * caused the crash) and opens Settings instead, so the user is never
     * permanently locked out. Uses commit() (synchronous) since this runs in
     * the uncaught-exception handler, moments before the process dies.
     */
    private fun bumpCrashStreak(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CRASH_STREAK, prefs.getInt(KEY_CRASH_STREAK, 0) + 1).commit()
    }

    fun crashStreak(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_CRASH_STREAK, 0)

    /** Call once a launch has stayed up for a few seconds — a genuinely successful run. */
    fun clearCrashStreak(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_CRASH_STREAK, 0).apply()
    }

    /** Records a non-fatal exception caught in place of a crash; safe to call from any thread. */
    fun recordError(context: Context, tag: String, throwable: Throwable) {
        runCatching {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val entry = "[$stamp] $tag\n$sw\n"
            val file = errorsFile(context)
            val existing = if (file.exists()) file.readText() else ""
            val combined = (entry + existing).let {
                if (it.length > MAX_ERRORS_BYTES) it.substring(0, MAX_ERRORS_BYTES) else it
            }
            file.writeText(combined)
        }
    }

    fun readCrash(context: Context): String? =
        crashFile(context).takeIf { it.exists() }?.readText()?.take(6000)

    fun readErrors(context: Context): String? =
        errorsFile(context).takeIf { it.exists() }?.readText()?.take(6000)

    fun clearCrash(context: Context) {
        crashFile(context).delete()
    }

    fun clearErrors(context: Context) {
        errorsFile(context).delete()
    }

    private fun crashFile(context: Context) = File(context.filesDir, CRASH_FILE)
    private fun errorsFile(context: Context) = File(context.filesDir, ERRORS_FILE)
}
