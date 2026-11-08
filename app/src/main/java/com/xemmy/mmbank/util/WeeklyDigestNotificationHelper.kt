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
import com.xemmy.mmbank.domain.intelligence.WhatChangedDigest
import com.xemmy.mmbank.domain.intelligence.WhatChangedWording
import com.xemmy.mmbank.ui.MainActivity
import com.xemmy.mmbank.ui.util.asMoney

/**
 * Posts the weekly "what changed" notification (see
 * [com.xemmy.mmbank.data.digest.WeeklyDigestWorker]). Fails silently on every
 * error, like the other alert helpers.
 */
object WeeklyDigestNotificationHelper {
    private const val CHANNEL_ID = "weekly_digest"
    private const val NOTIFICATION_ID = 9600

    fun post(context: Context, digest: WhatChangedDigest.Digest, currencyCode: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)

        val wording = WhatChangedWording(
            money = { it.asMoney(currencyCode) },
            moneyIn = { amount, code -> amount.asMoney(code) },
        )
        val headline = wording.headline(digest)
        val body = (listOf(headline) + digest.items.map { "• " + wording.item(it) }).joinToString("\n")

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Your week in money")
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Weekly digest", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Once a week: what you spent and what changed."
            }
        )
    }
}
