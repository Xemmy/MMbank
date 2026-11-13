package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import com.xemmy.mmbank.domain.model.Transaction
import java.math.BigDecimal

/**
 * "5 messages from card ••4543 -- add it?" Groups captured transactions that
 * belong to no account by the account/card ending their message revealed, when
 * that ending matches none of the user's accounts. Pure, so the grouping and
 * the type guess are unit tested.
 *
 * Grouped per (ending, currency): a backfill puts every row of a group into one
 * account, and mixing currencies there would silently corrupt its balance.
 */
object UnknownAccountSuggestions {

    data class Suggestion(
        /** Digits only, at least [AccountNumberMatcher.MIN_DIGITS] long. */
        val digits: String,
        val currencyCode: String,
        val transactionIds: List<Long>,
        /** Distinct sender labels, most frequent first. */
        val senders: List<String>,
        val suggestedType: AccountType,
        /** The newest balance a message of this group printed, if any. */
        val latestReportedBalance: BigDecimal?,
        val latestReportedKind: ReportedBalanceKind?,
        val latestReportedAtMillis: Long?,
        val latestAtMillis: Long,
    ) {
        val count: Int get() = transactionIds.size

        /** What the user is shown as the name of the thing: "card ••4543". */
        val label: String
            get() = (if (suggestedType == AccountType.CREDIT_CARD) "card" else "account") + " ••" + digits.takeLast(4)
    }

    fun suggest(transactions: List<Transaction>, accounts: List<Account>): List<Suggestion> {
        val known = accounts.flatMap {
            AccountNumberMatcher.numbersOf(
                AccountNumberMatcher.Candidate(it.id, it.accountNumber, it.cardEndings, it.currencyCode),
            )
        }.distinct()

        return transactions
            .asSequence()
            .filter { it.accountId == null }
            .mapNotNull { tx ->
                val digits = tx.accountNumberSuffix?.filter(Char::isDigit).orEmpty()
                if (digits.length < AccountNumberMatcher.MIN_DIGITS) return@mapNotNull null
                // An ending that overlaps any account is not unknown: the matcher either already
                // files it there or refused on a tie, which is a different problem to solve.
                if (known.any { digits.endsWith(it) || it.endsWith(digits) }) return@mapNotNull null
                Triple(digits, tx.currencyCode.uppercase(), tx)
            }
            .groupBy({ it.first to it.second }, { it.third })
            .map { (key, rows) -> build(key.first, key.second, rows) }
            .sortedWith(compareByDescending<Suggestion> { it.count }.thenByDescending { it.latestAtMillis })
    }

    private fun build(digits: String, currency: String, rows: List<Transaction>): Suggestion {
        val newestFirst = rows.sortedWith(compareByDescending<Transaction> { it.timestampMillis }.thenByDescending { it.id })
        val withBalance = newestFirst.firstOrNull { it.reportedBalance != null }
        val kind = withBalance?.reportedBalanceKind
        return Suggestion(
            digits = digits,
            currencyCode = currency,
            transactionIds = rows.map { it.id },
            senders = rows.mapNotNull { it.sender?.trim()?.takeIf(String::isNotEmpty) }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key },
            // A printed "available credit" only ever comes from a card.
            suggestedType = if (kind == ReportedBalanceKind.AVAILABLE_CREDIT) AccountType.CREDIT_CARD else AccountType.CHECKING,
            latestReportedBalance = withBalance?.reportedBalance,
            latestReportedKind = kind,
            latestReportedAtMillis = withBalance?.timestampMillis,
            latestAtMillis = newestFirst.first().timestampMillis,
        )
    }

    /**
     * The new account for "Add as new account": carries the ending as its account
     * number and an opening balance that reconciles it with the bank's printed
     * balance (see [OpeningBalanceSolver]); zero when no message printed one.
     */
    fun newAccountFor(
        suggestion: Suggestion,
        rows: List<Transaction>,
        name: String,
        type: AccountType,
        creditLimit: BigDecimal? = null,
    ): Account = Account(
        name = name.trim(),
        type = type,
        currencyCode = suggestion.currencyCode,
        accountNumber = suggestion.digits,
        creditLimit = if (type == AccountType.CREDIT_CARD) creditLimit else null,
        openingBalance = OpeningBalanceSolver.solve(type, rows, creditLimit) ?: BigDecimal.ZERO,
    )

    /** "It's my existing account": [existing] with the ending added to its card endings, nothing else touched. */
    fun linkTo(existing: Account, suggestion: Suggestion): Account =
        AccountMerge.merge(
            existing,
            AccountUpsertResolver.Draft(
                type = existing.type,
                name = existing.name,
                currencyCode = existing.currencyCode,
                cardEndings = suggestion.digits,
            ),
        ).account
}
