package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.AccountChange
import com.xemmy.mmbank.domain.model.AccountChangeSource
import com.xemmy.mmbank.domain.model.AccountChangeType

/**
 * Plain-language wording for an [AccountChange], kept pure so each sentence is
 * unit tested. The [Line.headline] never contains an amount; anything that
 * does ([Line.from], [Line.to], [Line.extra]) is flagged by [Line.sensitive]
 * so the screen can mask it when balances are hidden.
 */
object AccountChangeWording {

    /**
     * @param sensitive true when [from], [to] or [extra] carry amounts: mask all three while balances are hidden.
     * @param by who made the change, as a short phrase ("you", "retention clean-up").
     */
    data class Line(
        val headline: String,
        val from: String? = null,
        val to: String? = null,
        val extra: String? = null,
        val sensitive: Boolean = false,
        val by: String = "you",
    )

    private val moneyFields = setOf("openingBalance", "creditLimit", "outstandingBalance", "nextInstallment")

    private val fieldLabels = mapOf(
        "name" to "Name",
        "type" to "Type",
        "currency" to "Currency",
        "accountNumber" to "Account number",
        "cardEndings" to "Card endings",
        "bankName" to "Bank",
        "accountTitle" to "Account title",
        "iban" to "IBAN",
        "swift" to "SWIFT",
        "creditLimit" to "Credit limit",
        "statementDay" to "Statement day",
        "dueDay" to "Due day",
        "outstandingBalance" to "Outstanding balance",
        "nextInstallment" to "Next installment",
        "profitRate" to "Profit rate",
        "maturity" to "Maturity date",
        "notes" to "Notes",
        "loanNotes" to "Loan notes",
        "openingBalance" to "Opening balance",
    )

    private val rowFieldWords = mapOf(
        "amount" to "amount",
        "direction" to "direction",
        "currency" to "currency",
        "date" to "date",
    )

    fun fieldLabel(field: String?): String = fieldLabels[field] ?: "Details"

    fun by(source: AccountChangeSource): String = when (source) {
        AccountChangeSource.USER -> "you"
        AccountChangeSource.CAPTURE_SUGGESTION -> "a suggestion from your messages"
        AccountChangeSource.RETENTION -> "retention clean-up"
        AccountChangeSource.BACKUP_RESTORE -> "a backup restore"
        AccountChangeSource.HISTORY_FILING -> "MMbank, to keep your balance"
    }

    /** "amount", "amount and date", "amount, direction and date". */
    internal fun joinWords(words: List<String>): String = when (words.size) {
        0 -> ""
        1 -> words[0]
        else -> words.dropLast(1).joinToString(", ") + " and " + words.last()
    }

    /** [count] > 1 describes a bulk move or delete collapsed into one line (see [AccountTimeline]). */
    fun line(change: AccountChange, count: Int = 1): Line {
        val who = by(change.source)
        val merchant = change.note?.takeIf { it.isNotBlank() }
        return when (change.type) {
            AccountChangeType.CREATED ->
                Line("Account created", to = change.newValue, extra = "Opening balance", sensitive = true, by = who)

            AccountChangeType.DETAILS_EDITED -> {
                val money = change.field in moneyFields
                // A date stored as epoch millis reads as noise; the headline says it changed.
                val showValues = change.field != "maturity"
                Line(
                    "${fieldLabel(change.field)} changed",
                    from = change.oldValue.takeIf { showValues },
                    to = change.newValue.takeIf { showValues },
                    sensitive = money,
                    by = who,
                )
            }

            AccountChangeType.OPENING_BALANCE_CHANGED ->
                if (change.source == AccountChangeSource.HISTORY_FILING) {
                    // The balance did not move; the opening balance underneath it did. Lead with the reason.
                    Line(
                        merchant ?: "Older transactions filed, balance kept",
                        from = change.oldValue,
                        to = change.newValue,
                        extra = "Opening balance",
                        sensitive = true,
                        by = who,
                    )
                } else {
                    Line("Opening balance changed", from = change.oldValue, to = change.newValue, sensitive = true, by = who)
                }

            AccountChangeType.BALANCE_ADJUSTED -> Line(
                "Balance adjusted",
                extra = listOfNotNull(BalanceChangeReason.labelFor(change.reason), change.note?.trim()?.takeIf { it.isNotEmpty() })
                    .distinct()
                    .joinToString(" — ")
                    .ifEmpty { null },
                by = who,
            )

            AccountChangeType.ARCHIVED -> Line("Account archived", by = who)
            AccountChangeType.RESTORED -> Line("Account restored", by = who)
            AccountChangeType.DELETED -> Line("Account deleted", from = change.oldValue, by = who)

            AccountChangeType.TRANSACTION_EDITED -> {
                val words = change.field.orEmpty().split(',').mapNotNull { rowFieldWords[it.trim()] }
                Line(
                    if (words.isEmpty()) "Transaction edited" else "Transaction edited: ${joinWords(words)}",
                    from = change.oldValue,
                    to = change.newValue,
                    extra = merchant,
                    sensitive = true,
                    by = who,
                )
            }

            AccountChangeType.TRANSACTION_MOVED_IN -> {
                val from = change.oldValue?.takeIf { it.isNotBlank() } ?: "another account"
                Line(
                    if (count > 1) "$count transactions moved in from $from" else "Transaction moved in from $from",
                    extra = change.note.takeIf { count == 1 },
                    sensitive = count == 1,
                    by = who,
                )
            }

            AccountChangeType.TRANSACTION_MOVED_OUT -> {
                val to = change.newValue?.takeIf { it.isNotBlank() } ?: "another account"
                Line(
                    if (count > 1) "$count transactions moved out to $to" else "Transaction moved out to $to",
                    extra = change.note.takeIf { count == 1 },
                    sensitive = count == 1,
                    by = who,
                )
            }

            AccountChangeType.TRANSACTION_DELETED -> Line(
                if (count > 1) "$count transactions deleted" else "Transaction deleted",
                from = change.oldValue.takeIf { count == 1 },
                extra = merchant.takeIf { count == 1 },
                sensitive = count == 1,
                by = who,
            )

            AccountChangeType.RETENTION_FOLDED -> Line(
                "Old transactions folded into the opening balance",
                from = change.oldValue,
                to = change.newValue,
                sensitive = true,
                by = who,
            )

            AccountChangeType.BACKUP_RESTORED ->
                Line("Restored from a backup", to = change.newValue, extra = "Opening balance", sensitive = true, by = who)
        }
    }
}
