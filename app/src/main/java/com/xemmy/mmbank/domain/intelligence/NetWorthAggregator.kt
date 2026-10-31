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
     * net-worth figure. Balances are signed the way
     * [com.xemmy.mmbank.domain.account.AccountBalanceCalculator] computes
     * them: a credit card or loan you owe on is negative.
     *
     * A liability account that's in credit -- a card overpaid, or refunded
     * after being paid off -- is money the bank owes you, so its positive
     * balance counts as an asset. (This used to take the magnitude, which
     * turned an overpaid card into debt.)
     */
    fun aggregate(balances: List<AccountBalance>): NetWorthBreakdown {
        val liabilities = balances.filter { it.type in CREDIT_TYPES }
        val debitTotal = balances.filter { it.type in DEBIT_TYPES }.sumOf { it.balance } +
            liabilities.sumOf { it.balance.max(BigDecimal.ZERO) }
        val creditTotal = liabilities.sumOf { it.balance.negate().max(BigDecimal.ZERO) }
        val perAccount = balances.associate { it.accountId to it.balance }
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
