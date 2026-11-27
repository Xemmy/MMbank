package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.account.AccountBalanceCalculator.LedgerEntry
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal

/**
 * Keeps a retention prune balance-neutral.
 *
 * Balances are derived -- `balance = openingBalance + (credits - debits)` (see
 * [AccountBalanceCalculator]) -- so deleting old transactions silently moves
 * every balance they contributed to. That is what the opt-in retention prune
 * did: turn it on and your balances drift by the net of whatever window got
 * pruned, with nothing on screen to explain it.
 *
 * The fix is arithmetic, not policy. If the rows being deleted contribute a net
 * of `n`, the balance after deletion would be `opening + total - n`. Folding
 * `n` into the opening balance -- `newOpening = opening + n` -- restores it
 * exactly. The user sees the same balance before and after; only the history
 * behind it is shorter.
 *
 * Pure and `BigDecimal`, so the guarantee is provable in a JVM test rather than
 * discovered on a device. [com.xemmy.mmbank.data.retention.RetentionPruner]
 * applies the result, and folds and deletes inside one Room transaction so a
 * failure can never leave the two halves out of step.
 */
object PrunedBalanceFolder {

    /**
     * One account's opening-balance correction. [foldedNet] is the net
     * contribution of the rows being deleted -- exactly what the balance would
     * otherwise have moved by.
     */
    data class Adjustment(
        val accountId: Long,
        val previousOpeningBalance: BigDecimal,
        val newOpeningBalance: BigDecimal,
        val foldedNet: BigDecimal,
    )

    /**
     * Opening-balance adjustments that hold every balance in [accounts] steady
     * across the deletion of [prunedEntries].
     *
     * Entries with a null `accountId` are ignored: they were in no balance, so
     * deleting them moves nothing. Accounts with no pruned rows produce no
     * adjustment rather than a zero one, so the caller writes only the rows it
     * genuinely needs to.
     *
     * Currency is not converted, matching [AccountBalanceCalculator]'s own
     * face-value summing -- the fold has to use the same arithmetic as the
     * balance it is protecting, or it would not protect it.
     */
    fun fold(accounts: List<Account>, prunedEntries: List<LedgerEntry>): List<Adjustment> {
        if (accounts.isEmpty() || prunedEntries.isEmpty()) return emptyList()

        val netByAccount = LinkedHashMap<Long, BigDecimal>()
        for (entry in prunedEntries) {
            val accountId = entry.accountId ?: continue
            val signed =
                if (entry.direction == TransactionDirection.CREDIT) entry.amount else entry.amount.negate()
            netByAccount[accountId] = (netByAccount[accountId] ?: BigDecimal.ZERO) + signed
        }

        return accounts.mapNotNull { account ->
            val net = netByAccount[account.id] ?: return@mapNotNull null
            Adjustment(
                accountId = account.id,
                previousOpeningBalance = account.openingBalance,
                newOpeningBalance = account.openingBalance + net,
                foldedNet = net,
            )
        }
    }
}
