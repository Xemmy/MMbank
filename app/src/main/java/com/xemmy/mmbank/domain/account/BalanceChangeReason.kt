package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.AccountType

/**
 * Why the user corrected a balance by hand. Stored (by [name]) on the change
 * log entry for the adjustment, so the history can say *why* a balance moved
 * and not just that it did.
 */
enum class BalanceChangeReason(val label: String) {
    MISSED_TRANSACTION("Missed transaction"),
    CASH_COUNT("Cash count"),
    INTEREST_OR_FEES("Interest or fees"),
    MARKET_VALUE("Market value"),
    STATEMENT_CORRECTION("Statement correction"),
    INITIAL_SETUP("Initial setup"),
    OTHER("Other"),
    ;

    companion object {
        /** Null for blank or unknown names (e.g. an entry written before reasons existed). */
        fun parse(name: String?): BalanceChangeReason? = entries.firstOrNull { it.name == name }

        /** What to show for a stored reason: its label, or the raw text if it isn't one of ours. */
        fun labelFor(stored: String?): String? =
            parse(stored)?.label ?: stored?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * Every reason, the likeliest ones for [type] first: a cash wallet is
         * usually a count, an investment a market move, a loan interest.
         */
        fun orderedFor(type: AccountType): List<BalanceChangeReason> {
            val first = when (type) {
                AccountType.CASH -> listOf(CASH_COUNT, MISSED_TRANSACTION)
                AccountType.INVESTMENT, AccountType.CRYPTO_SPOT -> listOf(MARKET_VALUE, MISSED_TRANSACTION)
                AccountType.LOAN -> listOf(INTEREST_OR_FEES, STATEMENT_CORRECTION)
                AccountType.CREDIT_CARD -> listOf(STATEMENT_CORRECTION, INTEREST_OR_FEES, MISSED_TRANSACTION)
                AccountType.CHECKING, AccountType.SAVINGS ->
                    listOf(MISSED_TRANSACTION, STATEMENT_CORRECTION, INTEREST_OR_FEES)
            }
            return (first + entries).distinct()
        }
    }
}
