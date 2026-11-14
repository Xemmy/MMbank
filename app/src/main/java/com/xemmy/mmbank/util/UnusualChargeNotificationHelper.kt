package com.xemmy.mmbank.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.xemmy.mmbank.R
import com.xemmy.mmbank.domain.intelligence.SpendAnomalyDetector
import com.xemmy.mmbank.ui.MainActivity
import com.xemmy.mmbank.ui.util.asMoney
import java.util.Locale

/**
 * Posts the opt-in "unusual charge" notification (see
 * [com.xemmy.mmbank.di.AppContainer]'s post-insert check and
 * [SpendAnomalyDetector]). Fails silently on every error, like the other
 * alert helpers: a missed notification must never touch the capture pipeline.
 */
object UnusualChargeNotificationHelper {
    private const val CHANNEL_ID = "unusual_charge"

    /** After the bill (9300+) and card (9400+) families; keyed per transaction. */
    private const val NOTIFICATION_ID_BASE = 9500

    fun post(context: Context, anomaly: SpendAnomalyDetector.Anomaly) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)

        val ratio = String.format(Locale.US, "%.1f", anomaly.ratio)
        val title = "Unusual charge at ${anomaly.merchant}"
        val text = "${anomaly.amount.asMoney(anomaly.currencyCode)} is ${ratio}× what you usually pay there " +
            "(about ${anomaly.usual.asMoney(anomaly.currencyCode)})."

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_BASE + (anomaly.transaction.id % 1000).toInt(), notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Unusual charges", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when a new charge is far above what that merchant usually costs you."
            }
        )
    }
}
