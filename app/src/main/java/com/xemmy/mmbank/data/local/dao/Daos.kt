package com.xemmy.mmbank.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xemmy.mmbank.data.local.entity.AccountChangeEntity
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.AssetBalanceEntity
import com.xemmy.mmbank.data.local.entity.BillEntity
import com.xemmy.mmbank.data.local.entity.ExchangeRateEntity
import com.xemmy.mmbank.data.local.entity.ExchangeRateHistoryEntity
import com.xemmy.mmbank.data.local.entity.ExpenseSplitEntity
import com.xemmy.mmbank.data.local.entity.FriendEntity
import com.xemmy.mmbank.data.local.entity.GroupExpenseEntity
import com.xemmy.mmbank.data.local.entity.IgnoredKeywordEntity
import com.xemmy.mmbank.data.local.entity.IgnoredMessagePatternEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.SavingsGoalEntity
import com.xemmy.mmbank.data.local.entity.SenderLabelEntity
import com.xemmy.mmbank.data.local.entity.SplitGroupEntity
import com.xemmy.mmbank.data.local.entity.SplitGroupMemberEntity
import com.xemmy.mmbank.data.local.entity.SubscriptionEntity
import com.xemmy.mmbank.data.local.entity.TransactionEntity
import com.xemmy.mmbank.data.local.entity.WalletBalanceEntity
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow

/**
 * One transaction reduced to what a balance needs, so the balance surfaces
 * never load whole rows (which carry the full `rawMessage` SMS body) just to
 * add up money. [amount] stays TEXT -> BigDecimal through `Converters`, never a
 * lossy `CAST(amount AS REAL)`.
 *
 * [accountId] is nullable on purpose: a transaction belonging to no account is
 * in the ledger but in no balance, and the Accounts and Home screens surface
 * that total rather than letting it disappear.
 */
data class LedgerEntryRow(
    val accountId: Long?,
    val amount: BigDecimal,
    val currencyCode: String,
    val direction: TransactionDirection,
)

/** When an account's balance was last corrected by hand: its newest "Balance adjustment" row. */
data class LastAdjustmentRow(
    val accountId: Long,
    val atMillis: Long,
)

