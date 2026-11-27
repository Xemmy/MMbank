package com.xemmy.mmbank.data.digest

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.domain.intelligence.WhatChangedDigest
import com.xemmy.mmbank.util.WeeklyDigestNotificationHelper
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * Opt-in weekly "what changed" notification (see [WhatChangedDigest]). One of
 * MMbank's periodic work items, behind its own Settings toggle that defaults
 * to off; a single local DB read, no network. Sends at most once per ISO
 * week ([shouldSend]), so a worker re-run never repeats itself. Fails
 * silently like every other periodic worker here.
 */
class WeeklyDigestWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? MMBankApp)?.container ?: return Result.success()
        val prefs = container.preferences
        val today = LocalDate.now()
        val key = weekKey(today)
        if (!shouldSend(prefs.weeklyDigestNotificationsEnabled, prefs.weeklyDigestLastSentKey, key)) return Result.success()

        // Don't mark the week as sent when nothing can be shown.
        if (!com.xemmy.mmbank.util.NotificationPermission.granted(applicationContext)) return Result.success()

        runCatching {
            val digest = container.buildWeeklyDigest() ?: return@runCatching
            WeeklyDigestNotificationHelper.post(applicationContext, digest, prefs.defaultCurrency)
            prefs.weeklyDigestLastSentKey = key
        }.onFailure { container.recordError("WeeklyDigestWorker", it) }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "weekly_digest"

        /** "2026-W45": the ISO week [day] falls in. */
        fun weekKey(day: LocalDate): String =
            "%04d-W%02d".format(java.util.Locale.US, day.get(IsoFields.WEEK_BASED_YEAR), day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

        /** Opted in, and nothing sent yet this week. */
        fun shouldSend(enabled: Boolean, lastSentKey: String, thisWeekKey: String): Boolean =
            enabled && lastSentKey != thisWeekKey
    }
}
