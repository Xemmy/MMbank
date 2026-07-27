package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Transaction
import java.math.BigDecimal

/**
 * Splits accounts into debit (asset) vs credit (liability) groups by
 * [AccountType] and rolls each group's balance into a net-worth figure. Pure
 * and top-level so it's JVM-testable; the Dashboard already computes each
 * account's balance (opening balance + net movement) — this only aggregates
 * balances that are already known, one per account.
 */
object NetWorthAggregator {

    /** Asset-side account types: their balance adds to net worth. */
    private val DEBIT_TYPES = setOf(
        AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH,
        AccountType.CRYPTO_SPOT, AccountType.INVESTMENT,
    )

    /** Liability-side account types: what's owed subtracts from net worth. */
    private val CREDIT_TYPES = setOf(AccountType.CREDIT_CARD, AccountType.LOAN)

    /** One account's id, [AccountType] and current balance, ready to aggregate. */
    data class AccountBalance(val accountId: Long, val type: AccountType, val balance: BigDecimal)

    /**
     * [debitTotal] and [creditTotal] are both non-negative magnitudes;
     * [netWorth] = debitTotal - creditTotal, per the debit-vs-credit split.
     */
    data class NetWorthBreakdown(
        val debitTotal: BigDecimal,
        val creditTotal: BigDecimal,
        val netWorth: BigDecimal,
        /** Per-account contribution, signed: positive for debit accounts, negative for what a credit account owes. */
        val perAccount: Map<Long, BigDecimal>,
    )

    /**
     * Aggregates every account's balance into asset/liability totals and a
     * net-worth figure. A credit/loan account's [AccountBalance.balance] is
     * taken as a magnitude owed (its sign is not assumed) so callers that
     * track credit balances as negative numbers or as positive debt figures
     * both aggregate correctly.
     */
    fun aggregate(balances: List<AccountBalance>): NetWorthBreakdown {
        val debitTotal = balances.filter { it.type in DEBIT_TYPES }.sumOf { it.balance }
        val creditTotal = balances.filter { it.type in CREDIT_TYPES }.sumOf { it.balance.abs() }
        val perAccount = balances.associate { account ->
            val signed = if (account.type in CREDIT_TYPES) -account.balance.abs() else account.balance
            account.accountId to signed
        }
        return NetWorthBreakdown(
            debitTotal = debitTotal,
            creditTotal = creditTotal,
            netWorth = debitTotal - creditTotal,
            perAccount = perAccount,
        )
    }

    /** Transactions belonging to [accountId], or every transaction when it's null ("All accounts"). */
    fun filterByAccount(transactions: List<Transaction>, accountId: Long?): List<Transaction> =
        if (accountId == null) transactions else transactions.filter { it.accountId == accountId }
}