/** [LedgerEntryRow] plus its row id, for pruning (which must delete by id). */
data class IdentifiedLedgerEntryRow(
    val id: Long,
    val accountId: Long?,
    val amount: BigDecimal,
    val currencyCode: String,
    val direction: TransactionDirection,
)

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(entity: TransactionEntity): Long

    /** Full-row update by primary key; used by the manual "edit transaction" flow. */
    @Update
    suspend fun update(entity: TransactionEntity)

    /**
     * Sliding-window dedup lookup: an identical amount + currency + direction
     * within `windowMillis` of the event timestamp is treated as the same
     * real-world transaction arriving over a second channel (SMS vs push).
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amount = :amount
          AND currencyCode = :currencyCode
          AND direction = :direction
          AND isDemo = 0
          AND ABS(timestamp - :timestamp) <= :windowMillis
        ORDER BY ABS(timestamp - :timestamp) ASC
        LIMIT 1
        """
    )
    suspend fun findDuplicateInWindow(
        amount: BigDecimal,
        currencyCode: String,
        direction: TransactionDirection,
        timestamp: Long,
        windowMillis: Long,
    ): TransactionEntity?

    /**
     * Every row [findDuplicateInWindow] would accept, nearest first, so the
     * caller can apply [com.xemmy.mmbank.domain.ledger.CaptureDedupPolicy]
     * (channel and card-ending vetoes) and pick the first genuine duplicate.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amount = :amount
          AND currencyCode = :currencyCode
          AND direction = :direction
          AND isDemo = 0
          AND ABS(timestamp - :timestamp) <= :windowMillis
        ORDER BY ABS(timestamp - :timestamp) ASC
        LIMIT 20
        """
    )
    suspend fun findDuplicateCandidatesInWindow(
        amount: BigDecimal,
        currencyCode: String,
        direction: TransactionDirection,
        timestamp: Long,
        windowMillis: Long,
    ): List<TransactionEntity>

    /**
     * The row already captured from this exact message text, if any. Backs the
     * paste importer's "already imported" check: re-pasting the same backlog
     * must not double-record it, and unlike
     * [findDuplicateInWindow] this holds however long ago the message was
     * captured and whatever its clock time was.
     */
    @Query("SELECT * FROM transactions WHERE rawMessage = :rawMessage AND isDemo = 0 LIMIT 1")
    suspend fun findByRawMessage(rawMessage: String): TransactionEntity?

    /** [demo] selects the active partition: real rows (false) or demo rows (true). */
    @Query("SELECT * FROM transactions WHERE isDemo = :demo ORDER BY timestamp DESC")
    fun observeAll(demo: Boolean): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE isDemo = :demo ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(demo: Boolean, limit: Int): Flow<List<TransactionEntity>>

    /**
     * Every entry a balance is built from, in the active partition.
     *
     * Deliberately NOT a SQL aggregate: amounts are plain-decimal TEXT, so any
     * `SUM` needs `CAST(amount AS REAL)` and loses exactness. Summing in Kotlin
     * over this 4-column projection keeps the arithmetic in BigDecimal, and
     * costs far less than the whole-ledger loads the Home and Monthly Report
     * screens already do.
     *
     * Rows with no account are included -- see [LedgerEntryRow.accountId].
     */
    @Query("SELECT accountId, amount, currencyCode, direction FROM transactions WHERE isDemo = :demo")
    fun observeLedgerEntries(demo: Boolean): Flow<List<LedgerEntryRow>>

    /**
     * Real transactions that carry a merchant but no account -- the only rows a
     * learned merchant -> account rule is ever allowed to touch, matching
     * [assignAccountByRuleName]'s `accountId IS NULL` posture so a transaction
     * the user filed by hand is never overwritten.
     *
     * Returns merchants rather than filtering in SQL because the match is by
     * normalized key (see MerchantNormalizer), which SQL cannot express; the
     * candidate set is small by construction.
     */
    @Query("SELECT id, merchant FROM transactions WHERE accountId IS NULL AND merchant IS NOT NULL AND isDemo = 0")
    suspend fun getUnassignedWithMerchant(): List<UnassignedMerchantRow>

    /** The balance-relevant fields of specific rows, for a retention prune. */
    @Query(
        "SELECT id, accountId, amount, currencyCode, direction FROM transactions WHERE id IN (:ids)"
    )
    suspend fun getLedgerEntriesByIds(ids: List<Long>): List<IdentifiedLedgerEntryRow>

    /** Removes every demo transaction. Delete these BEFORE demo accounts (FK SET_NULL). */
    @Query("DELETE FROM transactions WHERE isDemo = 1")
    suspend fun deleteDemo(): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE isDemo = 1")
    suspend fun countDemo(): Int

    @Query("UPDATE transactions SET accountId = :accountId WHERE id = :transactionId")
    suspend fun assignAccount(transactionId: Long, accountId: Long?)

    /**
     * Backfills a rule's account onto the transactions it already captured that
     * are still unassigned. Only NULL accountIds are touched, so a transaction
     * the user manually moved elsewhere is never overwritten. Returns the count.
     */
    @Query(
        "UPDATE transactions SET accountId = :accountId " +
            "WHERE matchedRuleName = :ruleName AND accountId IS NULL"
    )
    suspend fun assignAccountByRuleName(ruleName: String, accountId: Long): Int

    /** A person set here is the user's choice; clearing it hands the row back to the default. */
    @Query(
        "UPDATE transactions SET person = :person, " +
            "personSource = CASE WHEN :person IS NULL THEN 'DEFAULT' ELSE 'USER' END WHERE id = :transactionId"
    )
    suspend fun setPerson(transactionId: Long, person: String?)

    @Query("UPDATE transactions SET spendingCategory = :category WHERE id = :transactionId")
    suspend fun setSpendingCategory(transactionId: Long, category: com.xemmy.mmbank.domain.model.SpendingCategory)

    /**
     * Renames (or clears, when [to] is null) a person across the current
     * partition. Matches case-insensitively so it covers every spelling the
     * People screen folded together when it counted them, and stays inside
     * [demo] so a rename made in Demo mode can't rewrite real rows.
     */
    @Query("UPDATE transactions SET person = :to WHERE person = :from COLLATE NOCASE AND isDemo = :demo")
    suspend fun renamePerson(from: String, to: String?, demo: Boolean)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Multi-select edit: sets spending category on every row in [ids] at once. Returns affected count. */
    @Query("UPDATE transactions SET spendingCategory = :category WHERE id IN (:ids)")
    suspend fun bulkSetCategory(ids: List<Long>, category: com.xemmy.mmbank.domain.model.SpendingCategory): Int

    /** Multi-select edit: sets the analytics-exclusion flag on every row in [ids] at once. Returns affected count. */
    @Query("UPDATE transactions SET excludeFromStats = :excluded WHERE id IN (:ids)")
    suspend fun bulkSetExcludeFromStats(ids: List<Long>, excluded: Boolean): Int

    /** Multi-select delete. Returns the number of rows removed. */
    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteMany(ids: List<Long>): Int

    /** Multi-select edit: assigns (or clears, when null) the account on every row in [ids] at once. Returns affected count. */
    @Query("UPDATE transactions SET accountId = :accountId WHERE id IN (:ids)")
    suspend fun bulkAssignAccount(ids: List<Long>, accountId: Long?): Int

    /**
     * The newest balance adjustment per account, for
     * [com.xemmy.mmbank.domain.account.BalanceNeutralFiling]: rows dated before it
     * are already inside the corrected balance.
     */
    @Query(
        "SELECT accountId AS accountId, MAX(timestampMillis) AS atMillis FROM transactions " +
            "WHERE kind = 'ADJUSTMENT' AND accountId IN (:accountIds) GROUP BY accountId"
    )
    suspend fun lastAdjustmentAt(accountIds: List<Long>): List<LastAdjustmentRow>

    /** Multi-select edit: assigns (or clears, when null) the person on every row in [ids] at once. Returns affected count. */
    @Query(
        "UPDATE transactions SET person = :person, " +
            "personSource = CASE WHEN :person IS NULL THEN 'DEFAULT' ELSE 'USER' END WHERE id IN (:ids)"
    )
    suspend fun bulkSetPerson(ids: List<Long>, person: String?): Int

    // --- Dynamic category assignment (see CategoryRepository / CategoryMatcher) ---

    /** Assigns a dynamic category and clears any pending AI suggestion on the row (manual pick or accept). */
    @Query(
        "UPDATE transactions SET categoryId = :categoryId, needsCategoryApproval = 0, " +
            "categorySource = CASE WHEN :categoryId IS NULL THEN 'DEFAULT' ELSE 'USER' END, " +
            "suggestedCategoryName = NULL, suggestedCategoryIcon = NULL, suggestedCategoryColorHex = NULL " +
            "WHERE id = :transactionId"
    )
    suspend fun setCategory(transactionId: Long, categoryId: Long?)

    /** Multi-select edit: assigns (or clears, when null) a dynamic category on every row in [ids] at once. Returns affected count. */
    @Query(
        "UPDATE transactions SET categoryId = :categoryId, needsCategoryApproval = 0, " +
            "categorySource = CASE WHEN :categoryId IS NULL THEN 'DEFAULT' ELSE 'USER' END, " +
            "suggestedCategoryName = NULL, suggestedCategoryIcon = NULL, suggestedCategoryColorHex = NULL " +
            "WHERE id IN (:ids)"
    )
    suspend fun bulkSetCategoryId(ids: List<Long>, categoryId: Long?): Int

    /** Dismisses a pending AI suggestion without assigning a category -- the row stays uncategorized. */
    @Query(
        "UPDATE transactions SET needsCategoryApproval = 0, suggestedCategoryName = NULL, " +
            "suggestedCategoryIcon = NULL, suggestedCategoryColorHex = NULL WHERE id = :transactionId"
    )
    suspend fun dismissCategorySuggestion(transactionId: Long)

    /**
     * Moves every transaction pointing at [oldCategoryId] onto [newCategoryId]
     * (or clears it to uncategorized, when null) -- used when deleting a
     * category that's still in use, so its transactions land somewhere
     * explicit instead of being silently orphaned. Returns the count moved.
     *
     * Scoped to [demo] because the count shown in the delete dialog comes from
     * the demo-aware [observeAll]: without this filter, deleting a category
     * while Demo mode is on would rewrite real rows the user never saw.
     */
    @Query(
        "UPDATE transactions SET categoryId = :newCategoryId, needsCategoryApproval = 0, " +
            "categorySource = CASE WHEN :newCategoryId IS NULL THEN 'DEFAULT' ELSE 'USER' END, " +
            "suggestedCategoryName = NULL, suggestedCategoryIcon = NULL, suggestedCategoryColorHex = NULL " +
            "WHERE categoryId = :oldCategoryId AND isDemo = :demo"
    )
    suspend fun reassignCategory(oldCategoryId: Long, newCategoryId: Long?, demo: Boolean): Int

    /**
     * Backfills a newly learned/confirmed merchant->category rule onto every
     * row from that merchant that is still uncategorized OR was categorized by
     * the app's own guess (a keyword match, an earlier learned rule, an AI
     * suggestion) -- never one the user chose themselves (`USER`, which is
     * also what every pre-existing categorized row was marked as when
     * categorySource was added). Returns the count.
     */
    @Query(
        "UPDATE transactions SET categoryId = :categoryId, needsCategoryApproval = 0, " +
            "categorySource = 'LEARNED', " +
            "suggestedCategoryName = NULL, suggestedCategoryIcon = NULL, suggestedCategoryColorHex = NULL " +
            "WHERE merchant = :merchant COLLATE NOCASE " +
            "AND (categoryId IS NULL OR categorySource IN ('KEYWORD', 'LEARNED', 'AI'))"
    )
    suspend fun backfillCategoryByMerchant(merchant: String, categoryId: Long): Int

    /** Flags every still-uncategorized row from [merchant] with a pending AI category suggestion. Returns the count. */
    @Query(
        "UPDATE transactions SET needsCategoryApproval = 1, suggestedCategoryName = :name, " +
            "suggestedCategoryIcon = :icon, suggestedCategoryColorHex = :colorHex " +
            "WHERE merchant = :merchant COLLATE NOCASE AND categoryId IS NULL"
    )
    suspend fun backfillCategorySuggestionByMerchant(merchant: String, name: String, icon: String?, colorHex: String?): Int

    /**
     * Real, non-excluded transactions still missing a dynamic category:
     * either a row with no `categoryId` at all, or one carrying a pending AI
     * suggestion. Backs the Uncategorized queue screen.
     */
    @Query(
        "SELECT * FROM transactions WHERE isDemo = :demo AND " +
            "(needsCategoryApproval = 1 OR categoryId IS NULL) AND " +
            "kind NOT IN ('TRANSFER', 'CARD_PAYMENT', 'ADJUSTMENT') " +
            "ORDER BY timestamp DESC"
    )
    fun observeUncategorized(demo: Boolean): Flow<List<TransactionEntity>>

    /**
     * Total real net spend in [currencyCode] within [ [startMillis], [endMillis] )
     * -- feeds [com.xemmy.mmbank.domain.budget.DailyBudgetPacing]. "Spend" here is
     * exactly [com.xemmy.mmbank.domain.ledger.LedgerMath.spendDelta]: SPEND and
     * ATM rows add, REFUND rows subtract, transfers/card payments/adjustments
     * and anything the user hid from analytics don't count.
     */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN kind = 'REFUND' THEN -CAST(amount AS REAL) ELSE CAST(amount AS REAL) END), 0.0)
        FROM transactions
        WHERE isDemo = 0 AND excludeFromStats = 0 AND kind IN ('SPEND', 'ATM', 'REFUND')
          AND currencyCode = :currencyCode
          AND timestamp >= :startMillis AND timestamp < :endMillis
        """
    )
    suspend fun sumDebitsInRange(currencyCode: String, startMillis: Long, endMillis: Long): Double

    /**
     * Same total as [sumDebitsInRange] but scoped to one dynamic category --
     * feeds [com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator]'s
     * post-insert cap check. A targeted aggregate rather than loading the
     * month's rows, since this runs on the capture path for every debit.
     */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN kind = 'REFUND' THEN -CAST(amount AS REAL) ELSE CAST(amount AS REAL) END), 0.0)
        FROM transactions
        WHERE isDemo = 0 AND excludeFromStats = 0 AND kind IN ('SPEND', 'ATM', 'REFUND')
          AND currencyCode = :currencyCode
          AND categoryId = :categoryId
          AND timestamp >= :startMillis AND timestamp < :endMillis
        """
    )
    suspend fun sumDebitsInRangeForCategory(
        currencyCode: String,
        categoryId: Long,
        startMillis: Long,
        endMillis: Long,
    ): Double

    /**
     * One merchant's real, analytics-eligible debit history, oldest first --
     * the input [com.xemmy.mmbank.domain.intelligence.SubscriptionRadarEngine]
     * needs to re-detect that merchant after a new charge lands, without
     * loading the whole ledger on the capture path.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE isDemo = 0 AND excludeFromStats = 0 AND kind = 'SPEND'
          AND merchant IS NOT NULL AND LOWER(TRIM(merchant)) = LOWER(TRIM(:merchant))
        ORDER BY timestamp ASC
        """
    )
    suspend fun findRealDebitsByMerchant(merchant: String): List<TransactionEntity>

    /**
     * The persons the user explicitly assigned to the two most recent rows at
     * [merchant], newest first -- see PersonLearner. Only `USER` rows count: a
     * row that merely received the default person says nothing about intent.
     */
    @Query(
        """
        SELECT person FROM transactions
        WHERE isDemo = 0 AND personSource = 'USER' AND person IS NOT NULL AND TRIM(person) != ''
          AND merchant IS NOT NULL AND LOWER(TRIM(merchant)) = LOWER(TRIM(:merchant))
        ORDER BY timestamp DESC, id DESC
        LIMIT 2
        """
    )
    suspend fun lastUserPersonsForMerchant(merchant: String): List<String>

    /**
     * Still-unlinked, opposite-direction, same amount+currency real rows
     * within a broad time bound, for [com.xemmy.mmbank.domain.usecase.TransferMatcher]
     * to narrow down to a final counterpart. `linkedTransactionId IS NULL`
     * means an already-linked pair can never be reused by a later match.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amount = :amount
          AND currencyCode = :currencyCode
          AND direction != :direction
          AND isDemo = 0
          AND linkedTransactionId IS NULL
          AND ABS(timestamp - :timestamp) <= :windowMillis
        ORDER BY ABS(timestamp - :timestamp) ASC
        """
    )
    suspend fun findUnlinkedOppositeDirectionCandidates(
        amount: BigDecimal,
        currencyCode: String,
        direction: TransactionDirection,
        timestamp: Long,
        windowMillis: Long,
    ): List<TransactionEntity>

    /**
     * Marks a transfer-reconciliation match: both sides call this with each
     * other's id. The [kind] (TRANSFER or CARD_PAYMENT) is what keeps both legs
     * out of Spent/Received; a kind the user set by hand is left alone.
     */
    @Query(
        "UPDATE transactions SET linkedTransactionId = :linkedId, spendingCategory = :category, " +
            "kind = CASE WHEN kindUserSet = 1 THEN kind ELSE :kind END " +
            "WHERE id = :id"
    )
    suspend fun linkAsTransfer(
        id: Long,
        linkedId: Long,
        kind: com.xemmy.mmbank.domain.model.TransactionKind,
        category: com.xemmy.mmbank.domain.model.SpendingCategory,
    )

    /** Rows linked to any of [ids] that are not themselves in [ids] -- the partners a delete would orphan. */
    @Query("SELECT * FROM transactions WHERE linkedTransactionId IN (:ids) AND id NOT IN (:ids)")
    suspend fun getLinkedPartners(ids: List<Long>): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    /** The rows behind [ids], read before a bulk edit or delete so the account change log can say what moved. */
    @Query("SELECT * FROM transactions WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TransactionEntity>

    /** The rows [assignAccountByRuleName] is about to move. */
    @Query("SELECT * FROM transactions WHERE matchedRuleName = :ruleName AND accountId IS NULL")
    suspend fun getUnassignedByRuleName(ruleName: String): List<TransactionEntity>

    /**
     * Real rows a learned merchant -> kind rule may re-classify: never one
     * the user classified by hand, never a reconciled transfer leg.
     */
    @Query(
        "SELECT * FROM transactions WHERE kindUserSet = 0 AND linkedTransactionId IS NULL " +
            "AND merchant IS NOT NULL AND isDemo = 0"
    )
    suspend fun getAutoClassifiedWithMerchant(): List<TransactionEntity>

    // --- Backup support ---

    // Backups carry only real rows — demo data must never leak into an export.
    @Query("SELECT * FROM transactions WHERE isDemo = 0")
    suspend fun getAll(): List<TransactionEntity>

    @Query("DELETE FROM transactions")
    suspend fun clearAll()

    @Insert
    suspend fun insertAll(entities: List<TransactionEntity>)
}

