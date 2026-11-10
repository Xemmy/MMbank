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
        val folded = foldDigits(logMessage)
        val ibansMasked = ibanPattern.replace(folded) { match -> maskKeepingLast4(match.value) }
        val cardsMasked = cardNumberPattern.replace(ibansMasked) { match ->
            maskKeepingLast4(match.groupValues.drop(1).joinToString(""))
        }
        return longAccountNumberPattern.replace(cardsMasked) { match ->
            maskKeepingLast4(match.value)
        }
    }

    /** Unspaced IBAN: 2 letters, 2 check digits, then 11-30 alphanumerics (e.g. AE070331234567890123456). */
    private val ibanPattern = Regex("""\b[A-Za-z]{2}\d{2}[A-Za-z0-9]{11,30}\b""")

    /**
     * Arabic-Indic (٠-٩) and Extended Arabic-Indic (۰-۹) digits are folded to
     * ASCII first, because the numeric patterns below only see ASCII digits and
     * would otherwise let a card number written in them through unmasked.
     */
    private fun foldDigits(text: String): String {
        if (text.none { it in '\u0660'..'\u0669' || it in '\u06F0'..'\u06F9' }) return text
        return buildString(text.length) {
            for (c in text) {
                append(
                    when (c) {
                        in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                        in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                        else -> c
                    },
                )
            }
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
