package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionKind

/**
 * "Before I started tracking…" -- which unassigned transactions the user can
 * set aside as history only in one go (see `Transaction.historyOnly`). Pure, so
 * the choices and their counts are unit tested.
 *
 * Only rows that still need an account are ever offered: a filed row is not
 * unassigned, and one already set aside has nothing left to do.
 */
object HistoryOnlyPlanner {

    enum class Scope {
        /** Dated before the earliest moment any account's balance was known to be right. */
        BEFORE_TRACKING,
        OLDER_THAN_30_DAYS,
        ALL,
    }

    data class Option(
        val scope: Scope,
        /** Rows dated before this are included; null for [Scope.ALL]. */
        val beforeMillis: Long?,
        val transactionIds: List<Long>,
    ) {
        val count: Int get() = transactionIds.size
    }

    private const val THIRTY_DAYS_MILLIS = 30L * 24 * 60 * 60 * 1000

    /** The newest balance-adjustment time per account, read from the ledger itself. */
    fun lastAdjustments(transactions: List<Transaction>): Map<Long, Long> {
        val latest = HashMap<Long, Long>()
        for (tx in transactions) {
            if (tx.kind != TransactionKind.ADJUSTMENT) continue
            val accountId = tx.accountId ?: continue
            latest[accountId] = maxOf(latest[accountId] ?: Long.MIN_VALUE, tx.timestampMillis)
        }
        return latest
    }

    /**
     * When tracking began: the earliest [BalanceNeutralFiling.cutoffFor] among
     * [accounts]. A transaction dated before it predates every balance the app
     * holds. Null with no accounts.
     */
    fun trackingStart(accounts: List<Account>, transactions: List<Transaction>): Long? {
        val adjusted = lastAdjustments(transactions)
        return accounts.minOfOrNull { BalanceNeutralFiling.cutoffFor(it, adjusted[it.id]) }
    }

    /**
     * The choices to offer, widest last. [Scope.BEFORE_TRACKING] is absent when
     * there is no account to date it from. Options that would mark nothing are
     * kept (with a zero count) so the dialog can say so rather than hide them.
     */
    fun options(transactions: List<Transaction>, accounts: List<Account>, nowMillis: Long): List<Option> {
        val pending = transactions.filter { it.needsAccount }
        fun before(scope: Scope, cutoff: Long) =
            Option(scope, cutoff, pending.filter { it.timestampMillis < cutoff }.map { it.id })
        return buildList {
            trackingStart(accounts, transactions)?.let { add(before(Scope.BEFORE_TRACKING, it)) }
            add(before(Scope.OLDER_THAN_30_DAYS, nowMillis - THIRTY_DAYS_MILLIS))
            add(Option(Scope.ALL, null, pending.map { it.id }))
        }
    }

    /** The option to preselect: the narrowest one that marks something, else the first. */
    fun default(options: List<Option>): Option? = options.firstOrNull { it.count > 0 } ?: options.firstOrNull()
}
