package com.xemmy.mmbank.data.repository

import androidx.room.withTransaction
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.dao.TransactionDao
import com.xemmy.mmbank.data.local.entity.TransactionEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TransactionRepositoryImpl(
    private val database: AppDatabase,
    private val dao: TransactionDao,
) : TransactionRepository {

    override fun observeTransactions(): Flow<List<Transaction>> =
        dao.observeAll().map { list -> list.map(TransactionEntity::toDomain) }

    override fun observeRecent(limit: Int): Flow<List<Transaction>> =
        dao.observeRecent(limit).map { list -> list.map(TransactionEntity::toDomain) }

    override fun observeNetByAccount(): Flow<Map<Long, Double>> =
        dao.observeNetByAccount().map { rows -> rows.associate { it.accountId to it.net } }

    /**
     * Sliding-Window Deduplication Engine.
     *
     * The same purchase often arrives twice — once as an SMS and once as a bank
     * app push notification, seconds apart. Before inserting, we look for an
     * existing row with identical amount, currency and direction whose event
     * timestamp falls within ±[DEDUP_WINDOW_MILLIS]. The check and the insert
     * run inside one Room transaction so two channels racing each other can't
     * both pass the check.
     */
    override suspend fun recordParsed(
        parsed: ParsedTransaction,
        message: CapturedMessage,
    ): IngestResult = database.withTransaction {
        val existing = dao.findDuplicateInWindow(
            amount = parsed.amount,
            currencyCode = parsed.currencyCode,
            direction = parsed.direction,
            timestamp = message.timestampMillis,
            windowMillis = DEDUP_WINDOW_MILLIS,
        )
        if (existing != null) {
            IngestResult.Duplicate(existing.id)
        } else {
            val id = dao.insert(
                TransactionEntity(
                    amount = parsed.amount,
                    currencyCode = parsed.currencyCode,
                    direction = parsed.direction,
                    merchant = parsed.merchant,
                    source = message.source,
                    sender = message.sender,
                    rawMessage = message.body,
                    matchedRuleName = parsed.matchedRuleName,
                    timestamp = message.timestampMillis,
                    createdAt = System.currentTimeMillis(),
                )
            )
            IngestResult.Inserted(id)
        }
    }

    override suspend fun assignAccount(transactionId: Long, accountId: Long?) =
        dao.assignAccount(transactionId, accountId)

    override suspend fun delete(transactionId: Long) = dao.deleteById(transactionId)

    companion object {
        /** ±2 minutes, per the dedup strategy. */
        const val DEDUP_WINDOW_MILLIS: Long = 2 * 60 * 1000
    }
}
