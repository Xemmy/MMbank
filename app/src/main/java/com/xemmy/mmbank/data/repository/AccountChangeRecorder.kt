package com.xemmy.mmbank.data.repository

import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.local.entity.toEntity
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
) {

    suspend fun record(changes: List<AccountChange>) {
        if (changes.isNotEmpty()) database.accountChangeDao().insertAll(changes.map { it.toEntity() })
    }

    /** Edited / moved rows: each pair is (before, after). Pairs with no balance-relevant difference log nothing. */
    suspend fun recordEdits(
        edits: List<Pair<Transaction, Transaction>>,
        source: AccountChangeSource = AccountChangeSource.USER,
    ) {
        if (edits.isEmpty()) return
        val names = namesOf(edits.flatMap { listOf(it.first.accountId, it.second.accountId) })
        val now = clock()
        record(edits.flatMap { (old, new) -> LedgerChangeDiff.edited(old, new, names::get, now, source) })
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
