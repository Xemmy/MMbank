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
import com.xemmy.mmbank.domain.budget.DailyBudgetPacing
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.ui.util.asMoney

/**
 * Posts the "you're over today's budget pace" local notification (see
 * [com.xemmy.mmbank.di.AppContainer]'s post-insert pacing check). Fails
 * silently -- no permission, no channel support, whatever -- same stance as
 * every other background-path integration in this app: a missed notification
 * must never crash or block the capture pipeline.
 */
object BudgetNotificationHelper {
    private const val CHANNEL_ID = "daily_budget_pacing"
    private const val NOTIFICATION_ID = 9001

    fun postOverspendAlert(
        context: Context,
        transaction: Transaction,
        overspend: DailyBudgetPacing.PacingResult.Overspend,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(context)

        val merchant = transaction.merchant ?: "this purchase"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Over your daily budget")
            .setContentText(
                "$merchant put you ${overspend.overspendAmount.asMoney(transaction.currencyCode)} over " +
                    "today's ${overspend.dailyAllowance.asMoney(transaction.currencyCode)} allowance."
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Daily budget pacing", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when a purchase pushes you over today's daily budget allowance."
            }
        )
    }
}
