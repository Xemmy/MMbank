package com.xemmy.mmbank.util

/**
 * Masks sensitive numeric sequences (card numbers, bank account numbers)
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

    fun redactSensitiveData(logMessage: String): String {
        val cardsMasked = cardNumberPattern.replace(logMessage) { match ->
            maskKeepingLast4(match.groupValues.drop(1).joinToString(""))
        }
        return longAccountNumberPattern.replace(cardsMasked) { match ->
            maskKeepingLast4(match.value)
        }
    }

    private fun maskKeepingLast4(digits: String): String {
        val last4 = digits.takeLast(4)
        return if (digits.length == 16) {
            "**** **** **** $last4"
        } else {
            "*".repeat((digits.length - 4).coerceAtLeast(0)) + last4
        }
    }
}
