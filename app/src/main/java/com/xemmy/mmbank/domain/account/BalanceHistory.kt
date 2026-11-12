package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.Transaction
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Balances as they stood at a past moment, rebuilt from the ledger -- so a
 * net-worth history needs no snapshot table and is available the day the
 * feature ships, for as far back as the ledger goes.
 *
 * Uses [AccountBalanceCalculator]'s single formula (opening balance plus every
 * row up to that moment, excluded-from-stats rows included, currencies at
 * face value), so the latest point always equals the balance every other
 * screen shows.
 *
 * Limit, by construction: when retention pruning is on, pruned rows are
 * folded into the opening balance ([PrunedBalanceFolder]), so nothing before
 * the oldest kept row can be rebuilt. [historyStart] is where history begins.
 */
object BalanceHistory {

    /** Each account's balance at [atMillis] (inclusive). Every account is present. */
    fun balancesAt(
        accounts: List<Account>,
        transactions: List<Transaction>,
        atMillis: Long,
        fx: LedgerFx? = null,
    ): Map<Long, BigDecimal> {
        val entries = transactions.filter { it.timestampMillis <= atMillis }.map(::toLedgerEntry)
        return AccountBalanceCalculator.balances(accounts, fx?.apply(entries) ?: entries)
    }

    fun toLedgerEntry(tx: Transaction) = AccountBalanceCalculator.LedgerEntry(
        accountId = tx.accountId,
        amount = tx.amount,
        currencyCode = tx.currencyCode,
        direction = tx.direction,
    )

    /**
     * The last moment of each of the [count] months ending with [current],
     * oldest first. The current month's entry is [Long.MAX_VALUE] -- "now,
     * including anything dated later" -- so it matches today's balances exactly.
     */
    fun monthEnds(current: YearMonth, count: Int, zone: ZoneId): List<Pair<YearMonth, Long>> =
        (count - 1 downTo 0).map { back ->
            val month = current.minusMonths(back.toLong())
            month to if (back == 0) {
                Long.MAX_VALUE
            } else {
                month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            }
        }

    /** The month of the oldest transaction: the first month the ledger can speak for. Null when empty. */
    fun historyStart(transactions: List<Transaction>, zone: ZoneId): YearMonth? =
        transactions.minOfOrNull { it.timestampMillis }?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(zone)) }
}
