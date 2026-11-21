package com.xemmy.mmbank.data.repository

import androidx.room.withTransaction
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.entity.MerchantAccountMappingEntity
import com.xemmy.mmbank.data.local.entity.TransactionEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.domain.model.MerchantAccountMapping
import com.xemmy.mmbank.domain.parser.AccountMatcher
import com.xemmy.mmbank.domain.parser.MerchantAccountLearner
import com.xemmy.mmbank.domain.repository.MerchantAccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MerchantAccountRepositoryImpl(
    private val database: AppDatabase,
    /** Told when backfilled history left a corrected balance where it was; see [AccountChangeRecorder]. */
    onBalanceKept: (String) -> Unit = {},
) : MerchantAccountRepository {

    private val dao get() = database.merchantAccountDao()

    private val changeLog = AccountChangeRecorder(database, onBalanceKept = onBalanceKept)

    override fun observeMappings(): Flow<List<MerchantAccountMapping>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun findAccountForMerchant(merchant: String): Long? =
        AccountMatcher.match(merchant, dao.getAll().map { it.toDomain() })

    override suspend fun findMappingForAccount(accountId: Long): List<MerchantAccountMapping> =
        dao.getForAccount(accountId).map { it.toDomain() }

    /**
     * Remembers the rule and immediately applies it to the history it explains.
     *
     * Both halves run in one Room transaction: a rule that is stored but not
     * backfilled (or vice versa) would leave the user looking at transactions
     * that contradict the correction they just made.
     */
    override suspend fun learnMapping(merchant: String, accountId: Long): Int {
        val trimmed = merchant.trim()
        if (trimmed.isEmpty()) return 0

        return database.withTransaction {
            dao.upsert(
                MerchantAccountMappingEntity(
                    merchantPattern = trimmed,
                    accountId = accountId,
                    createdAt = System.currentTimeMillis(),
                )
            )
            val candidates = database.transactionDao().getUnassignedWithMerchant()
                .map { MerchantAccountLearner.Candidate(id = it.id, merchant = it.merchant) }
            val ids = MerchantAccountLearner.idsToBackfill(candidates, trimmed)
            if (ids.isEmpty()) {
                0
            } else {
                // Moving history onto an account moves that account's balance: log it like any other move.
                val moving = ids.chunked(500).flatMap { database.transactionDao().getByIds(it) }.map(TransactionEntity::toDomain)
                val moved = database.transactionDao().bulkAssignAccount(ids, accountId)
                changeLog.recordEdits(moving.map { it to it.copy(accountId = accountId) })
                moved
            }
        }
    }

    override suspend fun forget(merchant: String): Int = dao.deleteByMerchant(merchant.trim())
}