@Dao
interface AccountDao {

    @Insert
    suspend fun insert(entity: AccountEntity): Long

    @Update
    suspend fun update(entity: AccountEntity)

    @Delete
    suspend fun delete(entity: AccountEntity)

    /** [demo] selects the active partition: real accounts (false) or demo accounts (true). */
    @Query("SELECT * FROM accounts WHERE archived = 0 AND isDemo = :demo ORDER BY name")
    fun observeActive(demo: Boolean): Flow<List<AccountEntity>>

    /** Archived accounts in the [demo] partition -- the Accounts screen's "Archived" section. */
    @Query("SELECT * FROM accounts WHERE archived = 1 AND isDemo = :demo ORDER BY name")
    fun observeArchived(demo: Boolean): Flow<List<AccountEntity>>

    // Capture auto-assignment only ever binds to REAL accounts, so a demo
    // account can never end up owning a real transaction.
    @Query("SELECT * FROM accounts WHERE archived = 0 AND isDemo = 0 ORDER BY name")
    suspend fun getActive(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    /**
     * Rewrites a derived balance's only stored input. Used by the retention
     * prune to fold the net of the rows it deletes back into the opening
     * balance, so the balance on screen does not move when history is trimmed
     * (see [com.xemmy.mmbank.domain.account.PrunedBalanceFolder]).
     */
    @Query("UPDATE accounts SET openingBalance = :openingBalance WHERE id = :id")
    suspend fun setOpeningBalance(id: Long, openingBalance: BigDecimal)

    /** Removes every demo account. Delete demo transactions FIRST (FK SET_NULL). */
    @Query("DELETE FROM accounts WHERE isDemo = 1")
    suspend fun deleteDemo(): Int

    @Query("SELECT COUNT(*) FROM accounts WHERE isDemo = 1")
    suspend fun countDemo(): Int

    // --- Backup support ---

    // Backups carry only real rows — demo data must never leak into an export.
    @Query("SELECT * FROM accounts WHERE isDemo = 0")
    suspend fun getAll(): List<AccountEntity>

    @Query("DELETE FROM accounts")
    suspend fun clearAll()

    @Insert
    suspend fun insertAll(entities: List<AccountEntity>)
}

@Dao
interface ParsingRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ParsingRuleEntity): Long

    @Query("DELETE FROM parsing_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Detaches every parsing rule's "default assign" pointer at [accountId]
     * before the account is deleted -- ParsingRuleEntity.assignAccountId
     * carries no real FK (unlike TransactionEntity.accountId), so a rule left
     * pointing at a deleted account can make the next matching capture's
     * accountId write violate transactions' real FK. Mirrors
     * CategoryDao.clearParent.
     */
    @Query("UPDATE parsing_rules SET assignAccountId = NULL WHERE assignAccountId = :accountId")
    suspend fun clearAssignAccountId(accountId: Long): Int

    /** [demo] selects the active partition: real rules (false) or demo rules (true). */
    @Query("SELECT * FROM parsing_rules WHERE isDemo = :demo ORDER BY priority DESC, name")
    fun observeAll(demo: Boolean): Flow<List<ParsingRuleEntity>>

    // The parser only ever runs REAL rules — a real message captured during a
    // demo must be matched against the user's real rules, not the sample ones.
    @Query("SELECT * FROM parsing_rules WHERE enabled = 1 AND isDemo = 0 ORDER BY priority DESC")
    suspend fun getEnabled(): List<ParsingRuleEntity>

    @Query("SELECT * FROM parsing_rules WHERE id = :id")
    suspend fun getById(id: Long): ParsingRuleEntity?

    /** Removes every demo rule. */
    @Query("DELETE FROM parsing_rules WHERE isDemo = 1")
    suspend fun deleteDemo(): Int

    @Query("SELECT COUNT(*) FROM parsing_rules WHERE isDemo = 1")
    suspend fun countDemo(): Int

    // --- Backup support ---

    // Backups carry only real rules — demo data must never leak into an export.
    @Query("SELECT * FROM parsing_rules WHERE isDemo = 0")
    suspend fun getAll(): List<ParsingRuleEntity>

    @Query("DELETE FROM parsing_rules")
    suspend fun clearAll()

    @Insert
    suspend fun insertAll(entities: List<ParsingRuleEntity>)
}

