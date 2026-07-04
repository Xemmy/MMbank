package com.xemmy.mmbank.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.TransactionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Push-notification capture channel. Every posted notification is normalized
 * to a [CapturedMessage] and handed to the ingest pipeline on an IO
 * dispatcher; non-financial notifications simply fall out as NoMatch, so no
 * package allow-list is needed.
 */
class BankNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Never parse our own notifications, and skip ongoing/group-summary noise.
        if (sbn.packageName == packageName) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val body = if (bigText.length > text.length) bigText else text
        if (body.isBlank()) return

        val message = CapturedMessage(
            source = TransactionSource.PUSH_NOTIFICATION,
            sender = sbn.packageName,
            title = title.takeIf { it.isNotBlank() },
            body = body,
            timestampMillis = sbn.postTime,
        )

        val container = (applicationContext as MMBankApp).container
        scope.launch {
            try {
                when (val result = container.ingestCapturedMessage(message)) {
                    is IngestResult.Inserted ->
                        Log.d(TAG, "Captured tx #${result.transactionId} from ${sbn.packageName}")
                    is IngestResult.Duplicate ->
                        Log.d(TAG, "Deduped against tx #${result.existingTransactionId} (${sbn.packageName})")
                    IngestResult.NoMatch -> Unit
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Ingest failed for notification from ${sbn.packageName}", t)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BankNotifListener"
    }
}
