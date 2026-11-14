package com.xemmy.mmbank.domain.model

import java.math.BigDecimal

/** What happened to an account -- the "why" half of the account history. See [AccountChange]. */
enum class AccountChangeType {
    CREATED,
    DETAILS_EDITED,
    OPENING_BALANCE_CHANGED,
    BALANCE_ADJUSTED,
    ARCHIVED,
    RESTORED,
    DELETED,
    TRANSACTION_EDITED,
    TRANSACTION_MOVED_IN,
    TRANSACTION_MOVED_OUT,
    TRANSACTION_DELETED,
    RETENTION_FOLDED,
    BACKUP_RESTORED,
    ;

    /** True for the types that can move the balance on screen (the timeline's "Balance changes" filter). */
    val canMoveBalance: Boolean
        get() = when (this) {
            CREATED, OPENING_BALANCE_CHANGED, BALANCE_ADJUSTED, DELETED,
            TRANSACTION_EDITED, TRANSACTION_MOVED_IN, TRANSACTION_MOVED_OUT, TRANSACTION_DELETED -> true
            DETAILS_EDITED, ARCHIVED, RESTORED, RETENTION_FOLDED, BACKUP_RESTORED -> false
        }

    companion object {
        /** Lenient parse for persisted/backed-up names; unknown -> [DETAILS_EDITED], the most neutral type. */
        fun parse(name: String?): AccountChangeType = entries.firstOrNull { it.name == name } ?: DETAILS_EDITED
    }
}

/** Who or what made the change. */
enum class AccountChangeSource {
    USER,
    CAPTURE_SUGGESTION,
    RETENTION,
    BACKUP_RESTORE,

    /** The app shifted an opening balance so filing old transactions did not move a corrected balance. */
    HISTORY_FILING,
    ;

    companion object {
        fun parse(name: String?): AccountChangeSource = entries.firstOrNull { it.name == name } ?: USER
    }
}

/**
 * One entry in an account's change log: something that moved the account's
 * balance or changed how it is described, with enough to explain it later.
 *
 * Balances are derived (`openingBalance + credits - debits`), so most things
 * that move one -- editing or deleting a transaction, moving it to another
 * account, a retention clean-up, a restore -- used to leave no trace. This is
 * that trace. Ordinary *new* transactions are deliberately not logged: they are
 * the ledger itself, and the history screen merges them in.
 *
 * [accountName] is a snapshot, and there is no foreign key to the account, so
 * the log outlives a deleted account.
 *
 * [balanceDelta] is the exact effect on the account's balance, in
 * [currencyCode], computed from the change itself (face value, no FX). Null
 * when the change has no balance effect or it can't be stated (e.g. a
 * transaction whose currency was changed).
 */
data class AccountChange(
    val id: Long = 0,
    val accountId: Long,
    val accountName: String,
    val timestampMillis: Long,
    val type: AccountChangeType,
    val source: AccountChangeSource = AccountChangeSource.USER,
    /** Which field changed, for edits ("name", "openingBalance", "amount,direction", "account"). */
    val field: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    val balanceDelta: BigDecimal? = null,
    val currencyCode: String? = null,
    val transactionId: Long? = null,
    /** Why a balance was adjusted; set by the balance-adjust dialog. */
    val reason: String? = null,
    val note: String? = null,
    val isDemo: Boolean = false,
)
