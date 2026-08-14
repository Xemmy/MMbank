package com.xemmy.mmbank.data.subscription

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.domain.intelligence.SubscriptionReminders
import com.xemmy.mmbank.util.SubscriptionNotificationHelper
import kotlinx.coroutines.flow.first

/**
 * Opt-in daily check for subscriptions renewing in the next few days.
 *
 * This is the app's **only** periodic work, and it exists behind a Settings
 * toggle that defaults to off. The README sells "no background polling" as a
 * battery property, so this deliberately keeps that promise intact: the tick
 * does a single local DB read and touches no network, and nothing is scheduled
 * at all until the user asks for reminders.
 *
 * Fails silently (`Result.success` with no side effect) on every non-fatal
 * condition, like [com.xemmy.mmbank.data.ai.AiParsingWorker] -- a missed
 * reminder must never become a retry storm.
 */
class SubscriptionReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? MMBankApp)?.container ?: return Result.success()
        if (!container.preferences.subscriptionRemindersEnabled) return Result.success()

        runCatching {
            val subscriptions = container.subscriptionRepository.observeActive().first()
            val now = System.currentTimeMillis()
            val announced = container.preferences.subscriptionRemindersSent

            val due = SubscriptionReminders.pending(subscriptions, now, announced)
            due.forEach { SubscriptionNotificationHelper.postRenewalReminder(applicationContext, it, now) }

            // Record what was announced, then drop keys whose renewal is gone,
            // so the set tracks live subscriptions instead of growing forever.
            val updated = announced + due.map { SubscriptionReminders.reminderKey(it) }
            container.preferences.subscriptionRemindersSent =
                SubscriptionReminders.prune(updated, subscriptions)
        }.onFailure { container.recordError("SubscriptionReminderWorker", it) }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "subscription_renewal_reminders"
    }
}