@Dao
interface IgnoredMessagePatternDao {

    @Query("SELECT * FROM ignored_message_patterns ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<IgnoredMessagePatternEntity>>

    /** REPLACE on the unique fingerprint: re-adding the same template refreshes it, never duplicates it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IgnoredMessagePatternEntity): Long

    @Query("DELETE FROM ignored_message_patterns WHERE id = :id")
    suspend fun deleteById(id: Long)

    // --- Backup support ---

    @Query("SELECT * FROM ignored_message_patterns")
    suspend fun getAll(): List<IgnoredMessagePatternEntity>

    @Query("DELETE FROM ignored_message_patterns")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<IgnoredMessagePatternEntity>)
}

@Dao
interface SenderLabelDao {

    @Query("SELECT * FROM sender_labels")
    fun observeAll(): Flow<List<SenderLabelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SenderLabelEntity)

    @Query("DELETE FROM sender_labels WHERE sender = :sender")
    suspend fun delete(sender: String)

    // --- Backup support ---

    @Query("SELECT * FROM sender_labels")
    suspend fun getAll(): List<SenderLabelEntity>

    @Query("DELETE FROM sender_labels")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SenderLabelEntity>)
}

@Dao
interface AssetBalanceDao {

    @Query("SELECT * FROM asset_balances ORDER BY valueUsd DESC")
    fun observeAll(): Flow<List<AssetBalanceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<AssetBalanceEntity>)

    @Query("DELETE FROM asset_balances")
    suspend fun clear()
}

@Dao
interface WalletBalanceDao {

    @Query("SELECT * FROM wallet_balances ORDER BY valueUsd DESC")
    fun observeAll(): Flow<List<WalletBalanceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<WalletBalanceEntity>)

    @Query("DELETE FROM wallet_balances")
    suspend fun clear()
}

@Dao
interface ExchangeRateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ExchangeRateEntity>)

    @Query("SELECT * FROM exchange_rates")
    fun observeAll(): Flow<List<ExchangeRateEntity>>
}

@Dao
interface ExchangeRateHistoryDao {

    /** One row per (base, quote, day): a later refresh the same day replaces the earlier one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ExchangeRateHistoryEntity>)

    @Query("SELECT * FROM exchange_rates_history WHERE baseCode = :baseCode ORDER BY day ASC")
    fun observeForBase(baseCode: String): Flow<List<ExchangeRateHistoryEntity>>

    /** Every currency an account or a transaction is in -- the only ones worth keeping a history for. */
    @Query("SELECT DISTINCT currencyCode FROM accounts UNION SELECT DISTINCT currencyCode FROM transactions")
    suspend fun heldCurrencyCodes(): List<String>
}

@Dao
interface SubscriptionDao {

