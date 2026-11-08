package com.xemmy.mmbank.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.diagnostics.Diagnostics
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.parser.CaptureLogFormatter
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.util.LogRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SMS capture channel. Multipart messages are re-assembled into one body
 * before parsing. Work is handed off with goAsync() so the ~10s broadcast
 * budget covers the Room round-trip.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (body.isBlank()) return
        val sender = parts.first().displayOriginatingAddress
        val timestamp = parts.first().timestampMillis

        val message = CapturedMessage(
            source = TransactionSource.SMS,
            sender = sender,
            title = null,
            body = body,
            timestampMillis = timestamp,
        )

        val container = (context.applicationContext as MMBankApp).container
        // Any SMS at all proves the receiver still fires (capture health).
        runCatching { container.preferences.captureLastSmsAt = System.currentTimeMillis() }
        val pendingResult = goAsync()
        scope.launch {
            try {
                val result = container.ingestCapturedMessage(message)
                val hasDigit = body.any { it.isDigit() }
                CaptureLogFormatter.outcome(result, hasDigit)?.let {
                    Diagnostics.recordCapture(context, CaptureLogFormatter.line("SMS", sender, null, body, it))
                }
            } catch (t: Throwable) {
                Diagnostics.recordCapture(
                    context,
                    CaptureLogFormatter.line("SMS", sender, null, body, "ERROR ${t.javaClass.simpleName}"),
                )
                Diagnostics.recordError(context, "SmsReceiver.ingest", t)
                Log.w(TAG, LogRedactor.redactSensitiveData("Ingest failed for SMS from $sender"), t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsReceiver"

        // Manifest receivers are re-instantiated per broadcast; sharing one
        // scope avoids allocating a fresh SupervisorJob for every SMS.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
