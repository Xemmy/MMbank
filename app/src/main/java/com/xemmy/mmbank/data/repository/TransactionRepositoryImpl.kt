package com.xemmy.mmbank.data.repository

import androidx.room.withTransaction
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.dao.TransactionDao
import com.xemmy.mmbank.data.local.entity.TransactionEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.local.entity.toEntity
import com.xemmy.mmbank.domain.account.AccountBalanceCalculator
import com.xemmy.mmbank.domain.account.AccountNumberMatcher
import com.xemmy.mmbank.data.local.entity.MerchantKindMappingEntity
import com.xemmy.mmbank.domain.ledger.MerchantKindMatcher
import com.xemmy.mmbank.domain.ledger.TransactionKindDetector
import com.xemmy.mmbank.domain.ledger.TransferLegs
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.parser.MerchantAccountLearner
import com.xemmy.mmbank.domain.repository.LedgerRepository
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.CategoryMigrationMapping
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.parser.AiConfidenceScorer
import com.xemmy.mmbank.domain.parser.CaptureAccountResolver
import com.xemmy.mmbank.domain.parser.CaptureCategoryResolver
import com.xemmy.mmbank.domain.parser.MerchantCategorizer
import com.xemmy.mmbank.domain.repository.CategoryRepository
import com.xemmy.mmbank.domain.repository.MerchantAccountRepository
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
     * Learned merchant -> account lookup, consulted only after the account
     * number in the message fails to identify one (see [CaptureAccountResolver]).
     * Nullable and defaulted like [categoryRepository] so every existing
     * construction site -- including the hand-written test fakes -- still
     * compiles and behaves exactly as before.
     */
    private val merchantAccountRepository: MerchantAccountRepository? = null,
    /**
     * Fire-and-forget hook run after a real, dedup-passing transaction is
     * inserted -- used by [com.xemmy.mmbank.di.AppContainer] to run the daily
     * budget pacing check. Never awaited and never allowed to affect the
     * ingest pipeline's own result.
     */
    private val onTransactionRecorded: (Transaction) -> Unit = {},
) : TransactionRepository, LedgerRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeTransactions(): Flow<List<Transaction>> =
        demoMode.flatMapLatest { demo -> dao.observeAll(demo) }
            .map { list -> list.map(TransactionEntity::toDomain) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRecent(limit: Int): Flow<List<Transaction>> =
        demoMode.flatMapLatest { demo -> dao.observeRecent(demo, limit) }
            .map { list -> list.map(TransactionEntity::toDomain) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeLedgerEntries(): Flow<List<AccountBalanceCalculator.LedgerEntry>> =
        demoMode.flatMapLatest { demo -> dao.observeLedgerEntries(demo) }
            .map { rows ->
                rows.map {
                    AccountBalanceCalculator.LedgerEntry(
                        accountId = it.accountId,
                        amount = it.amount,
                        currencyCode = it.currencyCode,
                        direction = it.direction,
                    )
                }
            }

    /**
     * Sliding-Window Deduplication Engine.
     *
     * The same purchase often arrives twice — once as an SMS and once as a bank
     * app push notification, seconds apart. Before inserting, we look for an
     * existing row with identical amount, currency and direction whose event
     * timestamp falls within ±[DEDUP_WINDOW_MILLIS]. The check and the insert
     * run inside one Room transaction so two channels racing each other can't
     * both pass the check.
     *
     * [dedupWindow] = false skips that check entirely — see the interface: the
     * window only means something when the timestamp is a real arrival time.
     */
    override suspend fun recordParsed(
        parsed: ParsedTransaction,
        message: CapturedMessage,
        dedupWindow: Boolean,
    ): IngestResult = database.withTransaction {
        val existing = if (dedupWindow) {
            dao.findDuplicateInWindow(
                amount = parsed.amount,
                currencyCode = parsed.currencyCode,
                direction = parsed.direction,
                timestamp = message.timestampMillis,
                windowMillis = DEDUP_WINDOW_MILLIS,
            )
        } else {
            null
        }
        if (existing != null) {
            IngestResult.Duplicate(existing.id)
        } else {
            // Rule pin > account number printed in the message > learned
            // merchant rule. The ordering itself lives in CaptureAccountResolver
            // so it can be tested on its own rather than inferred from this
            // block.
            val rulePinnedAccountId = parsed.assignAccountId
            val numberAccountId = parsed.accountNumberSuffix
                ?.let { detectAccountByNumber(it, parsed.currencyCode) }
            // Only pay for the learned-rule read when the two stronger signals
            // both came up empty -- which includes a message that DID carry a
            // card number that matches no account, the case that otherwise
            // leaves a transaction unassigned forever.
            val learnedAccountId = if (rulePinnedAccountId == null && numberAccountId == null) {
                parsed.merchant?.let { merchantAccountRepository?.findAccountForMerchant(it) }
            } else {
                null
            }
            val detectedAccountId = CaptureAccountResolver.resolve(
                rulePinnedAccountId = rulePinnedAccountId,
                detectedAccountId = numberAccountId,
                learnedAccountId = learnedAccountId,
            )

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

            // What the message is -- spend, refund, ATM, card payment... --
            // decided once the account is known, since a credit landing on a
            // credit card is a payment or a refund, never income.
            val accountType = detectedAccountId?.let { database.accountDao().getById(it)?.type }
            val kind = TransactionKindDetector.detect(
                TransactionKindDetector.Input(
                    direction = parsed.direction,
                    matchedRuleName = parsed.matchedRuleName,
                    rawMessage = message.body,
                    merchant = parsed.merchant,
                    accountType = accountType,
                    category = parsed.category,
                    ruleKind = parsed.kindOverride,
                    learnedKind = parsed.merchant?.let { findKindForMerchant(it) },
                ),
            )

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
                kind = kind,
                reportedBalance = parsed.reportedBalance,
                reportedBalanceKind = parsed.reportedBalanceKind,
            )
            val id = dao.insert(entity)
            tryLinkTransfer(entity.copy(id = id))
            // The row as it stands AFTER linking: a debit that just turned out
            // to be one leg of a card payment must not trip a budget alert or
            // feed subscription detection as if it were a purchase.
            onTransactionRecorded((dao.getById(id) ?: entity.copy(id = id)).toDomain())
            IngestResult.Inserted(id)
        }
    }

    override suspend fun findIdByRawMessage(rawMessage: String): Long? =
        dao.findByRawMessage(rawMessage)?.id

    /**
     * Reconciles the two legs of a transfer or card payment (see
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
            windowMillis = TransferMatcher.MAX_WINDOW_MILLIS,
        )
        if (candidates.isEmpty()) return
        val accountTypes = accountTypes()
        val self = inserted.toDomain()
        val match = TransferMatcher.bestMatch(self, candidates.map(TransactionEntity::toDomain), accountTypes) ?: return
        val kind = TransferMatcher.pairKind(self, match, accountTypes)
        dao.linkAsTransfer(inserted.id, match.id, kind, SpendingCategory.TRANSFERS)
        dao.linkAsTransfer(match.id, inserted.id, kind, SpendingCategory.TRANSFERS)
    }

    /** Re-detects [row]'s kind as if it stood alone (a kind the user set is kept). */
    private suspend fun standaloneKind(row: TransactionEntity, accountTypes: Map<Long, AccountType>): TransactionKind =
        if (row.kindUserSet) {
            row.kind
        } else {
            TransactionKindDetector.detect(
                TransactionKindDetector.Input(
                    direction = row.direction,
                    matchedRuleName = row.matchedRuleName,
                    rawMessage = row.rawMessage,
                    merchant = row.merchant,
                    accountType = row.accountId?.let { accountTypes[it] },
                    category = row.category,
                    learnedKind = row.merchant?.let { findKindForMerchant(it) },
                ),
            )
        }

    private suspend fun accountTypes(): Map<Long, AccountType> =
        database.accountDao().getActive().associate { it.id to it.type }

    /**
     * Deletes [ids] and releases whatever they were linked to. Without this
     * the surviving leg kept pointing at a row that no longer exists and kept
     * its transfer kind -- silently hidden from Spent/Received forever. The
     * partner gets the kind it would have had on its own (a kind the user set
     * by hand is kept).
     */
    private suspend fun deleteAndRelease(ids: List<Long>): Int = database.withTransaction {
        val partners = dao.getLinkedPartners(ids)
        val deleted = dao.deleteMany(ids)
        if (partners.isNotEmpty()) {
            val accountTypes = accountTypes()
            partners.forEach { partner ->
                dao.update(partner.copy(linkedTransactionId = null, kind = standaloneKind(partner, accountTypes)))
            }
        }
        deleted
    }

    override suspend fun recordManual(transaction: Transaction): Long =
        dao.insert(transaction.toEntity())

    // --- LedgerRepository ---

    override suspend fun recordTransfer(draft: TransferLegs.Draft): Int = database.withTransaction {
        val (out, inn) = TransferLegs.build(draft, label = MANUAL_TRANSFER_LABEL)
        val outId = out?.let { dao.insert(it.toEntity()) }
        val inId = inn?.let { dao.insert(it.toEntity()) }
        if (outId != null && inId != null) {
            val kind = TransferLegs.kindFor(draft)
            dao.linkAsTransfer(outId, inId, kind, SpendingCategory.TRANSFERS)
            dao.linkAsTransfer(inId, outId, kind, SpendingCategory.TRANSFERS)
        }
        listOfNotNull(outId, inId).size
    }

    override suspend fun setKind(transactionId: Long, kind: TransactionKind, rememberForMerchant: Boolean): Int =
        database.withTransaction {
            val row = dao.getById(transactionId) ?: return@withTransaction 0
            val accountTypes = accountTypes()
            val newKind = TransactionKindDetector.coerce(kind, row.direction)
            var updated = row.copy(kind = newKind, kindUserSet = true)

            val partner = row.linkedTransactionId?.let { dao.getById(it) }
            if (partner != null) {
                if (newKind.isInternalMove) {
                    // Transfer <-> card payment: keep the pair consistent.
                    if (!partner.kindUserSet) dao.update(partner.copy(kind = newKind))
                } else {
                    // No longer one side of a move: release the other side.
                    dao.update(partner.copy(linkedTransactionId = null, kind = standaloneKind(partner, accountTypes)))
                    updated = updated.copy(linkedTransactionId = null)
                }
            }
            dao.update(updated)
            if (newKind.isInternalMove && updated.linkedTransactionId == null) tryLinkTransfer(updated)

            val merchant = row.merchant?.trim()?.takeIf { it.isNotEmpty() }
            if (!rememberForMerchant || merchant == null) return@withTransaction 0
            database.merchantKindDao().upsert(
                MerchantKindMappingEntity(merchantPattern = merchant, kind = newKind),
            )
            val candidates = dao.getAutoClassifiedWithMerchant().filter { it.id != row.id }
            val ids = MerchantAccountLearner.idsToBackfill(
                candidates.map { MerchantAccountLearner.Candidate(it.id, it.merchant.orEmpty()) },
                merchant,
            ).toSet()
            var changed = 0
            candidates.filter { it.id in ids }.forEach { other ->
                val coerced = TransactionKindDetector.coerce(newKind, other.direction)
                if (coerced != other.kind) {
                    dao.update(other.copy(kind = coerced))
                    if (coerced.isInternalMove) tryLinkTransfer(other.copy(kind = coerced))
                    changed++
                }
            }
            changed
        }

    override suspend fun findKindForMerchant(merchant: String): TransactionKind? =
        MerchantKindMatcher.match(merchant, database.merchantKindDao().getAll().map { it.toDomain() })

    override suspend fun forgetMerchantKind(merchant: String): Int =
        database.merchantKindDao().deleteByMerchant(merchant.trim())

    override fun observeMerchantKinds(): Flow<List<com.xemmy.mmbank.domain.model.MerchantKindMapping>> =
        database.merchantKindDao().observeAll().map { rows -> rows.map { it.toDomain() } }

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
        dao.renamePerson(from, to?.trim()?.takeIf { it.isNotBlank() }, demoMode.value)

    override suspend fun delete(transactionId: Long) {
        deleteAndRelease(listOf(transactionId))
    }

    override suspend fun bulkSetCategory(
        ids: List<Long>,
        category: com.xemmy.mmbank.domain.model.SpendingCategory,
    ): Int = dao.bulkSetCategory(ids, category)

    override suspend fun bulkSetExcludeFromStats(ids: List<Long>, excluded: Boolean): Int =
        dao.bulkSetExcludeFromStats(ids, excluded)

    override suspend fun deleteMany(ids: List<Long>): Int = deleteAndRelease(ids)

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

    override suspend fun reassignCategory(oldCategoryId: Long, newCategoryId: Long?): Int =
        dao.reassignCategory(oldCategoryId, newCategoryId, demoMode.value)

    /**
     * Matches the account/card number pulled from a bank message (e.g. "*617",
     * "XXX810001") against each account's number and extra card numbers; see
     * [AccountNumberMatcher] for the rules.
     */
    private suspend fun detectAccountByNumber(detected: String, currencyCode: String): Long? =
        AccountNumberMatcher.match(
            detected = detected,
            currencyCode = currencyCode,
            candidates = database.accountDao().getActive().map {
                AccountNumberMatcher.Candidate(it.id, it.accountNumber, it.cardEndings, it.currencyCode)
            },
        )

    companion object {
        /** ±2 minutes, per the dedup strategy. */
        const val DEDUP_WINDOW_MILLIS: Long = 2 * 60 * 1000

        /** matchedRuleName on both legs of a transfer the user entered by hand. */
        const val MANUAL_TRANSFER_LABEL = "Manual transfer"
    }
}
