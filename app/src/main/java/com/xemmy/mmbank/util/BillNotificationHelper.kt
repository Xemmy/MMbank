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
import com.xemmy.mmbank.domain.account.CardPaymentReminders
import com.xemmy.mmbank.domain.intelligence.BillReminders
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.ui.util.asMoney

/**
 * Posts the "this bill is due soon" local notification for the opt-in
 * reminder worker (see [com.xemmy.mmbank.data.bills.BillReminderWorker]).
 *
 * Fails silently on every non-fatal condition, the same stance as
 * [SubscriptionNotificationHelper].
 */
object BillNotificationHelper {
    private const val CHANNEL_ID = "bill_due_dates"

    /** Offset clear of the budget (9001 / 9100+) and subscription (9200+) families, keyed per bill. */
    private const val NOTIFICATION_ID_BASE = 9300

    fun postDueReminder(context: Context, bill: Bill, nowMillis: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(context)

        val days = BillReminders.daysUntil(bill, nowMillis)
        val whenText = when {
            days < 0L -> "was due ${-days}d ago"
            days == 0L -> "due today"
            days == 1L -> "due tomorrow"
            else -> "due in ${days}d"
        }
        val amount = bill.amount.asMoney(bill.currencyCode)
        val text = "${bill.name} $whenText — $amount."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${bill.name} $whenText")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_BASE + bill.id.toInt(), notification)
        }
    }

    /** Offset for credit-card payment reminders, keyed per card account. */
    private const val CARD_NOTIFICATION_ID_BASE = 9400

    /** "ADCB Card due in 3d — AED 1,240.00 left to pay." */
    fun postCardDueReminder(context: Context, reminder: CardPaymentReminders.CardReminder) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val days = reminder.status.daysUntilDue ?: return
        ensureChannel(context)

        val whenText = CardPaymentReminders.whenText(days)
        val amount = reminder.status.amountDue.asMoney(reminder.currencyCode)
        val text = "${reminder.accountName} payment $whenText — $amount left to pay."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${reminder.accountName} payment $whenText")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(CARD_NOTIFICATION_ID_BASE + reminder.accountId.toInt(), notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bill due dates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders shortly before a bill or credit-card payment is due."
            }
        )
    }
}
