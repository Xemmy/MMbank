package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal

/**
 * The opening balance that makes a brand-new account's computed balance equal
 * what the bank printed, so adding an account from captured messages starts
 * reconciled instead of showing a drift on day one.
 *
 * Mirrors [com.xemmy.mmbank.domain.ledger.IntegrityChecker]: the computed
 * balance at a message's time is `opening + every row up to that time`, and a
 * card's printed figure is credit still *available*, so what it owes is
 * `min(available - limit, 0)`.
 */
object OpeningBalanceSolver {

    /**
     * Returns the opening balance, or null when it can't be known: no row in
     * [rows] printed a usable balance, or it is a card's available credit and
     * [creditLimit] is unknown.
     *
     * [rows] are the transactions that will be filed into the new account
     * (all one currency). The newest row that printed a balance is the anchor.
     */
    fun solve(type: AccountType, rows: List<Transaction>, creditLimit: BigDecimal? = null): BigDecimal? {
        val isCard = type == AccountType.CREDIT_CARD
        val accepted = if (isCard) {
            setOf(ReportedBalanceKind.AVAILABLE_CREDIT)
        } else {
            setOf(ReportedBalanceKind.ACCOUNT_BALANCE, ReportedBalanceKind.AVAILABLE_CREDIT)
        }
        val anchor = rows
            .filter { it.reportedBalance != null && it.reportedBalanceKind in accepted }
            .maxWithOrNull(compareBy<Transaction>({ it.timestampMillis }, { it.id }))
            ?: return null

        val reported = anchor.reportedBalance!!
        val target = if (isCard) {
            val limit = creditLimit?.takeIf { it.signum() > 0 } ?: return null
            (reported - limit).min(BigDecimal.ZERO)
        } else {
            reported
        }
        val movement = rows
            .filter { it.timestampMillis <= anchor.timestampMillis }
            .fold(BigDecimal.ZERO) { acc, tx ->
                if (tx.direction == TransactionDirection.CREDIT) acc + tx.amount else acc - tx.amount
            }
        return target - movement
    }
}
