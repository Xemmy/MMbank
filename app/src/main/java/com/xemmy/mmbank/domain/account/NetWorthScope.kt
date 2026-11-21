package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account

/**
 * The one rule for which accounts count toward net worth: every account unless the user switched
 * "Count in net worth" off. An excluded account keeps its balance, history, debts and reminders;
 * only the net-worth sums (Home, the Insights trend, the monthly report, financial health) leave
 * it out. Pure so every screen applies the same rule.
 */
object NetWorthScope {

    fun counts(account: Account): Boolean = !account.excludeFromTotals

    fun filter(accounts: List<Account>): List<Account> = accounts.filter(::counts)

    /** True when [accounts] holds at least one account left out of net worth (to show a notice). */
    fun anyExcluded(accounts: List<Account>): Boolean = accounts.any { !counts(it) }
}
