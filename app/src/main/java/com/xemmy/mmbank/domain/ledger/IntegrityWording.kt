package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.ledger.IntegrityChecker.Issue
import com.xemmy.mmbank.domain.ledger.IntegrityChecker.LinkProblem
import com.xemmy.mmbank.domain.ledger.IntegrityChecker.SetupItem
import com.xemmy.mmbank.domain.ledger.IntegrityChecker.Type
import java.math.BigDecimal

/**
 * What an [Issue] says, in words. Pure and formatter-injected (money and date
 * come from the UI's own formatters), so the sentences a user will read are
 * unit tested instead of living inside a composable.
 */
object IntegrityWording {

    data class Text(val title: String, val detail: String)

    fun describe(
        issue: Issue,
        accountName: String?,
        money: (BigDecimal, String) -> String,
        date: (Long) -> String,
    ): Text {
        val account = accountName ?: "An account"
        val currency = issue.currencyCode.orEmpty()
        return when (issue.type) {
            Type.BALANCE_DRIFT -> {
                val drift = issue.amount ?: BigDecimal.ZERO
                val bankMore = drift.signum() > 0
                Text(
                    title = "$account differs from your bank by ${money(drift.abs(), currency)}",
                    detail = "Your bank said ${money(issue.bankBalance ?: BigDecimal.ZERO, currency)} " +
                        "${issue.asOfMillis?.let { "on ${date(it)} " }.orEmpty()}but MMbank adds up to " +
                        "${money(effectiveApp(issue), currency)}. " +
                        if (bankMore) {
                            "Money in (a deposit, refund or payment) may be missing."
                        } else {
                            "Money out (a purchase, fee or withdrawal) may be missing."
                        },
                )
            }
            Type.UNPAIRED_MOVE -> Text(
                title = "${issue.transactionKind?.label ?: "Transfer"} of " +
                    "${money(issue.amount ?: BigDecimal.ZERO, currency)} has no matching other side",
                detail = "${issue.asOfMillis?.let { "From ${date(it)}. " }.orEmpty()}" +
                    "A message for the other account may be missing, or that account isn't tracked in MMbank. " +
                    "If it really is a purchase, change its type.",
            )
            Type.BROKEN_LINK -> Text(
                title = "A linked pair doesn't add up",
                detail = when (issue.linkProblem) {
                    LinkProblem.PARTNER_MISSING -> "The other side of this transfer was deleted."
                    LinkProblem.NOT_MUTUAL -> "One side points at a transaction that doesn't point back."
                    LinkProblem.AMOUNT_DIFFERS -> "The two sides are in the same currency but different amounts."
                    LinkProblem.SAME_ACCOUNT -> "Both sides are on the same account, so nothing actually moved."
                    null -> "These two transactions are linked inconsistently."
                },
            )
            Type.UNASSIGNED -> Text(
                title = "${issue.count} ${plural(issue.count, "transaction")} " +
                    "${if (issue.count == 1) "is" else "are"} in no account",
                detail = "They count in Spent and Received but in no balance. Assign them to an account.",
            )
            Type.CURRENCY_MISMATCH -> Text(
                title = "$account: ${issue.count} ${plural(issue.count, "transaction")} in another currency",
                detail = "Balances add amounts at face value, so this account can't be checked against your bank.",
            )
            Type.CARD_SETUP -> {
                val missing = issue.setupMissing.joinToString(" and ") {
                    when (it) {
                        SetupItem.CREDIT_LIMIT -> "a credit limit"
                        SetupItem.DUE_DAY -> "a payment due day"
                    }
                }
                Text(
                    title = "$account: add $missing",
                    detail = buildString {
                        if (issue.blocksBankCheck) append("Your bank reports available credit, but it can only be checked once the limit is set. ")
                        if (SetupItem.DUE_DAY in issue.setupMissing) append("The due day is needed for payment reminders.")
                    }.trim(),
                )
            }
        }
    }

    /** The app-side balance the drift was measured against: bank minus drift. */
    private fun effectiveApp(issue: Issue): BigDecimal =
        (issue.bankBalance ?: BigDecimal.ZERO) - (issue.amount ?: BigDecimal.ZERO)

    private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
}
