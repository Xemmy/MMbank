package com.xemmy.mmbank.data.repository

import androidx.room.withTransaction
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.dao.TransactionDao
import com.xemmy.mmbank.data.local.entity.TransactionEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.local.entity.toEntity
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.parser.MerchantCategorizer
import com.xemmy.mmbank.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TransactionRepositoryImpl(
    private val database: AppDatabase,
    private val dao: TransactionDao,
    /** Person newly captured transactions default to (Settings > Default person). */
    private val defaultPersonProvider: () -> String? = { null },
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
            // A rule's explicit account ("default assign") wins over the
            // number sniffed out of the message text.
            val detectedAccountId = parsed.assignAccountId
                ?: parsed.accountNumberSuffix?.let { detectAccountByNumber(it, parsed.currencyCode) }

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
                    accountNumberSuffix = parsed.accountNumberSuffix,
                    accountId = detectedAccountId,
                    category = parsed.category,
                    person = defaultPersonProvider()?.takeIf { it.isNotBlank() },
                    spendingCategory = MerchantCategorizer.categorize(parsed.merchant, message.body),
                    excludeFromStats = parsed.excludeFromStats,
                )
            )
            IngestResult.Inserted(id)
        }
    }

    override suspend fun recordManual(transaction: Transaction): Long =
        dao.insert(transaction.toEntity())

    override suspend fun update(transaction: Transaction) =
        dao.update(transaction.toEntity())

    override suspend fun assignAccount(transactionId: Long, accountId: Long?) =
        dao.assignAccount(transactionId, accountId)

    override suspend fun assignAccountForRule(ruleName: String, accountId: Long): Int =
        dao.assignAccountByRuleName(ruleName, accountId)

    override suspend fun setPerson(transactionId: Long, person: String?) =
        dao.setPerson(transactionId, person?.trim()?.takeIf { it.isNotBlank() })

    override suspend fun setSpendingCategory(
        transactionId: Long,
        category: com.xemmy.mmbank.domain.model.SpendingCategory,
    ) = dao.setSpendingCategory(transactionId, category)

    override suspend fun renamePerson(from: String, to: String?) =
        dao.renamePerson(from, to?.trim()?.takeIf { it.isNotBlank() })

    override suspend fun delete(transactionId: Long) = dao.deleteById(transactionId)

    override suspend fun bulkSetCategory(
        ids: List<Long>,
        category: com.xemmy.mmbank.domain.model.SpendingCategory,
    ): Int = dao.bulkSetCategory(ids, category)

    override suspend fun bulkSetExcludeFromStats(ids: List<Long>, excluded: Boolean): Int =
        dao.bulkSetExcludeFromStats(ids, excluded)

    override suspend fun deleteMany(ids: List<Long>): Int = dao.deleteMany(ids)

    /**
     * Matches the account/card number pulled from a bank message (e.g. "*617",
     * "XXX810001") against the identifying number the user configured on each
     * account. Comparison is on digits only, tolerating masking characters and
     * differing lengths by matching on a shared suffix. When several accounts
     * share the same trailing digits, currency is used to disambiguate.
     */
    private suspend fun detectAccountByNumber(detected: String, currencyCode: String): Long? {
        val detectedDigits = detected.filter(Char::isDigit)
        if (detectedDigits.isEmpty()) return null

        val candidates = database.accountDao().getActive().filter { account ->
            val accountDigits = account.accountNumber?.filter(Char::isDigit).orEmpty()
            accountDigits.length >= 3 &&
                (detectedDigits.endsWith(accountDigits) || accountDigits.endsWith(detectedDigits))
        }

        return when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates.first().id
            else -> candidates.firstOrNull { it.currencyCode.equals(currencyCode, ignoreCase = true) }?.id
                ?: candidates.first().id
        }
    }

    companion object {
        /** ±2 minutes, per the dedup strategy. */
        const val DEDUP_WINDOW_MILLIS: Long = 2 * 60 * 1000
    }
}
