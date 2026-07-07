package com.xemmy.mmbank

import android.app.Application
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.diagnostics.Diagnostics

class MMBankApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
        container = AppContainer(this)
        container.seedDefaultRulesOnce()
    }

    /**
     * Records any crash's stack trace to a file before the process dies, so it
     * can be reviewed under Settings → Diagnostics after the app restarts.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Diagnostics.recordCrash(this, thread.name, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
