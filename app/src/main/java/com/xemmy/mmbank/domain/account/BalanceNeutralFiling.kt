package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal

/**
 * Keeps a corrected balance where it is when *old* transactions are filed.
 *
 * Balances are derived -- `openingBalance + (credits - debits)` (see
 * [AccountBalanceCalculator]). "Update balance" posts one adjustment row dated
 * now, so the balance is right as of that moment, and every transaction dated
 * before it is already inside the corrected figure whether or not it is filed
 * in the account. Filing such a row afterwards used to count it a second time:
 * the balance walked away from the bank's with each old row sorted.
 *
 * The fix is the same arithmetic as [PrunedBalanceFolder], in the other
 * direction: a row dated before the account's [cutoffFor] that enters the
 * account shifts the opening balance by the opposite of what it contributes,
 * and one that leaves shifts it back. Today's balance is unchanged; only the
 * history behind it gets longer (or shorter).
 *
 * Rows dated on or after the cutoff are left alone: they happened after the
 * balance was last known to be right, so they must move it.
 *
 * Only rows in the account's own currency are kept neutral. A foreign-currency
 * row reaches the balance through today's exchange rate ([LedgerFx]), which
 * moves; an opening-balance shift frozen at one rate would not hold the balance
 * steady, so those rows keep moving it and are reported in [Kept.skippedForeign].
 *
 * Pure and `BigDecimal`, so the guarantee is proven in a JVM test.
 */
object BalanceNeutralFiling {

    /** One account's opening-balance correction for a filing. */
    data class Kept(
        val accountId: Long,
        val previousOpeningBalance: BigDecimal,
        val newOpeningBalance: BigDecimal,
        /** How many old rows entered or left the account without moving its balance. */
        val rowCount: Int,
        /** Old rows in another currency, which still moved the balance. */
        val skippedForeign: Int = 0,
    )

    /**
     * The moment [account]'s balance was last known to be right: its newest
     * balance adjustment, or its creation when it never had one.
     */
    fun cutoffFor(account: Account, lastAdjustmentAtMillis: Long?): Long =
        maxOf(account.createdAt, lastAdjustmentAtMillis ?: Long.MIN_VALUE)

    private fun signed(tx: Transaction): BigDecimal =
        if (tx.direction == TransactionDirection.CREDIT) tx.amount else tx.amount.negate()

    private class Tally(var shift: BigDecimal = BigDecimal.ZERO, var rows: Int = 0, var foreign: Int = 0)

    /**
     * Opening-balance corrections for [edits], each a (before, after) pair of
     * the same row. Only pairs whose account changed matter. Accounts missing
     * from [accounts] and accounts with nothing old to keep are absent.
     *
     * @param lastAdjustmentAt newest balance-adjustment time per account id; absent = never adjusted.
     */
    fun offsets(
        edits: List<Pair<Transaction, Transaction>>,
        accounts: Map<Long, Account>,
        lastAdjustmentAt: Map<Long, Long>,
    ): List<Kept> {
        val tallies = LinkedHashMap<Long, Tally>()

        fun visit(tx: Transaction, entering: Boolean) {
            val account = accounts[tx.accountId ?: return] ?: return
            if (tx.timestampMillis >= cutoffFor(account, lastAdjustmentAt[account.id])) return
            val tally = tallies.getOrPut(account.id) { Tally() }
            if (!tx.currencyCode.equals(account.currencyCode, ignoreCase = true)) {
                tally.foreign++
                return
            }
            // Entering adds signed(tx) to the balance, so the opening balance gives it back; leaving is the mirror.
            tally.shift += if (entering) signed(tx).negate() else signed(tx)
            tally.rows++
        }

        for ((old, new) in edits) {
            if (old.accountId == new.accountId) continue
            visit(old, entering = false)
            visit(new, entering = true)
        }

        return tallies.mapNotNull { (accountId, tally) ->
            if (tally.rows == 0 && tally.foreign == 0) return@mapNotNull null
            val account = accounts.getValue(accountId)
            Kept(
                accountId = accountId,
                previousOpeningBalance = account.openingBalance,
                newOpeningBalance = account.openingBalance + tally.shift,
                rowCount = tally.rows,
                skippedForeign = tally.foreign,
            )
        }
    }

    /** "12 older transactions filed, balance kept" -- the history line and the on-screen notice share it. */
    fun summary(rowCount: Int): String =
        if (rowCount == 1) "1 older transaction filed, balance kept" else "$rowCount older transactions filed, balance kept"

    /** The notice shown after a filing; null when nothing was kept. */
    fun notice(kept: List<Kept>, nameOf: (Long) -> String?): String? {
        val held = kept.filter { it.rowCount > 0 }
        if (held.isEmpty()) return null
        val total = held.sumOf { it.rowCount }
        val where = held.singleOrNull()?.let { nameOf(it.accountId) }
        val rows = if (total == 1) "1 older transaction" else "$total older transactions"
        return if (where != null) {
            "Filed $rows. ${where}'s balance was kept."
        } else {
            "Filed $rows. Balances were kept."
        }
    }
}
