package com.xemmy.mmbank.data.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.TransactionSource

/**
 * Expedited Gate 2 worker: carries one unmatched-but-money-looking message to
 * the AiFallbackPipeline off the capture path, surviving process death between
 * capture and inference (model load can take seconds). Always reports success —
 * a missed learning opportunity is never worth WorkManager retry churn, and
 * the transaction pipeline itself never depends on this worker.
 */
class AiParsingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val message = messageFromData(inputData) ?: return Result.success()
        val container = (applicationContext as MMBankApp).container
        try {
            container.aiFallbackPipeline.run(message)
        } catch (t: Throwable) {
            container.recordError("AiParsingWorker", t)
        }
        return Result.success()
    }

    // Expedited work runs as a short foreground service below API 31; the
    // notification is mandatory plumbing — minimal, silent, MIN importance.
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transaction parsing", NotificationManager.IMPORTANCE_MIN)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Learning a new transaction format")
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "ai_parsing"
        private const val NOTIFICATION_ID = 0xA1
        private const val KEY_SOURCE = "source"
        private const val KEY_SENDER = "sender"
        private const val KEY_TITLE = "title"
        private const val KEY_BODY = "body"
        private const val KEY_TIMESTAMP = "timestamp"

        fun enqueue(context: Context, message: CapturedMessage) {
            val request = OneTimeWorkRequestBuilder<AiParsingWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    Data.Builder()
                        .putString(KEY_SOURCE, message.source.name)
                        .putString(KEY_SENDER, message.sender)
                        .putString(KEY_TITLE, message.title)
                        .putString(KEY_BODY, message.body)
                        .putLong(KEY_TIMESTAMP, message.timestampMillis)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun messageFromData(data: Data): CapturedMessage? {
            val body = data.getString(KEY_BODY)?.takeIf { it.isNotBlank() } ?: return null
            val source = data.getString(KEY_SOURCE)
                ?.let { name -> TransactionSource.entries.firstOrNull { it.name == name } }
                ?: return null
            return CapturedMessage(
                source = source,
                sender = data.getString(KEY_SENDER),
                title = data.getString(KEY_TITLE),
                body = body,
                timestampMillis = data.getLong(KEY_TIMESTAMP, 0L),
            )
        }
    }
}
