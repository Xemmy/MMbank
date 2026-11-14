package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The debts a payoff plan starts from, read off the user's own accounts:
 * every credit card or loan that currently owes money. Account already
 * carries what a plan needs -- [Account.profitRatePa], [Account.nextInstallmentAmount],
 * [Account.outstandingBalance] -- and the planner lets the user fill gaps.
 */
object DebtCandidates {

    data class Candidate(
        val accountId: Long,
        val name: String,
        val type: AccountType,
        val currencyCode: String,
        val owed: BigDecimal,
        /** Annual rate in percent; null when the account doesn't say. */
        val aprPercent: Double?,
        val minimumPayment: BigDecimal,
        /** True when [minimumPayment] is our estimate, not the account's installment. */
        val minimumIsEstimate: Boolean,
    )

    /** Cards often ask for ~5% of the balance, with a floor. */
    private val CARD_MIN_RATE = BigDecimal("0.05")
    private val CARD_MIN_FLOOR = BigDecimal("100")

    /**
     * [balances] are the app's signed balances (see [AccountBalanceCalculator]):
     * negative means owed. A loan prefers its stated [Account.outstandingBalance]
     * when set, since a loan's opening balance is often never entered.
     */
    fun build(accounts: List<Account>, balances: Map<Long, BigDecimal>): List<Candidate> =
        accounts.mapNotNull { account ->
            if (account.type != AccountType.CREDIT_CARD && account.type != AccountType.LOAN) return@mapNotNull null
            val fromBalance = (balances[account.id] ?: BigDecimal.ZERO).negate().max(BigDecimal.ZERO)
            val owed = when (account.type) {
                AccountType.LOAN -> account.outstandingBalance?.takeIf { it.signum() > 0 } ?: fromBalance
                else -> fromBalance
            }.setScale(2, RoundingMode.HALF_UP)
            if (owed.signum() <= 0) return@mapNotNull null

            val stated = account.nextInstallmentAmount?.takeIf { it.signum() > 0 }
            Candidate(
                accountId = account.id,
                name = account.name,
                type = account.type,
                currencyCode = account.currencyCode.uppercase(),
                owed = owed,
                aprPercent = account.profitRatePa?.takeIf { it >= 0.0 },
                minimumPayment = (stated ?: estimatedMinimum(owed)).min(owed).setScale(2, RoundingMode.HALF_UP),
                minimumIsEstimate = stated == null,
            )
        }.sortedByDescending { it.owed }

    /** max(5% of [owed], 100), never more than [owed]. */
    fun estimatedMinimum(owed: BigDecimal): BigDecimal =
        (owed * CARD_MIN_RATE).max(CARD_MIN_FLOOR).min(owed).setScale(2, RoundingMode.HALF_UP)

    /** "2 years 3 months", "11 months", "1 year". */
    fun durationLabel(months: Int): String {
        val years = months / 12
        val rest = months % 12
        val y = when (years) {
            0 -> null
            1 -> "1 year"
            else -> "$years years"
        }
        val m = when (rest) {
            0 -> null
            1 -> "1 month"
            else -> "$rest months"
        }
        return listOfNotNull(y, m).joinToString(" ").ifEmpty { "0 months" }
    }
}