    /** Replaces the detection for a merchant on every re-scan (one row per merchant, see the unique index). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SubscriptionEntity): Long

    @Query("SELECT * FROM subscriptions WHERE dismissed = 0 ORDER BY nextDueDateMillis ASC")
    fun observeActive(): Flow<List<SubscriptionEntity>>

    @Query("UPDATE subscriptions SET dismissed = 1 WHERE id = :id")
    suspend fun dismiss(id: Long)

    @Query("SELECT * FROM subscriptions")
    suspend fun getAll(): List<SubscriptionEntity>
}

@Dao
interface BillDao {

    @Insert
    suspend fun insert(entity: BillEntity): Long

    @Update
    suspend fun update(entity: BillEntity)

    @Query("SELECT * FROM bills ORDER BY nextDueDateMillis ASC")
    fun observeAll(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getById(id: Long): BillEntity?

    @Query("UPDATE bills SET nextDueDateMillis = :nextDueDateMillis WHERE id = :id")
    suspend fun updateNextDueDate(id: Long, nextDueDateMillis: Long)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun delete(id: Long)

    // --- Backup support ---

    @Query("SELECT * FROM bills")
    suspend fun getAll(): List<BillEntity>

    @Query("DELETE FROM bills")
    suspend fun clearAll()

    @Insert
    suspend fun insertAll(entities: List<BillEntity>)

}

@Dao
interface SavingsGoalDao {

    @Insert
    suspend fun insert(entity: SavingsGoalEntity): Long

    @Update
    suspend fun update(entity: SavingsGoalEntity)

    @Query("SELECT * FROM savings_goals WHERE archived = 0 ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<SavingsGoalEntity>>

    @Query("SELECT * FROM savings_goals WHERE id = :id")
    suspend fun getById(id: Long): SavingsGoalEntity?

    @Query("UPDATE savings_goals SET currentAmount = :amount WHERE id = :id")
    suspend fun setCurrentAmount(id: Long, amount: BigDecimal)

    @Query("UPDATE savings_goals SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("DELETE FROM savings_goals WHERE id = :id")
    suspend fun delete(id: Long)

    // --- Backup support ---

    @Query("SELECT * FROM savings_goals")
    suspend fun getAll(): List<SavingsGoalEntity>

    @Query("DELETE FROM savings_goals")
    suspend fun clearAll()

    @Insert
    suspend fun insertAll(entities: List<SavingsGoalEntity>)

}

@Dao
interface FriendDao {

    @Insert
    suspend fun insert(entity: FriendEntity): Long

    @Update
    suspend fun update(entity: FriendEntity)

    @Query("SELECT * FROM friends WHERE archived = 0 ORDER BY name")
    fun observeActive(): Flow<List<FriendEntity>>

    @Query("UPDATE friends SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    // --- Backup support ---

    @Query("SELECT * FROM friends")
    suspend fun getAll(): List<FriendEntity>

    @Query("DELETE FROM friends")
    suspend fun clearAll()

    @Insert
    suspend fun insertAll(entities: List<FriendEntity>)

}

@Dao
interface SplitGroupDao {

    @Insert
    suspend fun insert(entity: SplitGroupEntity): Long

    @Update
    suspend fun update(entity: SplitGroupEntity)

    @Query("SELECT * FROM split_groups WHERE archived = 0 ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<SplitGroupEntity>>

    @Query("UPDATE split_groups SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    /** Replaces a group's whole member list -- simpler than diffing adds/removes for a handful of friends. */
    @Query("DELETE FROM split_group_members WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: Long)

    @Insert
    suspend fun insertMembers(members: List<SplitGroupMemberEntity>)

    @Query("SELECT * FROM split_group_members")
    fun observeAllMemberships(): Flow<List<SplitGroupMemberEntity>>

    // --- Backup support ---

    @Query("SELECT * FROM split_groups")
    suspend fun getAll(): List<SplitGroupEntity>

    @Query("DELETE FROM split_groups")
    suspend fun clearAll()

    @Insert
    suspend fun insertAll(entities: List<SplitGroupEntity>)

    @Query("SELECT * FROM split_group_members")
    suspend fun getAllMembers(): List<SplitGroupMemberEntity>

}

@Dao
interface GroupExpenseDao {

