package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import kotlin.math.abs

/**
 * Pairs the two SMS/notifications a credit-card statement payment generates
 * -- a generic debit from the funding account and a credit landing on the
 * card, recognized by [com.xemmy.mmbank.domain.parser.BuiltInRules]'
 * `builtin.en.transfer.*` rules (tagged [SpendingCategory.TRANSFERS] at parse
 * time) -- into one linked pair, so neither leg inflates Income/Spent on the
 * Dashboard. [com.xemmy.mmbank.data.repository.TransactionRepositoryImpl]
 * fetches the (already amount/currency/opposite-direction/unlinked-filtered)
 * candidate rows from Room; this is the pure decision of which one — if any
 * — is the real counterpart, so it's JVM-testable without a database.
 *
 * The debit leg's wording alone never indicates a transfer (it reads like any
 * other outgoing payment), so a pairing is only ever made when at least one
 * side is already tagged [SpendingCategory.TRANSFERS] — two ordinary
 * same-amount transactions are never linked just for coinciding in time.
 *
 * Rapid back-to-back identical-amount payments (e.g. two AED 1,000 card
 * payments a minute apart) still pair correctly: the caller only ever passes
 * candidates with `linkedTransactionId IS NULL`, so once a pair is linked it
 * drops out of every later lookup and can't be reused by a second pair.
 */
object TransferMatcher {

    /** How close in time the two legs of a statement payment must land to be paired. */
    const val MATCH_WINDOW_MILLIS = 10 * 60 * 1000L

    /**
     * The best still-unlinked candidate to pair with [inserted], or null if
     * none qualifies. [candidates] must already share [inserted]'s amount,
     * currency, and the opposite direction — this only applies the time
     * window, the "at least one side is a recognized transfer" requirement,
     * and picks the closest in time when several qualify.
     */
    fun bestMatch(inserted: Transaction, candidates: List<Transaction>): Transaction? {
        val insertedIsTransfer = inserted.spendingCategory == SpendingCategory.TRANSFERS
        return candidates
            .asSequence()
            .filter { it.id != inserted.id }
            .filter { abs(it.timestampMillis - inserted.timestampMillis) <= MATCH_WINDOW_MILLIS }
            .filter { insertedIsTransfer || it.spendingCategory == SpendingCategory.TRANSFERS }
            .minByOrNull { abs(it.timestampMillis - inserted.timestampMillis) }
    }
}
