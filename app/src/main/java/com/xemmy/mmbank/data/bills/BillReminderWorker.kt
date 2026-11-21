package com.xemmy.mmbank.data.bills

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.domain.account.CardPaymentReminders
import com.xemmy.mmbank.domain.intelligence.BillReminders
import com.xemmy.mmbank.util.BillNotificationHelper
import kotlinx.coroutines.flow.first

/**
 * Opt-in daily check for manually tracked bills due in the next few days.
 *
 * One of MMbank's periodic work items -- alongside
 * [com.xemmy.mmbank.data.subscription.SubscriptionReminderWorker] and
 * [com.xemmy.mmbank.data.retention.TransactionRetentionWorker] -- and it
 * exists behind its own Settings toggle that defaults to off, independent of
 * the subscription reminder toggle. The tick does a single local DB read and
 * touches no network.
 *
 * Fails silently (`Result.success` with no side effect) on every non-fatal
 * condition, matching every other periodic worker in the app -- a missed
 * reminder must never become a retry storm.
 */
class BillReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? MMBankApp)?.container ?: return Result.success()
        if (!container.preferences.billRemindersEnabled) return Result.success()
        // Nothing can be shown without the permission; leave the "sent" ledger
        // untouched so the reminders still fire once it is granted.
        if (!com.xemmy.mmbank.util.NotificationPermission.granted(applicationContext)) return Result.success()

        runCatching {
            val bills = container.billRepository.observeAll().first()
            val now = System.currentTimeMillis()
            val announced = container.preferences.billRemindersSent

            val due = BillReminders.pending(bills, now, announced)
            due.forEach { BillNotificationHelper.postDueReminder(applicationContext, it, now) }

            val updated = announced + due.map { BillReminders.reminderKey(it) }
            container.preferences.billRemindersSent = BillReminders.prune(updated, bills)
        }.onFailure { container.recordError("BillReminderWorker", it) }

        // Credit-card payments ride on the same opt-in: a card statement is
        // a bill the app can work out by itself.
        runCatching {
            val cards = container.cardStatuses()
            val announced = container.preferences.cardRemindersSent
            val due = CardPaymentReminders.pending(cards, announced)
            due.forEach { BillNotificationHelper.postCardDueReminder(applicationContext, it) }
            val updated = announced + due.map(CardPaymentReminders::reminderKey)
            container.preferences.cardRemindersSent = CardPaymentReminders.prune(updated, cards)
        }.onFailure { container.recordError("BillReminderWorker.cards", it) }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "bill_due_reminders"
    }
}