    @Insert
    suspend fun insert(entity: GroupExpenseEntity): Long

    @Update
    suspend fun update(entity: GroupExpenseEntity)

    @Query("SELECT * FROM group_expenses WHERE id = :id")
    suspend fun getById(id: Long): GroupExpenseEntity?

    /** Every group's expenses at once -- the Splits & Groups header balance needs all of them together. */
    @Query("SELECT * FROM group_expenses")
    fun observeAll(): Flow<List<GroupExpenseEntity>>

    // ON DELETE CASCADE on expense_splits.expenseId removes its splits too.
    @Query("DELETE FROM group_expenses WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertSplits(splits: List<ExpenseSplitEntity>)

    /** Clears an expense's splits before re-inserting the edited set wholesale -- simpler than diffing. */
    @Query("DELETE FROM expense_splits WHERE expenseId = :expenseId")
    suspend fun deleteSplitsForExpense(expenseId: Long)

    @Query("SELECT * FROM expense_splits WHERE expenseId = :expenseId")
    suspend fun getSplitsForExpense(expenseId: Long): List<ExpenseSplitEntity>

    @Query("SELECT * FROM expense_splits")
    fun observeAllSplits(): Flow<List<ExpenseSplitEntity>>

    // --- Backup support ---

    @Query("SELECT * FROM group_expenses")
    suspend fun getAll(): List<GroupExpenseEntity>

    @Query("SELECT * FROM expense_splits")
    suspend fun getAllSplits(): List<ExpenseSplitEntity>

    @Insert
    suspend fun insertAll(entities: List<GroupExpenseEntity>)

}

@Dao
interface IgnoredKeywordDao {

