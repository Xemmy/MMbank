package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.AccountChange
import com.xemmy.mmbank.domain.model.AccountChangeSource
import com.xemmy.mmbank.domain.model.AccountChangeType
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal

/**
 * Turns a change to a *transaction* into the [AccountChange] entries of the
 * account(s) it touches -- the half of the story that used to vanish when a
 * balance moved because a row was edited, moved or deleted. Pure, so each
 * balance effect is unit tested.
 *
 * Only edits that can move or re-date a balance are recorded: amount,
 * direction, currency, date and account. Merchant, category, person and the
 * like change nothing about a balance and would only be noise.
 *
 * [AccountChange.balanceDelta] is the row's face-value effect in its own
 * currency, the same arithmetic the balance calculator uses before FX.
 *
 * [nameOf] resolves an account id to its name; an unknown id falls back to
 * "Account <id>" so a snapshot is always readable.
 */
object LedgerChangeDiff {

    private fun signed(tx: Transaction): BigDecimal =
        if (tx.direction == TransactionDirection.CREDIT) tx.amount else tx.amount.negate()

    private fun describe(tx: Transaction): String =
        "${if (tx.direction == TransactionDirection.CREDIT) "+" else "-"}${tx.amount.toPlainString()} ${tx.currencyCode}"

    private fun entry(
        accountId: Long,
        nameOf: (Long) -> String?,
        tx: Transaction,
        nowMillis: Long,
        type: AccountChangeType,
        source: AccountChangeSource,
    ) = AccountChange(
        accountId = accountId,
        accountName = nameOf(accountId) ?: "Account $accountId",
        timestampMillis = nowMillis,
        type = type,
        source = source,
        transactionId = tx.id.takeIf { it != 0L },
        currencyCode = tx.currencyCode,
        isDemo = tx.isDemo,
    )

    /**
     * [old] became [new] (the same row, same id). Same account: one
     * TRANSACTION_EDITED entry listing the changed fields, with the exact
     * balance change. Different account: TRANSACTION_MOVED_OUT on the old one
     * and TRANSACTION_MOVED_IN on the new one, each with its own delta (an
     * amount edited in the same step is captured by using each side's own
     * values). Empty when nothing balance-relevant changed.
     */
    fun edited(
        old: Transaction,
        new: Transaction,
        nameOf: (Long) -> String?,
        nowMillis: Long,
        source: AccountChangeSource = AccountChangeSource.USER,
    ): List<AccountChange> {
        if (old.accountId != new.accountId) {
            return buildList {
                old.accountId?.let { from ->
                    add(
                        entry(from, nameOf, old, nowMillis, AccountChangeType.TRANSACTION_MOVED_OUT, source).copy(
                            field = "account",
                            oldValue = nameOf(from) ?: "Account $from",
                            newValue = new.accountId?.let { nameOf(it) ?: "Account $it" } ?: "No account",
                            balanceDelta = signed(old).negate(),
                            note = describe(old),
                        ),
                    )
                }
                new.accountId?.let { to ->
                    add(
                        entry(to, nameOf, new, nowMillis, AccountChangeType.TRANSACTION_MOVED_IN, source).copy(
                            field = "account",
                            oldValue = old.accountId?.let { nameOf(it) ?: "Account $it" } ?: "No account",
                            newValue = nameOf(to) ?: "Account $to",
                            balanceDelta = signed(new),
                            note = describe(new),
                        ),
                    )
                }
            }
        }

        val accountId = new.accountId ?: return emptyList()
        val changed = buildList {
            if (old.amount.compareTo(new.amount) != 0) add("amount")
            if (old.direction != new.direction) add("direction")
            if (!old.currencyCode.equals(new.currencyCode, ignoreCase = true)) add("currency")
            if (old.timestampMillis != new.timestampMillis) add("date")
        }
        if (changed.isEmpty()) return emptyList()

        val sameCurrency = old.currencyCode.equals(new.currencyCode, ignoreCase = true)
        return listOf(
            entry(accountId, nameOf, new, nowMillis, AccountChangeType.TRANSACTION_EDITED, source).copy(
                field = changed.joinToString(","),
                oldValue = describe(old),
                newValue = describe(new),
                // A currency change has no face-value delta that means anything.
                balanceDelta = if (sameCurrency) signed(new) - signed(old) else null,
                note = new.merchant?.takeIf { it.isNotBlank() },
            ),
        )
    }

    /** [tx] was deleted; its account loses (or regains) what it contributed. Empty for a row in no account. */
    fun deleted(
        tx: Transaction,
        nameOf: (Long) -> String?,
        nowMillis: Long,
        source: AccountChangeSource = AccountChangeSource.USER,
    ): List<AccountChange> {
        val accountId = tx.accountId ?: return emptyList()
        return listOf(
            entry(accountId, nameOf, tx, nowMillis, AccountChangeType.TRANSACTION_DELETED, source).copy(
                oldValue = describe(tx),
                balanceDelta = signed(tx).negate(),
                note = tx.merchant?.takeIf { it.isNotBlank() },
            ),
        )
    }

    /**
     * A "Balance adjustment" row was posted for [tx]'s account. [reason] and
     * [note] come from the adjust dialog; both optional until it asks for them.
     */
    fun adjusted(
        tx: Transaction,
        nameOf: (Long) -> String?,
        nowMillis: Long,
        reason: String? = null,
        note: String? = null,
        source: AccountChangeSource = AccountChangeSource.USER,
    ): List<AccountChange> {
        val accountId = tx.accountId ?: return emptyList()
        return listOf(
            entry(accountId, nameOf, tx, nowMillis, AccountChangeType.BALANCE_ADJUSTED, source).copy(
                balanceDelta = signed(tx),
                reason = reason,
                note = note,
            ),
        )
    }
}
