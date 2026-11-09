package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.util.LogRedactor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure formatting + rolling-buffer logic behind Settings → Diagnostics →
 * "Capture log": one line per captured message saying what happened to it, so
 * a notification/SMS that produced nothing can be explained without adb.
 * File I/O lives in `Diagnostics`; this stays Android-free for unit tests.
 */
object CaptureLogFormatter {

    const val MAX_SNIPPET = 90
    const val MAX_LOG_CHARS = 60_000

    private const val CURRENCY_TOKEN =
        "(?:AED|USD|EUR|GBP|SAR|INR|PKR|QAR|KWD|BHD|OMR|EGP|JOD|د\\.إ|درهم|\\$|€|£)"
    private val currencyBeforeDigits = Regex("$CURRENCY_TOKEN\\s?\\d", RegexOption.IGNORE_CASE)
    private val currencyAfterDigits = Regex("\\d\\s?$CURRENCY_TOKEN(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val standaloneDigitRun = Regex("(?<![\\d.,*])\\d{4,8}(?!\\d|[.,]\\d)")
    private val endsWithCurrency = Regex("$CURRENCY_TOKEN\\s?$", RegexOption.IGNORE_CASE)
    private val startsWithCurrency = Regex("^\\s?$CURRENCY_TOKEN(?![A-Za-z])", RegexOption.IGNORE_CASE)

    /**
     * True when the text pairs a number with a currency, i.e. could be a
     * payment. Used so unrelated notifications (chats, OTPs) that merely
     * contain a digit never reach the capture log.
     */
    fun looksFinancial(text: String): Boolean =
        currencyBeforeDigits.containsMatchIn(text) || currencyAfterDigits.containsMatchIn(text)

    /** Masks 4-8 digit runs (OTPs, PINs) that are not an amount next to a currency. */
    internal fun maskShortCodes(text: String): String =
        standaloneDigitRun.replace(text) { m ->
            val before = text.substring(0, m.range.first)
            val after = text.substring(m.range.last + 1)
            if (endsWithCurrency.containsMatchIn(before) || startsWithCurrency.containsMatchIn(after)) {
                m.value
            } else {
                "*".repeat(m.value.length)
            }
        }

    /** Short outcome label for a result; null means "not worth a line" (no-match that doesn't look financial). */
    fun outcome(result: IngestResult, looksFinancial: Boolean): String? = when (result) {
        is IngestResult.Inserted -> "SAVED tx#${result.transactionId}"
        is IngestResult.Duplicate -> "DUPLICATE of tx#${result.existingTransactionId}"
        IngestResult.Ignored -> "IGNORED (muted template)"
        IngestResult.NoMatch -> if (looksFinancial) "NO MATCH (no rule parsed it)" else null
    }

    fun line(
        channel: String,
        sender: String?,
        title: String?,
        body: String,
        outcome: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(nowMillis))
        val text = listOfNotNull(title?.takeIf { it.isNotBlank() }, body)
            .joinToString(" | ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val snippet = maskShortCodes(LogRedactor.redactSensitiveData(text)).let {
            if (it.length > MAX_SNIPPET) it.take(MAX_SNIPPET) + "…" else it
        }
        return "[$stamp] $channel ${sender.orEmpty()} -> $outcome\n    \"$snippet\"\n"
    }

    /** Newest entry first; the oldest are trimmed at a line boundary once over [maxChars]. */
    fun prepend(existing: String, entry: String, maxChars: Int = MAX_LOG_CHARS): String {
        val combined = entry + existing
        if (combined.length <= maxChars) return combined
        val cut = combined.lastIndexOf("\n[", maxChars)
        return if (cut > 0) combined.substring(0, cut + 1) else combined.take(maxChars)
    }
}
