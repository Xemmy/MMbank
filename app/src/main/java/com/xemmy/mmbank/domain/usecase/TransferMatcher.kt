package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionKind
import kotlin.math.abs

/**
 * Pairs the two messages one movement of the user's own money generates -- a
 * debit leaving one account and a credit landing on another -- into a linked
 * pair, so neither leg is counted as spending or income. The classic case is
 * a credit-card payment: "AED 3,600 debited from *963" plus "your payment of
 * AED 3,600 for card ending *876 has been credited".
 *
 * [com.xemmy.mmbank.data.repository.TransactionRepositoryImpl] fetches the
 * (already amount/currency/opposite-direction/unlinked-filtered) candidates
 * from Room; this is the pure decision of which one -- if any -- is the real
 * counterpart.
 *
 * A pairing is only ever made when at least one side is already known to be
 * an internal move ([TransactionKind.isInternalMove], set by
 * [com.xemmy.mmbank.domain.ledger.TransactionKindDetector]). Two ordinary
 * same-amount transactions are never linked just for coinciding in time --
 * and, unlike the old keyword-based check, an ATM withdrawal or a message
 * that merely mentions "transfer" no longer qualifies a row on its own.
 *
 * Other guards:
 *  - both legs on the same account is never a transfer;
 *  - a leg the user explicitly classified as something else
 *    ([Transaction.kindUserSet]) is never pulled into a pair;
 *  - card payments get a [CARD_PAYMENT_WINDOW_MILLIS] window, because banks
 *    often post the card-side credit a day or two after the debit; plain
 *    transfers keep the tight [MATCH_WINDOW_MILLIS].
 *
 * Rapid back-to-back identical-amount payments still pair correctly: the
 * caller only passes candidates with `linkedTransactionId IS NULL`, and the
 * closest in time wins.
 */
object TransferMatcher {

    /** How close in time the two legs of a plain transfer must land to be paired. */
    const val MATCH_WINDOW_MILLIS = 10 * 60 * 1000L

    /** Card-side credits often post a day or two late. */
    const val CARD_PAYMENT_WINDOW_MILLIS = 72 * 60 * 60 * 1000L

    /** The widest window any pairing can use -- what the candidate query should fetch. */
    const val MAX_WINDOW_MILLIS = CARD_PAYMENT_WINDOW_MILLIS

    /**
     * The best still-unlinked candidate to pair with [inserted], or null if
     * none qualifies. [accountTypes] maps account ids to their type, so a
     * credit landing on a credit card is recognized as a card payment.
     */
    fun bestMatch(
        inserted: Transaction,
        candidates: List<Transaction>,
        accountTypes: Map<Long, AccountType> = emptyMap(),
    ): Transaction? {
        if (blocked(inserted)) return null
        return candidates
            .asSequence()
            .filter { it.id != inserted.id }
            .filter { it.direction != inserted.direction }
            .filterNot(::blocked)
            .filter { it.accountId == null || inserted.accountId == null || it.accountId != inserted.accountId }
            .filter { inserted.kind.isInternalMove || it.kind.isInternalMove }
            .filter { abs(it.timestampMillis - inserted.timestampMillis) <= windowFor(inserted, it, accountTypes) }
            .minByOrNull { abs(it.timestampMillis - inserted.timestampMillis) }
    }

    /** The kind both legs of a linked pair get. */
    fun pairKind(a: Transaction, b: Transaction, accountTypes: Map<Long, AccountType> = emptyMap()): TransactionKind =
        if (isCardPayment(a, b, accountTypes)) TransactionKind.CARD_PAYMENT else TransactionKind.TRANSFER

    private fun blocked(t: Transaction) = t.kindUserSet && !t.kind.isInternalMove

    private fun windowFor(a: Transaction, b: Transaction, accountTypes: Map<Long, AccountType>): Long =
        if (isCardPayment(a, b, accountTypes)) CARD_PAYMENT_WINDOW_MILLIS else MATCH_WINDOW_MILLIS

    private fun isCardPayment(a: Transaction, b: Transaction, accountTypes: Map<Long, AccountType>): Boolean =
        a.kind == TransactionKind.CARD_PAYMENT || b.kind == TransactionKind.CARD_PAYMENT ||
            listOf(a, b).any { leg -> leg.accountId?.let { accountTypes[it] } == AccountType.CREDIT_CARD }
}
