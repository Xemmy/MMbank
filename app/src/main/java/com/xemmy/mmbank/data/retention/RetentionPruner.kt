package com.xemmy.mmbank.data.retention

import androidx.room.withTransaction
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.local.entity.toEntity
import com.xemmy.mmbank.domain.account.AccountChangeDiff
import com.xemmy.mmbank.domain.account.AccountBalanceCalculator
import com.xemmy.mmbank.domain.account.LedgerFx
import com.xemmy.mmbank.domain.account.PrunedBalanceFolder

/**
 * Deletes pruned transactions without moving a single balance.
 *
 * Balances are derived from the rows themselves, so a plain `deleteMany` shifts
 * every balance the deleted rows contributed to -- the opt-in retention setting
 * used to do exactly that, silently. [PrunedBalanceFolder] works out the
 * opening-balance correction that cancels the deletion out; this class applies
 * it and the deletion together inside **one** Room transaction, so a failure
 * can never leave the balance folded but the rows still present (or vice
 * versa).
 *
 * Held directly by [com.xemmy.mmbank.di.AppContainer] rather than hidden behind
 * `TransactionRepository`, following [com.xemmy.mmbank.data.backup.BackupManager]:
 * both are whole-database operations, and the repository interface is already
 * wide enough that every hand-written test fake pays for each new member.
 */
class RetentionPruner(
    private val database: AppDatabase,
    /**
     * Foreign-currency converter, so the fold is computed on the same
     * converted amounts every balance screen uses -- otherwise a prune would
     * move balances for accounts holding foreign-currency rows.
     */
    private val fxProvider: suspend () -> LedgerFx? = { null },
) {

    /**
     * @param deleted how many rows were actually removed.
     * @param accountsAdjusted how many accounts had their opening balance folded.
     */
    data class Result(val deleted: Int, val accountsAdjusted: Int)

    /**
     * Deletes [plannedIds] and folds their net into the affected accounts'
     * opening balances. Rows belonging to no account are deleted with no fold
     * -- they were in no balance, so removing them moves nothing.
     *
     * [logCutoffMillis], when given, also removes account change-log entries
     * older than it: the log describes the history being trimmed, so it goes
     * with it (the fold entries written here are new and always stay).
     */
    suspend fun prune(plannedIds: List<Long>, logCutoffMillis: Long? = null): Result {
        if (plannedIds.isEmpty()) return Result(deleted = 0, accountsAdjusted = 0)

        val fx = fxProvider()
        return database.withTransaction {
            val transactionDao = database.transactionDao()
            val accountDao = database.accountDao()

            // Read the rows INSIDE the transaction: the fold has to describe
            // exactly the rows the delete below removes.
            val rawEntries = transactionDao.getLedgerEntriesByIds(plannedIds).map { row ->
                AccountBalanceCalculator.LedgerEntry(
                    accountId = row.accountId,
                    amount = row.amount,
                    currencyCode = row.currencyCode,
                    direction = row.direction,
                )
            }
            val entries = fx?.apply(rawEntries) ?: rawEntries
            val accounts = accountDao.getAll().map { it.toDomain() }
            val adjustments = PrunedBalanceFolder.fold(
                accounts = accounts,
                prunedEntries = entries,
            )

            adjustments.forEach { accountDao.setOpeningBalance(it.accountId, it.newOpeningBalance) }
            // The opening balance just moved underneath an unchanged balance: say so, or it is a silent edit.
            val byId = accounts.associateBy { it.id }
            val now = System.currentTimeMillis()
            database.accountChangeDao().insertAll(
                adjustments.mapNotNull { adj ->
                    byId[adj.accountId]?.let { AccountChangeDiff.retentionFolded(it, adj, now).toEntity() }
                },
            )
            val deleted = transactionDao.deleteMany(plannedIds)
            if (logCutoffMillis != null) database.accountChangeDao().deleteOlderThan(logCutoffMillis)

            Result(deleted = deleted, accountsAdjusted = adjustments.size)
        }
    }
}
