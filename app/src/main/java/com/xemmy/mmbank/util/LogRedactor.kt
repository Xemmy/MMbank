package com.xemmy.mmbank.util

/**
 * Masks sensitive numeric sequences (card, bank account, phone and contract numbers)
 * before a string is written to logs/diagnostics. Pure Kotlin, no Android
 * dependency, so it can run anywhere a log line is about to be emitted.
 */
object LogRedactor {

    /** 16 digits, optionally grouped in 4s by a single space or dash. */
    private val cardNumberPattern = Regex(
        """\b(\d{4})[ -]?(\d{4})[ -]?(\d{4})[ -]?(\d{4})\b"""
    )

    /** Any other run of 13+ digits not already masked as a card number above. */
    private val longAccountNumberPattern = Regex("""\b\d{13,}\b""")

    /**
     * A 9-12 digit run: a phone number ("0501234567", "971501234567"), a bank
     * account or a contract number. Not part of a decimal or grouped amount
     * ("1234567.89", "1,234,567"), and not right next to a currency code or
     * symbol, so an amount written in a large-unit currency stays readable.
     */
    private val midLengthNumberPattern = Regex("""(?<![\d.,])\d{9,12}(?![\d]|[.,]\d)""")
    private val currencyBefore = Regex("""(?:\b[A-Z]{3}|[${'$'}€£₹¥]|درهم|د\.إ)\s?$""")
    private val currencyAfter = Regex("""^\s?(?:[A-Z]{3}\b|درهم|د\.إ)""")

    fun redactSensitiveData(logMessage: String): String {
        // Arabic-Indic digits are folded to ASCII first, because the numeric patterns
        // only see ASCII digits and would otherwise let a card number written in them through.
        val folded = Digits.fold(logMessage)
        val ibansMasked = ibanPattern.replace(folded) { match -> maskKeepingLast4(match.value) }
        val cardsMasked = cardNumberPattern.replace(ibansMasked) { match ->
            maskKeepingLast4(match.groupValues.drop(1).joinToString(""))
        }
        val longMasked = longAccountNumberPattern.replace(cardsMasked) { match ->
            maskKeepingLast4(match.value)
        }
        return midLengthNumberPattern.replace(longMasked) { m ->
            val before = longMasked.substring(0, m.range.first)
            val after = longMasked.substring(m.range.last + 1)
            if (currencyBefore.containsMatchIn(before) || currencyAfter.containsMatchIn(after)) m.value else maskKeepingLast4(m.value)
        }
    }

    /** Unspaced IBAN: 2 letters, 2 check digits, then 11-30 alphanumerics (e.g. AE070331234567890123456). */
    private val ibanPattern = Regex("""\b[A-Za-z]{2}\d{2}[A-Za-z0-9]{11,30}\b""")

    private fun maskKeepingLast4(digits: String): String {
        val last4 = digits.takeLast(4)
        return if (digits.length == 16) {
            "**** **** **** $last4"
        } else {
            "*".repeat((digits.length - 4).coerceAtLeast(0)) + last4
        }
    }
}
