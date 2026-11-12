package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import java.math.BigDecimal

/**
 * Recognises a credit that is really money coming back from a purchase the
 * user made earlier, even when the bank's wording never says "refund" (a bare
 * "AED 120.00 credited -- AMAZON" otherwise counts as income and overstates
 * both Spent and Received).
 *
 * A credit is a refund of a purchase when ALL of these hold:
 *  - same currency and the same merchant once case and punctuation are
 *    ignored (merchant must be at least [MIN_MERCHANT_CHARS] long, so a stray
 *    "A" can't pair with anything);
 *  - the purchase is a plain SPEND debit that happened before the credit and
 *    no more than [windowDays] earlier;
 *  - the credit is no larger than the purchase (a partial refund is fine;
 *    getting back more than was paid is not a refund).
 * Among several candidates an exact-amount match wins, then the most recent.
 */
object RefundMatcher {

    const val DEFAULT_WINDOW_DAYS = 90
    const val MIN_MERCHANT_CHARS = 3
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** Lower-cased merchant with everything but letters and digits removed. */
    internal fun key(merchant: String?): String =
        merchant.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    /** The purchase [credit] refunds, or null when none of [spends] qualifies. */
    fun findOriginal(
        credit: Transaction,
        spends: List<Transaction>,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): Transaction? {
        if (credit.direction != TransactionDirection.CREDIT || credit.amount.signum() <= 0) return null
        val creditKey = key(credit.merchant)
        if (creditKey.length < MIN_MERCHANT_CHARS) return null
        val windowMillis = windowDays * DAY_MILLIS
        val candidates = spends.filter { s ->
            s.direction == TransactionDirection.DEBIT &&
                s.kind == TransactionKind.SPEND &&
                s.currencyCode == credit.currencyCode &&
                key(s.merchant) == creditKey &&
                s.timestampMillis <= credit.timestampMillis &&
                credit.timestampMillis - s.timestampMillis <= windowMillis &&
                credit.amount.compareTo(s.amount) <= 0
        }
        return candidates.maxWithOrNull(
            compareBy<Transaction> { exact(credit.amount, it.amount) }.thenBy { it.timestampMillis },
        )
    }

    private fun exact(a: BigDecimal, b: BigDecimal): Int = if (a.compareTo(b) == 0) 1 else 0

    /**
     * The kind for a freshly captured credit: [TransactionKind.REFUND] when it
     * matches an earlier purchase and was classified only by default (plain
     * INCOME), otherwise [detected] unchanged. A kind from a rule, a learned
     * merchant mapping or the message wording is never second-guessed --
     * callers pass [overridable] = false for those.
     */
    fun refine(
        detected: TransactionKind,
        credit: Transaction,
        spends: List<Transaction>,
        overridable: Boolean = true,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): TransactionKind =
        if (overridable && detected == TransactionKind.INCOME && findOriginal(credit, spends, windowDays) != null) {
            TransactionKind.REFUND
        } else {
            detected
        }
}
