package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.ledger.DriftWindow
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountChange
import com.xemmy.mmbank.domain.model.AccountChangeType
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal

/**
 * One account's history for the "why did this balance move?" screen: its
 * transactions and its change log merged into a single newest-first list.
 * Pure, so ordering, the running balance, grouping and filters are unit tested.
 *
 * Two clocks meet here, deliberately kept apart:
 *  - a **transaction** sits at its event time, and carries the **running
 *    balance** after it: the current rows replayed oldest-first from the
 *    current opening balance (a statement view, ending exactly at today's
 *    balance);
 *  - a **change** sits at the moment it was made, and shows what it did to the
 *    balance *then* ([ChangeItem.delta]). An edit made today to a March
 *    transaction therefore shows up today -- which is exactly when the balance
 *    moved.
 *
 * Amounts are face value in each row's own currency, as in
 * [AccountBalanceCalculator] before FX; [Result.mixedCurrency] tells the
 * screen when that makes the running balance approximate.
 */
object AccountTimeline {

    enum class Filter(val label: String) {
        ALL("Everything"),
        BALANCE("Balance changes"),
        EDITS("Edits"),
        ;

        /** Whether a change-log entry belongs under this filter. Transactions appear under [ALL] only. */
        fun matches(change: AccountChange): Boolean = when (this) {
            ALL -> true
            BALANCE -> change.type.canMoveBalance
            EDITS -> change.type in EDIT_TYPES
        }
    }

    private val EDIT_TYPES = setOf(
        AccountChangeType.DETAILS_EDITED,
        AccountChangeType.OPENING_BALANCE_CHANGED,
        AccountChangeType.TRANSACTION_EDITED,
        AccountChangeType.ARCHIVED,
        AccountChangeType.RESTORED,
    )

    /** Bulk operations write one entry per row at the same instant; these collapse back into one line. */
    private val GROUPABLE = setOf(
        AccountChangeType.TRANSACTION_MOVED_IN,
        AccountChangeType.TRANSACTION_MOVED_OUT,
        AccountChangeType.TRANSACTION_DELETED,
    )

    sealed interface Item {
        val timestampMillis: Long
        val key: String
    }

    data class TransactionItem(
        val transaction: Transaction,
        /** Signed effect on the balance: credits positive, debits negative. */
        val delta: BigDecimal,
        /** The running balance right after this row. */
        val balanceAfter: BigDecimal,
    ) : Item {
        override val timestampMillis: Long get() = transaction.timestampMillis
        override val key: String get() = "t${transaction.id}"
    }

    data class ChangeItem(
        val change: AccountChange,
        /** How many log entries this line stands for (1 unless a bulk operation was collapsed). */
        val count: Int,
        /** Total balance effect when it was made; null when none can be stated. */
        val delta: BigDecimal?,
        val line: AccountChangeWording.Line,
    ) : Item {
        override val timestampMillis: Long get() = change.timestampMillis
        override val key: String get() = "c${change.id}"
    }

    data class Result(
        val items: List<Item>,
        val openingBalance: BigDecimal,
        /** Opening balance plus every transaction, at face value -- the figure the running balance ends on. */
        val currentBalance: BigDecimal,
        val currencyCode: String,
        val mixedCurrency: Boolean,
        /** Change-log entries for the account regardless of [Filter] (0 means "nothing logged yet"). */
        val changeCount: Int,
    )

    fun build(
        account: Account,
        transactions: List<Transaction>,
        changes: List<AccountChange>,
        filter: Filter = Filter.ALL,
    ): Result {
        val own = transactions.filter { it.accountId == account.id }
        val ascending = own.sortedWith(compareBy<Transaction>({ it.timestampMillis }, { it.id }))

        var running = account.openingBalance
        val txItems = ascending.map { tx ->
            val delta = if (tx.direction == TransactionDirection.CREDIT) tx.amount else tx.amount.negate()
            running += delta
            TransactionItem(tx, delta, running)
        }

        val changeItems = group(changes.filter { it.accountId == account.id && filter.matches(it) })

        val items = buildList<Item> {
            if (filter == Filter.ALL) addAll(txItems)
            addAll(changeItems)
        }.sortedWith(
            compareByDescending<Item> { it.timestampMillis }
                // Same instant: the change that explains a row sits above it.
                .thenBy { if (it is ChangeItem) 0 else 1 }
                .thenByDescending { sortId(it) },
        )

        return Result(
            items = items,
            openingBalance = account.openingBalance,
            currentBalance = running,
            currencyCode = account.currencyCode,
            mixedCurrency = own.any { !it.currencyCode.equals(account.currencyCode, ignoreCase = true) },
            changeCount = changes.count { it.accountId == account.id },
        )
    }

    /**
     * Narrows [result] to what could explain a [DriftWindow]: the transactions dated
     * inside it and the balance-moving changes made since its start. Running balances
     * on the rows are untouched, so they still read as the statement view.
     */
    fun restrictTo(result: Result, window: DriftWindow.Window): Result = result.copy(
        items = result.items.filter { item ->
            when (item) {
                is TransactionItem -> window.containsEvent(item.timestampMillis)
                is ChangeItem -> DriftWindow.relevant(window, item.change)
            }
        },
    )

    private fun sortId(item: Item): Long = when (item) {
        is TransactionItem -> item.transaction.id
        is ChangeItem -> item.change.id
    }

    /**
     * Collapses runs of the same bulk operation: entries of one groupable type
     * written at the same instant (and, for moves, between the same two
     * accounts) become one line with a count and the summed effect.
     */
    internal fun group(changes: List<AccountChange>): List<ChangeItem> {
        val ordered = changes.sortedWith(compareByDescending<AccountChange> { it.timestampMillis }.thenByDescending { it.id })
        val result = mutableListOf<ChangeItem>()
        var i = 0
        while (i < ordered.size) {
            val head = ordered[i]
            var j = i + 1
            if (head.type in GROUPABLE) {
                while (j < ordered.size && sameBulk(head, ordered[j])) j++
            }
            val run = ordered.subList(i, j)
            val deltas = run.mapNotNull { it.balanceDelta }
            result += ChangeItem(
                change = head,
                count = run.size,
                delta = if (deltas.isEmpty()) null else deltas.fold(BigDecimal.ZERO) { acc, d -> acc + d },
                line = AccountChangeWording.line(head, run.size),
            )
            i = j
        }
        return result
    }

    private fun sameBulk(a: AccountChange, b: AccountChange): Boolean =
        a.type == b.type && a.timestampMillis == b.timestampMillis && a.accountId == b.accountId &&
            a.source == b.source && a.currencyCode == b.currencyCode &&
            (a.type == AccountChangeType.TRANSACTION_DELETED || (a.oldValue == b.oldValue && a.newValue == b.newValue))
}
