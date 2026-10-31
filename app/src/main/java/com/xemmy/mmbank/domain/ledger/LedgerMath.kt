package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import java.math.BigDecimal

/**
 * The one definition of "Spent" and "Received" every screen, report, budget
 * and forecast uses. Before this, about fifteen places each wrote their own
 * `!excludeFromStats && direction == DEBIT` and quietly disagreed (the
 * Activity hero card ignored the exclusion flag entirely).
 *
 * By [TransactionKind]:
 *  - SPEND, ATM: add to spending.
 *  - REFUND: subtract from spending -- money back for a purchase is not income.
 *  - INCOME: adds to received.
 *  - TRANSFER, CARD_PAYMENT, CASHBACK, ADJUSTMENT: neither.
 * A row the user hid from analytics ([Transaction.excludeFromStats]) counts
 * toward neither, whatever its kind.
 *
 * [raw] = true is the Dashboard's "show the whole ledger" view: every debit is
 * spending and every credit is income, kinds and the exclusion flag ignored.
 *
 * Account balances deliberately don't go through here -- every row moves its
 * account's balance (see [com.xemmy.mmbank.domain.account.AccountBalanceCalculator]).
 */
object LedgerMath {

    enum class Effect { SPEND, REFUND, INCOME, NONE }

    fun effect(tx: Transaction, raw: Boolean = false): Effect = when {
        raw -> if (tx.direction == TransactionDirection.CREDIT) Effect.INCOME else Effect.SPEND
        tx.excludeFromStats -> Effect.NONE
        else -> when (tx.kind) {
            TransactionKind.SPEND, TransactionKind.ATM -> Effect.SPEND
            TransactionKind.REFUND -> Effect.REFUND
            TransactionKind.INCOME -> Effect.INCOME
            TransactionKind.TRANSFER, TransactionKind.CARD_PAYMENT,
            TransactionKind.CASHBACK, TransactionKind.ADJUSTMENT -> Effect.NONE
        }
    }

    /** +1 for spending, -1 for a refund, 0 for anything that doesn't touch spending. */
    fun spendSign(tx: Transaction, raw: Boolean = false): Int = when (effect(tx, raw)) {
        Effect.SPEND -> 1
        Effect.REFUND -> -1
        else -> 0
    }

    /** True for rows that move the spending total (purchases and refunds). */
    fun affectsSpend(tx: Transaction, raw: Boolean = false): Boolean = spendSign(tx, raw) != 0

    /** True only for a purchase -- not a refund. For "biggest expense", subscriptions, etc. */
    fun isSpend(tx: Transaction, raw: Boolean = false): Boolean = effect(tx, raw) == Effect.SPEND

    fun isIncome(tx: Transaction, raw: Boolean = false): Boolean = effect(tx, raw) == Effect.INCOME

    /** Signed contribution to spending, in the row's own currency. */
    fun spendDelta(tx: Transaction, raw: Boolean = false): BigDecimal = when (spendSign(tx, raw)) {
        1 -> tx.amount
        -1 -> tx.amount.negate()
        else -> BigDecimal.ZERO
    }

    /** Contribution to received, in the row's own currency. */
    fun incomeDelta(tx: Transaction, raw: Boolean = false): BigDecimal =
        if (isIncome(tx, raw)) tx.amount else BigDecimal.ZERO

    /**
     * Net spending over [txs], each converted by [value] (which returns null
     * for a row it can't convert -- that row is skipped, as elsewhere).
     */
    fun netSpend(txs: Iterable<Transaction>, raw: Boolean = false, value: (Transaction) -> Double?): Double =
        txs.sumOf { tx ->
            val sign = spendSign(tx, raw)
            if (sign == 0) 0.0 else (value(tx) ?: 0.0) * sign
        }

    /** Total received over [txs], converted by [value]. */
    fun income(txs: Iterable<Transaction>, raw: Boolean = false, value: (Transaction) -> Double?): Double =
        txs.sumOf { tx -> if (isIncome(tx, raw)) value(tx) ?: 0.0 else 0.0 }

    /** Exact net spending over [txs] already known to share one currency. */
    fun netSpendExact(txs: Iterable<Transaction>, raw: Boolean = false): BigDecimal =
        txs.fold(BigDecimal.ZERO) { acc, tx -> acc + spendDelta(tx, raw) }

    /** Exact total received over [txs] already known to share one currency. */
    fun incomeExact(txs: Iterable<Transaction>, raw: Boolean = false): BigDecimal =
        txs.fold(BigDecimal.ZERO) { acc, tx -> acc + incomeDelta(tx, raw) }
}
