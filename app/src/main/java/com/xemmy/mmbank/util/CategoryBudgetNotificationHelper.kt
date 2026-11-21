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
import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator
import com.xemmy.mmbank.ui.util.asMoney

/**
 * Posts the "this category is near / over its monthly cap" local notification
 * (see [com.xemmy.mmbank.di.AppContainer]'s post-insert category-budget check).
 *
 * Fails silently -- no permission, no channel support, whatever -- the same
 * stance as [BudgetNotificationHelper] and every other background-path
 * integration here: a missed notification must never crash or block the
 * capture pipeline.
 */
object CategoryBudgetNotificationHelper {
    private const val CHANNEL_ID = "category_budget"

    /**
     * Offset from [BudgetNotificationHelper]'s 9001 so the two alert families
     * never overwrite each other, then keyed per category so two categories
     * blowing their caps show as two notifications rather than one replacing
     * the other.
     */
    private const val NOTIFICATION_ID_BASE = 9100

    fun postCapAlert(
        context: Context,
        status: CategoryBudgetCalculator.CategoryBudgetStatus,
        currencyCode: String,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(context)

        val name = "${status.category.icon} ${status.category.name}".trim()
        val title = when (status.state) {
            CategoryBudgetCalculator.BudgetState.OVER -> "Over budget: ${status.category.name}"
            else -> "Nearly out: ${status.category.name}"
        }
        val text = when (status.state) {
            CategoryBudgetCalculator.BudgetState.OVER ->
                "$name has spent ${status.spent.asMoney(currencyCode)} of its " +
                    "${status.cap.asMoney(currencyCode)} monthly cap."
            else ->
                "$name is at ${(status.fraction * 100).toInt()}% of its " +
                    "${status.cap.asMoney(currencyCode)} cap — " +
                    "${status.remaining.asMoney(currencyCode)} left this month."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_BASE + status.category.id.toInt(), notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Category budgets", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when a spending category nears or passes its monthly cap."
            }
        )
    }
}