    @Query("SELECT * FROM ignored_keywords ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<IgnoredKeywordEntity>>

    /** REPLACE on the unique keyword: re-adding refreshes the row, never duplicates it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IgnoredKeywordEntity): Long

    @Query("DELETE FROM ignored_keywords WHERE id = :id")
    suspend fun deleteById(id: Long)

    // --- Backup support ---

    @Query("SELECT * FROM ignored_keywords")
    suspend fun getAll(): List<IgnoredKeywordEntity>

    @Query("DELETE FROM ignored_keywords")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<IgnoredKeywordEntity>)
}

@Dao
interface AccountChangeDao {

    @Insert
    suspend fun insert(entity: AccountChangeEntity): Long

    @Insert
    suspend fun insertAll(entities: List<AccountChangeEntity>)

    /** Newest first; [id] breaks ties so same-instant entries keep the order they were written in. */
    @Query("SELECT * FROM account_changes WHERE accountId = :accountId ORDER BY timestamp DESC, id DESC")
    fun observeForAccount(accountId: Long): Flow<List<AccountChangeEntity>>

    // Backups carry only real rows -- demo data must never leak into an export.
    @Query("SELECT * FROM account_changes WHERE isDemo = 0 ORDER BY id")
    suspend fun getAll(): List<AccountChangeEntity>

    @Query("DELETE FROM account_changes")
    suspend fun clearAll()

    /** Log rows older than [cutoffMillis] go with the transactions they describe (retention clean-up). */
    @Query("DELETE FROM account_changes WHERE timestamp < :cutoffMillis AND isDemo = 0")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    /** Removes the log of demo accounts when Demo mode is switched off. */
    @Query("DELETE FROM account_changes WHERE isDemo = 1")
    suspend fun deleteDemo(): Int
}
