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

    /**
     * A number introduced as a card/account ending ("card ending 1234", "a/c no 5678", "*1234")
     * or as a reference ("order #78402", "Ref 5521", "invoice no. 88", "booking ID 1234").
     */
    private val IDENTIFIER_BEFORE = Regex(
        "(?:ending(?:\\s+(?:in|with))?|ends\\s+(?:in|with)|card(?:\\s+(?:no\\.?|number))?|" +
            "a/c(?:\\s+no\\.?)?|account(?:\\s+(?:no\\.?|number))?|" +
            "\\b(?:order|ref(?:erence)?|invoice|booking|ticket|tracking)(?:\\s*(?:no\\.?|number|id|#))?|[*•xX#])\\s?:?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** Right after the number: ":30" or " pm" -- a clock time ("Dr. Ali at 4:30 PM"), not money. */
    private val TIME_AFTER = Regex("^(?::\\d{2}|\\s?(?:am|pm)\\b)", RegexOption.IGNORE_CASE)

    /** An uppercase 3-letter token right before the amount: an unrecognised currency code ("THB 1,500.00"). */
    private val CODE_BEFORE = Regex("(?<![A-Za-z])[A-Z]{3}\\s?$")

    fun isNonTransaction(text: String): Boolean = NON_TRANSACTION.containsMatchIn(text)

    /** True when the amount at [amountRange] is really a card/account number. */
    fun isIdentifierAmount(text: String, amountRange: IntRange): Boolean =
        IDENTIFIER_BEFORE.containsMatchIn(text.substring(0, amountRange.first).takeLast(24))

    /** True when the number at [amountRange] is the hour of a clock time ("4:30", "9 am"). */
    fun isTimeOfDay(text: String, amountRange: IntRange): Boolean =
        TIME_AFTER.containsMatchIn(text.substring(amountRange.last + 1))

    /** True when an uppercase currency-like code sits directly before the amount. */
    fun hasUnrecognisedCurrencyBefore(text: String, amountRange: IntRange): Boolean =
        CODE_BEFORE.containsMatchIn(text.substring(0, amountRange.first).takeLast(6))
}
