package com.xemmy.mmbank.data.repository

import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.local.entity.toEntity
import com.xemmy.mmbank.domain.account.AccountChangeDiff
import com.xemmy.mmbank.domain.account.BalanceNeutralFiling
import com.xemmy.mmbank.domain.ledger.LedgerChangeDiff
import com.xemmy.mmbank.domain.model.AccountChange
import com.xemmy.mmbank.domain.model.AccountChangeSource
import com.xemmy.mmbank.domain.model.Transaction

/**
 * Writes the account change log for edits made to *transactions*: the shared
 * seam for [TransactionRepositoryImpl] and [MerchantAccountRepositoryImpl],
 * which both move rows between accounts. What each change reads like lives in
 * the pure [LedgerChangeDiff]; this only resolves account names and inserts.
 *
 * Call it inside the same Room transaction as the change it describes, so the
 * log can never claim something that was rolled back (or miss something that
 * wasn't).
 */
internal class AccountChangeRecorder(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Told, in plain words, when a filing left a balance where it was (see [keepBalances]). */
    private val onBalanceKept: (String) -> Unit = {},
) {

    suspend fun record(changes: List<AccountChange>) {
        if (changes.isNotEmpty()) database.accountChangeDao().insertAll(changes.map { it.toEntity() })
    }

    /**
     * Edited / moved rows: each pair is (before, after). Pairs with no balance-relevant difference log nothing.
     *
     * [keepBalance] (the default) also holds every corrected balance steady when a
     * row dated before its account's last balance update moves in or out -- see
     * [BalanceNeutralFiling]. Pass false only where the caller has already
     * worked out an opening balance that *expects* the rows to count (a brand-new
     * account reconciled by `OpeningBalanceSolver`).
     */
    suspend fun recordEdits(
        edits: List<Pair<Transaction, Transaction>>,
        source: AccountChangeSource = AccountChangeSource.USER,
        keepBalance: Boolean = true,
    ) {
        if (edits.isEmpty()) return
        val names = namesOf(edits.flatMap { listOf(it.first.accountId, it.second.accountId) })
        val now = clock()
        record(edits.flatMap { (old, new) -> LedgerChangeDiff.edited(old, new, names::get, now, source) })
        if (keepBalance) keepBalances(edits, now)
    }

    /**
     * Shifts the opening balance of each account that old rows moved into or out
     * of, and logs the shift, so the balance on screen does not move. Runs in the
     * caller's Room transaction, like everything else here.
     */
    private suspend fun keepBalances(edits: List<Pair<Transaction, Transaction>>, now: Long) {
        val moved = edits.filter { (old, new) -> old.accountId != new.accountId }
        if (moved.isEmpty()) return
        val ids = moved.flatMap { listOf(it.first.accountId, it.second.accountId) }.filterNotNull().distinct()
        if (ids.isEmpty()) return

        val accountDao = database.accountDao()
        val accounts = ids.mapNotNull { id -> accountDao.getById(id)?.toDomain() }.associateBy { it.id }
        val lastAdjustmentAt = database.transactionDao().lastAdjustmentAt(ids).associate { it.accountId to it.atMillis }
        val kept = BalanceNeutralFiling.offsets(moved, accounts, lastAdjustmentAt)

        val shifted = kept.filter { it.newOpeningBalance.compareTo(it.previousOpeningBalance) != 0 }
        shifted.forEach { accountDao.setOpeningBalance(it.accountId, it.newOpeningBalance) }
        record(shifted.map { AccountChangeDiff.historyFiled(accounts.getValue(it.accountId), it, now) })

        BalanceNeutralFiling.notice(kept) { accounts[it]?.name }?.let(onBalanceKept)
    }

    suspend fun recordDeletes(rows: List<Transaction>, source: AccountChangeSource = AccountChangeSource.USER) {
        if (rows.isEmpty()) return
        val names = namesOf(rows.map { it.accountId })
        val now = clock()
        record(rows.flatMap { LedgerChangeDiff.deleted(it, names::get, now, source) })
    }

    suspend fun recordAdjustment(
        tx: Transaction,
        reason: String? = null,
        note: String? = null,
        source: AccountChangeSource = AccountChangeSource.USER,
    ) {
        val names = namesOf(listOf(tx.accountId))
        record(LedgerChangeDiff.adjusted(tx, names::get, clock(), reason, note, source))
    }

    private suspend fun namesOf(ids: Collection<Long?>): Map<Long, String> =
        ids.filterNotNull().distinct().mapNotNull { id ->
            database.accountDao().getById(id)?.let { id to it.toDomain().name }
        }.toMap()
}
