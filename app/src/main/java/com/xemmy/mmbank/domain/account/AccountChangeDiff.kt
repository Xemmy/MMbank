package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountChange
import com.xemmy.mmbank.domain.model.AccountChangeSource
import com.xemmy.mmbank.domain.model.AccountChangeType
import java.math.BigDecimal

/**
 * Turns what happened to an *account row* into [AccountChange] log entries.
 * Pure, so which edits are worth recording -- and how each one reads -- is
 * unit tested rather than inferred from repository code.
 *
 * Recorded: the name, type, currency, identifiers (account number, card
 * endings, IBAN, SWIFT, bank name, title), credit limit, statement and due
 * days, the loan figures, notes, archive state, and above all the opening
 * balance. Not recorded: display-only settings (accent colour, hidden from the
 * dashboard) and bookkeeping (id, createdAt, isDemo), which explain nothing
 * about a balance.
 */
object AccountChangeDiff {

    /** Long free text is cut so one rambling note cannot bloat the log. */
    internal const val MAX_VALUE_LENGTH = 200

    private class Tracked(val name: String, val read: (Account) -> String?)

    private fun money(value: BigDecimal?): String? = value?.let {
        // Numerically equal amounts must compare equal: "100" and "100.00" are not an edit.
        if (it.signum() == 0) "0" else it.stripTrailingZeros().toPlainString()
    }

    private fun text(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_VALUE_LENGTH)

    private val tracked = listOf(
        Tracked("name") { text(it.name) },
        Tracked("type") { it.type.name },
        Tracked("currency") { text(it.currencyCode)?.uppercase() },
        Tracked("accountNumber") { text(it.accountNumber) },
        Tracked("cardEndings") { text(it.cardEndings) },
        Tracked("bankName") { text(it.bankName) },
        Tracked("accountTitle") { text(it.accountTitle) },
        Tracked("iban") { text(it.iban) },
        Tracked("swift") { text(it.swift) },
        Tracked("creditLimit") { money(it.creditLimit) },
        Tracked("statementDay") { it.statementCycleDay?.toString() },
        Tracked("dueDay") { it.dueDayOfMonth?.toString() },
        Tracked("outstandingBalance") { money(it.outstandingBalance) },
        Tracked("nextInstallment") { money(it.nextInstallmentAmount) },
        Tracked("profitRate") { it.profitRatePa?.toString() },
        Tracked("maturity") { it.maturityDateMillis?.toString() },
        Tracked("notes") { text(it.notes) },
        Tracked("loanNotes") { text(it.loanNotes) },
    )

    /** A brand-new account; the opening balance is where its balance starts. */
    fun created(account: Account, nowMillis: Long, source: AccountChangeSource = AccountChangeSource.USER): AccountChange =
        base(account, nowMillis, AccountChangeType.CREATED, source).copy(
            field = "openingBalance",
            newValue = money(account.openingBalance),
            balanceDelta = account.openingBalance,
            currencyCode = account.currencyCode,
        )

    fun archived(account: Account, nowMillis: Long, source: AccountChangeSource = AccountChangeSource.USER): AccountChange =
        base(account, nowMillis, AccountChangeType.ARCHIVED, source)

    fun restored(account: Account, nowMillis: Long, source: AccountChangeSource = AccountChangeSource.USER): AccountChange =
        base(account, nowMillis, AccountChangeType.RESTORED, source)

    /** The account is gone; its history stays. */
    fun deleted(account: Account, nowMillis: Long, source: AccountChangeSource = AccountChangeSource.USER): AccountChange =
        base(account, nowMillis, AccountChangeType.DELETED, source).copy(
            oldValue = text(account.name),
            currencyCode = account.currencyCode,
        )

    /**
     * A retention clean-up folded deleted rows into the opening balance.
     * [balanceDelta] is zero on purpose: folding exists so the balance on
     * screen does *not* move; only the opening balance underneath it does.
     */
    fun retentionFolded(
        account: Account,
        adjustment: PrunedBalanceFolder.Adjustment,
        nowMillis: Long,
    ): AccountChange =
        base(account, nowMillis, AccountChangeType.RETENTION_FOLDED, AccountChangeSource.RETENTION).copy(
            field = "openingBalance",
            oldValue = money(adjustment.previousOpeningBalance),
            newValue = money(adjustment.newOpeningBalance),
            balanceDelta = BigDecimal.ZERO,
            currencyCode = account.currencyCode,
            note = "Folded the net of old transactions: ${money(adjustment.foldedNet)}",
        )

    /**
     * Old transactions were filed into (or moved out of) the account and the
     * opening balance was shifted so the balance on screen stayed put (see
     * [BalanceNeutralFiling]). [balanceDelta] is the shift itself: it cancels the
     * deltas of the TRANSACTION_MOVED_IN / _OUT entries written beside it.
     */
    fun historyFiled(account: Account, kept: BalanceNeutralFiling.Kept, nowMillis: Long): AccountChange =
        base(account, nowMillis, AccountChangeType.OPENING_BALANCE_CHANGED, AccountChangeSource.HISTORY_FILING).copy(
            field = "openingBalance",
            oldValue = money(kept.previousOpeningBalance),
            newValue = money(kept.newOpeningBalance),
            balanceDelta = kept.newOpeningBalance - kept.previousOpeningBalance,
            currencyCode = account.currencyCode,
            note = BalanceNeutralFiling.summary(kept.rowCount),
        )

    /** A backup replaced the data; one marker per restored account. */
    fun backupRestored(account: Account, nowMillis: Long): AccountChange =
        base(account, nowMillis, AccountChangeType.BACKUP_RESTORED, AccountChangeSource.BACKUP_RESTORE).copy(
            field = "openingBalance",
            newValue = money(account.openingBalance),
            currencyCode = account.currencyCode,
        )

    /**
     * What changed between [old] and [new]: one entry per changed field, in a
     * stable order. An opening-balance change is its own type with an exact
     * [AccountChange.balanceDelta]; an archive flip is ARCHIVED / RESTORED;
     * everything else is DETAILS_EDITED. Empty when nothing worth recording changed.
     */
    fun diff(
        old: Account,
        new: Account,
        nowMillis: Long,
        source: AccountChangeSource = AccountChangeSource.USER,
    ): List<AccountChange> = buildList {
        if (old.openingBalance.compareTo(new.openingBalance) != 0) {
            add(
                base(new, nowMillis, AccountChangeType.OPENING_BALANCE_CHANGED, source).copy(
                    field = "openingBalance",
                    oldValue = money(old.openingBalance),
                    newValue = money(new.openingBalance),
                    balanceDelta = new.openingBalance - old.openingBalance,
                    currencyCode = new.currencyCode,
                ),
            )
        }
        for (field in tracked) {
            val before = field.read(old)
            val after = field.read(new)
            if (before != after) {
                add(
                    base(new, nowMillis, AccountChangeType.DETAILS_EDITED, source).copy(
                        field = field.name,
                        oldValue = before,
                        newValue = after,
                    ),
                )
            }
        }
        if (old.archived != new.archived) {
            add(if (new.archived) archived(new, nowMillis, source) else restored(new, nowMillis, source))
        }
    }

    private fun base(account: Account, nowMillis: Long, type: AccountChangeType, source: AccountChangeSource) =
        AccountChange(
            accountId = account.id,
            accountName = account.name,
            timestampMillis = nowMillis,
            type = type,
            source = source,
            isDemo = account.isDemo,
        )
}
