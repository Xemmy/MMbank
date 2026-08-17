package com.xemmy.mmbank.data.repository

import androidx.room.withTransaction
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.dao.TransactionDao
import com.xemmy.mmbank.data.local.entity.TransactionEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.local.entity.toEntity
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.CategoryMigrationMapping
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.parser.AiConfidenceScorer
import com.xemmy.mmbank.domain.parser.CaptureCategoryResolver
import com.xemmy.mmbank.domain.parser.MerchantCategorizer
import com.xemmy.mmbank.domain.repository.CategoryRepository
import com.xemmy.mmbank.domain.repository.TransactionRepository
import com.xemmy.mmbank.domain.usecase.TransferMatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

class TransactionRepositoryImpl(
    private val database: AppDatabase,
    private val dao: TransactionDao,
    /**
     * Whether "Demo mode" is on. The ledger observers switch between the real
     * partition (false) and the disposable demo partition (true) reactively, so
     * flipping this hides/reveals the real data live without touching a row.
     */
    private val demoMode: StateFlow<Boolean>,
    /** Person newly captured transactions default to (Settings > Default person). */
    private val defaultPersonProvider: () -> String? = { null },
    /** Dynamic category lookup for newly captured transactions (see [CategoryMatcher]). */
    private val categoryRepository: CategoryRepository? = null,
    /**
     * Fire-and-forget hook run after a real, dedup-passing transaction is
     * inserted -- used by [com.xemmy.mmbank.di.AppContainer] to run the daily
     * budget pacing check. Never awaited and never allowed to affect the
     * ingest pipeline's own result.
     */
    private val onTransactionRecorded: (Transaction) -> Unit = {},
) : TransactionRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeTransactions(): Flow<List<Transaction>> =
        demoMode.flatMapLatest { demo -> dao.observeAll(demo) }
            .map { list -> list.map(TransactionEntity::toDomain) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRecent(limit: Int): Flow<List<Transaction>> =
        demoMode.flatMapLatest { demo -> dao.observeRecent(demo, limit) }
            .map { list -> list.map(TransactionEntity::toDomain) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeNetByAccount(): Flow<Map<Long, Double>> =
        demoMode.flatMapLatest { demo -> dao.observeNetByAccount(demo) }
            .map { rows -> rows.associate { it.accountId to it.net } }

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

            // Dynamic category: a confirmed merchant mapping wins outright; failing
            // that, an unconfirmed AI guess for the same merchant rides along as a
            // pending suggestion rather than being silently applied (see
            // AppContainer.onAiCategorySuggested). Only when neither applies does
            // MerchantCategorizer's keyword guess get bridged onto a same-named
            // dynamic category (see CategoryMigrationMapping) as a last resort --
            // CaptureCategoryResolver picks between the three.
            val confirmedCategoryId = parsed.merchant?.let { categoryRepository?.findCategoryForMerchant(it) }
            val pendingSuggestion = if (confirmedCategoryId == null) {
                parsed.merchant?.let { categoryRepository?.findPendingSuggestion(it) }
            } else null
            val spendingCategory = parsed.spendingCategoryOverride
                ?: MerchantCategorizer.categorize(parsed.merchant, message.body)
            val bridgedCategoryId = if (confirmedCategoryId == null && pendingSuggestion == null &&
                spendingCategory != SpendingCategory.UNCATEGORIZED
            ) {
                CategoryMigrationMapping.ENUM_TO_CATEGORY_NAME[spendingCategory]
                    ?.let { categoryRepository?.findCategoryIdByName(it) }
            } else null
            val categoryId = CaptureCategoryResolver.resolve(confirmedCategoryId, pendingSuggestion != null, bridgedCategoryId)

            val entity = TransactionEntity(
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
                spendingCategory = spendingCategory,
                excludeFromStats = parsed.excludeFromStats,
                categoryId = categoryId,
                needsCategoryApproval = pendingSuggestion != null,
                suggestedCategoryName = pendingSuggestion?.suggestedName,
                suggestedCategoryIcon = pendingSuggestion?.suggestedIcon,
                suggestedCategoryColorHex = pendingSuggestion?.suggestedColorHex,
                extractedMerchant = parsed.merchant,
                aiConfidence = AiConfidenceScorer.score(
                    // Deliberately the confirmed-only signal, not the final
                    // (possibly bridge-assigned) categoryId -- a bridge match
                    // is a KEYWORD_MATCH-strength guess, not a CONFIRMED_MAPPING
                    // one; spendingCategory already carries that signal below.
                    hasConfirmedCategory = confirmedCategoryId != null,
                    hasPendingSuggestion = pendingSuggestion != null,
                    spendingCategory = spendingCategory,
                ),
            )
            val id = dao.insert(entity)
            val inserted = entity.copy(id = id)
            tryLinkTransfer(inserted)
            onTransactionRecorded(inserted.toDomain())
            IngestResult.Inserted(id)
        }
    }

    /**
     * Reconciles a credit-card statement payment's two legs (see
     * [TransferMatcher]) right after inserting [inserted], inside the same
     * Room transaction as the insert itself — matching [recordParsed]'s own
     * atomicity guarantee, so a second, unrelated pair racing in can never
     * grab an already-linked row.
     */
    private suspend fun tryLinkTransfer(inserted: TransactionEntity) {
        val candidates = dao.findUnlinkedOppositeDirectionCandidates(
            amount = inserted.amount,
            currencyCode = inserted.currencyCode,
            direction = inserted.direction,
            timestamp = inserted.timestamp,
            windowMillis = TransferMatcher.MATCH_WINDOW_MILLIS,
        )
        val match = TransferMatcher.bestMatch(inserted.toDomain(), candidates.map(TransactionEntity::toDomain)) ?: return
        dao.linkAsTransfer(inserted.id, match.id, SpendingCategory.TRANSFERS)
        dao.linkAsTransfer(match.id, inserted.id, SpendingCategory.TRANSFERS)
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

    override suspend fun bulkAssignAccount(ids: List<Long>, accountId: Long?): Int =
        dao.bulkAssignAccount(ids, accountId)

    override suspend fun bulkSetPerson(ids: List<Long>, person: String?): Int =
        dao.bulkSetPerson(ids, person?.trim()?.takeIf { it.isNotBlank() })

    override suspend fun getAllReal(): List<Transaction> = dao.getAll().map(TransactionEntity::toDomain)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeUncategorized(): Flow<List<Transaction>> =
        demoMode.flatMapLatest { demo -> dao.observeUncategorized(demo) }
            .map { list -> list.map(TransactionEntity::toDomain) }

    override suspend fun setCategory(transactionId: Long, categoryId: Long?) =
        dao.setCategory(transactionId, categoryId)

    override suspend fun bulkSetCategoryId(ids: List<Long>, categoryId: Long?): Int =
        dao.bulkSetCategoryId(ids, categoryId)

    override suspend fun dismissCategorySuggestion(transactionId: Long) =
        dao.dismissCategorySuggestion(transactionId)

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
