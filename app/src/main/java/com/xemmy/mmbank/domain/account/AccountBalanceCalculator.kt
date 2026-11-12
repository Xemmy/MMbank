package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal

/**
 * The single balance formula: `openingBalance + (credits - debits)`.
 *
 * Balances in MMbank are derived, never stored (see [Account.openingBalance]),
 * so this is the only place the derivation lives. It replaces three divergent
 * copies that disagreed with each other:
 *
 *  - `AccountsScreen` and `DashboardViewModel` summed a `Double` produced by
 *    `CAST(amount AS REAL)` in SQL -- lossy, because amounts are stored as
 *    plain-decimal TEXT (see `Converters`).
 *  - `MonthlyReportScreen` folded in `BigDecimal` but filtered out
 *    `excludeFromStats` rows, so its net worth legitimately disagreed with the
 *    Accounts screen.
 *
 * Two semantics are deliberate and pinned by their own tests, so neither can
 * decay into an accident:
 *
 *  1. **`excludeFromStats` rows are counted.** A "Balance adjustment" and both
 *     legs of a linked transfer are real money movement; `excludeFromStats`
 *     means "keep this out of income/spend ANALYTICS", not "this never
 *     happened". Filtering them out of a *balance* is what made the monthly
 *     report wrong. Callers who want analytics figures filter for themselves.
 *  2. **Amounts are summed as given.** This calculator does no currency
 *     maths: the entries it receives are already in their account's currency
 *     where a rate exists ([LedgerFx], applied where the ledger entries are
 *     produced), and at face value, flagged `approximate`, where none does.
 *
 * Pure and `BigDecimal` end to end, so it is exact and JVM-testable.
 */
object AccountBalanceCalculator {

    /**
     * One transaction reduced to what a balance needs. Deliberately narrow: the
     * balance surfaces read this projection rather than whole `TransactionEntity`
     * rows, which carry the full `rawMessage` SMS body.
     */
    data class LedgerEntry(
        /** Null for a transaction that belongs to no account -- see [unassignedTotals]. */
        val accountId: Long?,
        val amount: BigDecimal,
        val currencyCode: String,
        val direction: TransactionDirection,
        /** True when [amount] was converted from another currency (or couldn't be): show it as "≈". See [LedgerFx]. */
        val approximate: Boolean = false,
    )

    /** Signed contribution of one entry: credits add, debits subtract. */
    private fun LedgerEntry.signedAmount(): BigDecimal =
        if (direction == TransactionDirection.CREDIT) amount else amount.negate()

    /**
     * Net movement per account, for entries that belong to one. Accounts with
     * no entries are absent rather than zero -- [balanceFor] treats both alike.
     */
    fun netByAccount(entries: List<LedgerEntry>): Map<Long, BigDecimal> {
        val net = LinkedHashMap<Long, BigDecimal>()
        for (entry in entries) {
            val accountId = entry.accountId ?: continue
            net[accountId] = (net[accountId] ?: BigDecimal.ZERO) + entry.signedAmount()
        }
        return net
    }

    /** [account]'s balance given a pre-computed [netByAccount] map. */
    fun balanceOf(account: Account, netByAccount: Map<Long, BigDecimal>): BigDecimal =
        account.openingBalance + (netByAccount[account.id] ?: BigDecimal.ZERO)

    /** [account]'s balance computed straight from [entries]. */
    fun balanceFor(account: Account, entries: List<LedgerEntry>): BigDecimal =
        balanceOf(account, netByAccount(entries))

    /**
     * Every account's balance, keyed by account id. Every account in [accounts]
     * is present, including ones with no transactions at all.
     */
    fun balances(accounts: List<Account>, entries: List<LedgerEntry>): Map<Long, BigDecimal> {
        val net = netByAccount(entries)
        return accounts.associate { it.id to balanceOf(it, net) }
    }

    /**
     * Signed net of every entry that belongs to no account, grouped by its own
     * currency. This money is in the ledger but in no balance, so without
     * surfacing it a mis-captured transaction is simply invisible.
     *
     * Grouped by currency rather than summed flat: unlike an account balance,
     * this total has no single currency to be denominated in, so summing
     * across currencies here would invent a number rather than report one.
     */
    fun unassignedTotals(entries: List<LedgerEntry>): Map<String, BigDecimal> {
        val totals = LinkedHashMap<String, BigDecimal>()
        for (entry in entries) {
            if (entry.accountId != null) continue
            val code = entry.currencyCode.uppercase()
            totals[code] = (totals[code] ?: BigDecimal.ZERO) + entry.signedAmount()
        }
        return totals
    }

    /**
     * Accounts whose balance includes at least one [LedgerEntry.approximate]
     * entry -- a charge in another currency, converted at today's rate or left
     * at face value for want of one -- so the UI can mark that balance "≈".
     */
    fun approximateAccounts(entries: List<LedgerEntry>): Set<Long> =
        entries.filter { it.approximate }.mapNotNull { it.accountId }.toSet()

    /** How many entries belong to no account. */
    fun unassignedCount(entries: List<LedgerEntry>): Int = entries.count { it.accountId == null }
}
