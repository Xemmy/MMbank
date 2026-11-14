package com.xemmy.mmbank.data.ai

import android.content.Context
import com.xemmy.mmbank.util.LogRedactor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device log of every Gate-2 AI request (see [LoggingLocalAiEngine]),
 * readable from Settings -> AI parsing without adb: a running request
 * count, plus a rolling, newest-first log of each prompt and the response
 * (or error) it got back. Mirrors [com.xemmy.mmbank.diagnostics.Diagnostics]'
 * file + counter shape.
 */
object AiCallLogStore {

    private const val LOG_FILE = "ai_call_log.txt"
    private const val MAX_LOG_BYTES = 60_000
    private const val PREFS_NAME = "mmbank_ai_call_log"
    private const val KEY_CALL_COUNT = "call_count"

    private val stamp: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** Appends one request/response pair and bumps the persistent counter. Safe to call from any thread. */
    fun recordCall(context: Context, prompt: String, result: Result<String>) {
        runCatching {
            val outcome = result.fold(
                onSuccess = { "Response:\n$it" },
                onFailure = { "Error: ${it.message}" },
            )
            // Bank-message prompts and AI responses can both embed raw card /
            // account digits, so both are masked before ever touching a
            // world-readable-in-Settings file.
            val entry = LogRedactor.redactSensitiveData(
                "[$stamp] #${bumpCallCount(context)}\nPrompt:\n$prompt\n$outcome\n\n"
            )
            val file = logFile(context)
            val existing = if (file.exists()) file.readText() else ""
            val combined = (entry + existing).let {
                if (it.length > MAX_LOG_BYTES) it.substring(0, MAX_LOG_BYTES) else it
            }
            file.writeText(combined)
        }
    }

    /** Total number of AI requests ever made on this device (survives log truncation/clearing of the text). */
    fun callCount(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_CALL_COUNT, 0)

    fun readLog(context: Context): String? =
        logFile(context).takeIf { it.exists() }?.readText()?.take(20_000)

    /** Clears the log text but keeps the running counter -- it's a lifetime request count, not a log length. */
    fun clearLog(context: Context) {
        logFile(context).delete()
    }

    private fun bumpCallCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val next = prefs.getInt(KEY_CALL_COUNT, 0) + 1
        prefs.edit().putInt(KEY_CALL_COUNT, next).apply()
        return next
    }

    private fun logFile(context: Context) = File(context.filesDir, LOG_FILE)
}
