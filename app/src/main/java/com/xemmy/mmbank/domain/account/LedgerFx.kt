package com.xemmy.mmbank.domain.account

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Foreign-currency handling for balances (Improvement Plan B1).
 *
 * A charge in a currency other than its account's is converted INTO the
 * account's currency before it reaches a balance, instead of being added at
 * face value (a USD 100 charge used to reduce an AED account by 100).
 *
 * Rates are "quote per 1 base" (the app's [com.xemmy.mmbank.domain.model.ExchangeRate]
 * convention, base = the default currency). A charge with no usable rate is
 * left at face value and flagged [AccountBalanceCalculator.LedgerEntry.approximate],
 * never dropped.
 *
 * Known limit (B2): the rate is TODAY'S, so a converted charge's effect on the
 * balance moves when rates move. A dated rate history fixes that.
 */
class LedgerFx(
    private val baseCurrency: String,
    ratesFromBase: Map<String, BigDecimal>,
    private val accountCurrencies: Map<Long, String>,
) {
    private val rates: Map<String, BigDecimal> =
        ratesFromBase.mapKeys { it.key.uppercase() }.filterValues { it.signum() > 0 }

    /** [from] -> [to] multiplier, or null when either side's rate is unknown. */
    internal fun factor(from: String, to: String): BigDecimal? {
        val f = from.uppercase()
        val t = to.uppercase()
        if (f == t) return BigDecimal.ONE
        val fromRate = rateOf(f) ?: return null
        val toRate = rateOf(t) ?: return null
        return toRate.divide(fromRate, MathContext.DECIMAL64)
    }

    private fun rateOf(code: String): BigDecimal? =
        if (code == baseCurrency.uppercase()) BigDecimal.ONE else rates[code]

    /** Entries in their account's currency; unassigned and same-currency entries pass through. */
    fun apply(entries: List<AccountBalanceCalculator.LedgerEntry>): List<AccountBalanceCalculator.LedgerEntry> =
        entries.map { e ->
            val accountId = e.accountId ?: return@map e
            val target = accountCurrencies[accountId] ?: return@map e
            if (e.currencyCode.equals(target, ignoreCase = true)) return@map e
            val factor = factor(e.currencyCode, target)
                ?: return@map e.copy(approximate = true)
            e.copy(
                amount = e.amount.multiply(factor).setScale(6, RoundingMode.HALF_EVEN),
                currencyCode = target,
                approximate = true,
            )
        }
}
