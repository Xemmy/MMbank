package com.xemmy.mmbank

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
        // Re-arms the opt-in subscription reminder if it's on. Idempotent
        // (unique work, KEEP), and a no-op entirely while the toggle is off.
        container.ensureSubscriptionReminderWork()
        // Same idempotent re-arm for the opt-in transaction-retention prune.
        container.ensureTransactionRetentionWork()
        // Same idempotent re-arm for the opt-in bill due-date reminder.
        container.ensureBillReminderWork()
        // Whole-app foreground/background transitions drive the opt-in app
        // lock (ProcessLifecycleOwner, unlike Activity onStop, doesn't fire
        // on rotation or intra-app navigation).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.appLock.onAppForegrounded()
            override fun onStop(owner: LifecycleOwner) = container.appLock.onAppBackgrounded()
        })
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
