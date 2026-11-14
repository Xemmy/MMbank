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

    /**
     * The balances a monthly report for [month] should show. The current (or a
     * later) month is simply now, at today's rates. A past month is replayed to
     * its last moment at the rates recorded for that month-end
     * ([RateHistory.overlay]) -- it used to show today's balances under a past
     * month's headline. Null when the ledger can't speak for [month] (before
     * its oldest kept row, or empty), so the caller shows no net worth rather
     * than a wrong one.
     */
    fun balancesForMonth(
        accounts: List<Account>,
        transactions: List<Transaction>,
        month: YearMonth,
        thisMonth: YearMonth,
        zone: ZoneId,
        baseCurrency: String,
        todayRates: Map<String, BigDecimal>,
        history: List<com.xemmy.mmbank.domain.model.ExchangeRateOnDay>,
    ): Map<Long, BigDecimal>? {
        val currencies = accounts.associate { it.id to it.currencyCode }
        if (month >= thisMonth) {
            return balancesAt(accounts, transactions, Long.MAX_VALUE, LedgerFx(baseCurrency, todayRates, currencies))
        }
        val start = historyStart(transactions, zone) ?: return null
        if (month < start) return null
        val rates = RateHistory.overlay(todayRates, RateHistory.forBase(history, baseCurrency), month.atEndOfMonth())
        val endMillis = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return balancesAt(accounts, transactions, endMillis, LedgerFx(baseCurrency, rates, currencies))
    }

    fun toLedgerEntry(tx: Transaction) = AccountBalanceCalculator.LedgerEntry(
        accountId = tx.accountId,
        amount = tx.amount,
        currencyCode = tx.currencyCode,
        direction = tx.direction,
        historyOnly = tx.historyOnly,
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
