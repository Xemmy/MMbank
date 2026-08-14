package com.xemmy.mmbank.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.xemmy.mmbank.R
import com.xemmy.mmbank.domain.intelligence.SubscriptionReminders
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.ui.util.asMoney

/**
 * Posts the "this subscription renews soon" local notification for the opt-in
 * reminder worker (see
 * [com.xemmy.mmbank.data.subscription.SubscriptionReminderWorker]).
 *
 * Fails silently on every non-fatal condition, the same stance as
 * [BudgetNotificationHelper] and [CategoryBudgetNotificationHelper].
 */
object SubscriptionNotificationHelper {
    private const val CHANNEL_ID = "subscription_renewals"

    /** Offset clear of the budget families (9001 / 9100+), keyed per subscription. */
    private const val NOTIFICATION_ID_BASE = 9200

    fun postRenewalReminder(context: Context, subscription: Subscription, nowMillis: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(context)

        val days = SubscriptionReminders.daysUntil(subscription, nowMillis)
        val whenText = when {
            days < 0L -> "was due ${-days}d ago"
            days == 0L -> "renews today"
            days == 1L -> "renews tomorrow"
            else -> "renews in ${days}d"
        }
        val amount = subscription.averageAmount.asMoney(subscription.currencyCode)
        val text = buildString {
            append("${subscription.merchant} $whenText — $amount.")
            subscription.previousAmount?.let { previous ->
                append(" Price changed from ${previous.asMoney(subscription.currencyCode)}.")
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${subscription.merchant} $whenText")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_BASE + subscription.id.toInt(), notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Subscription renewals", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders shortly before a detected subscription renews."
            }
        )
    }
}
