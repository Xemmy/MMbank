package com.xemmy.mmbank

import android.app.Application
import android.util.Log
import com.xemmy.mmbank.di.AppContainer
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MMBankApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
        container = AppContainer(this)
    }

    /**
     * Records any crash's stack trace to a file before the process dies, so it
     * can be reviewed under Settings → Diagnostics after the app restarts.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                crashLogFile(this).writeText(
                    "Crash at $stamp on thread ${thread.name}\n\n$sw"
                )
            } catch (t: Throwable) {
                Log.e("MMBankApp", "Failed to record crash", t)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun crashLogFile(app: Application): File = File(app.filesDir, "last_crash.txt")
    }
}
