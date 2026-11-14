package com.xemmy.mmbank.domain.parser

/**
 * Pure checks that stop the built-in regex rules from recording things that
 * are not payments, or from reading a non-money number as the amount. Applied
 * only to built-in rules, so a user's own rule keeps full control.
 */
object ParseGuards {

    /**
     * Messages that mention a card/amount but are not a completed transaction:
     * blocked cards, declined or failed attempts, and one-time codes.
     */
    private val NON_TRANSACTION = Regex(
        "\\b(?:declined?|unsuccessful|failed|rejected|insufficient\\s+(?:funds|balance)|" +
            "(?:has\\s+been|is|was)\\s+blocked|card\\s+blocked|otp|one[- ]time\\s+(?:password|pin|code)|" +
            "verification\\s+code|do\\s+not\\s+share)\\b|مرفوض|رمز\\s+التحقق|كلمة\\s+(?:المرور|السر)\\s+لمرة",
        RegexOption.IGNORE_CASE,
    )

    /** A number introduced as a card/account ending, e.g. "card ending 1234", "a/c no 5678", "*1234". */
    private val IDENTIFIER_BEFORE = Regex(
        "(?:ending(?:\\s+(?:in|with))?|ends\\s+(?:in|with)|card(?:\\s+(?:no\\.?|number))?|" +
            "a/c(?:\\s+no\\.?)?|account(?:\\s+(?:no\\.?|number))?|[*•xX])\\s?:?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** An uppercase 3-letter token right before the amount: an unrecognised currency code ("THB 1,500.00"). */
    private val CODE_BEFORE = Regex("(?<![A-Za-z])[A-Z]{3}\\s?$")

    fun isNonTransaction(text: String): Boolean = NON_TRANSACTION.containsMatchIn(text)

    /** True when the amount at [amountRange] is really a card/account number. */
    fun isIdentifierAmount(text: String, amountRange: IntRange): Boolean =
        IDENTIFIER_BEFORE.containsMatchIn(text.substring(0, amountRange.first).takeLast(24))

    /** True when an uppercase currency-like code sits directly before the amount. */
    fun hasUnrecognisedCurrencyBefore(text: String, amountRange: IntRange): Boolean =
        CODE_BEFORE.containsMatchIn(text.substring(0, amountRange.first).takeLast(6))
}
