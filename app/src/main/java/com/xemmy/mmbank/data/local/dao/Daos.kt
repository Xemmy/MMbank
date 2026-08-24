package com.xemmy.mmbank.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.AssetBalanceEntity
import com.xemmy.mmbank.data.local.entity.BillEntity
import com.xemmy.mmbank.data.local.entity.ExchangeRateEntity
import com.xemmy.mmbank.data.local.entity.ExpenseSplitEntity
import com.xemmy.mmbank.data.local.entity.FriendEntity
import com.xemmy.mmbank.data.local.entity.GroupExpenseEntity
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

/** Net movement (credits - debits) per account; joined onto accounts for balances. */
data class AccountNetRow(
    val accountId: Long,
    val net: Double,
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

    /** [demo] selects the active partition: real rows (false) or demo rows (true). */
    @Query("SELECT * FROM transactions WHERE isDemo = :demo ORDER BY timestamp DESC")
    fun observeAll(demo: Boolean): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE isDemo = :demo ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(demo: Boolean, limit: Int): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT accountId,
               SUM(CASE WHEN direction = 'CREDIT' THEN CAST(amount AS REAL)
                        ELSE -CAST(amount AS REAL) END) AS net
        FROM transactions
        WHERE accountId IS NOT NULL AND isDemo = :demo
        GROUP BY accountId
        """
    )
    fun observeNetByAccount(demo: Boolean): Flow<List<AccountNetRow>>

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

    @Query("UPDATE transactions SET person = :person WHERE id = :transactionId")
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

    /** Multi-select edit: assigns (or clears, when null) the person on every row in [ids] at once. Returns affected count. */
    @Query("UPDATE transactions SET person = :person WHERE id IN (:ids)")
    suspend fun bulkSetPerson(ids: List<Long>, person: String?): Int

    // --- Dynamic category assignment (see CategoryRepository / CategoryMatcher) ---

    /** Assigns a dynamic category and clears any pending AI suggestion on the row (manual pick or accept). */
    @Query(
        "UPDATE transactions SET categoryId = :categoryId, needsCategoryApproval = 0, " +
            "suggestedCategoryName = NULL, suggestedCategoryIcon = NULL, suggestedCategoryColorHex = NULL " +
            "WHERE id = :transactionId"
    )
    suspend fun setCategory(transactionId: Long, categoryId: Long?)

    /** Multi-select edit: assigns (or clears, when null) a dynamic category on every row in [ids] at once. Returns affected count. */
    @Query(
        "UPDATE transactions SET categoryId = :categoryId, needsCategoryApproval = 0, " +
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
            "suggestedCategoryName = NULL, suggestedCategoryIcon = NULL, suggestedCategoryColorHex = NULL " +
            "WHERE categoryId = :oldCategoryId AND isDemo = :demo"
    )
    suspend fun reassignCategory(oldCategoryId: Long, newCategoryId: Long?, demo: Boolean): Int

    /**
     * Backfills a newly learned/confirmed merchant->category rule onto every
     * still-uncategorized real row from that merchant (mirrors
     * [assignAccountByRuleName]'s backfill pattern). Returns the count.
     */
    @Query(
        "UPDATE transactions SET categoryId = :categoryId, needsCategoryApproval = 0, " +
            "suggestedCategoryName = NULL, suggestedCategoryIcon = NULL, suggestedCategoryColorHex = NULL " +
            "WHERE merchant = :merchant COLLATE NOCASE AND categoryId IS NULL"
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
            "(needsCategoryApproval = 1 OR categoryId IS NULL) " +
            "ORDER BY timestamp DESC"
    )
    fun observeUncategorized(demo: Boolean): Flow<List<TransactionEntity>>

    /** Total real debit spend (analytics-eligible) in [currencyCode] within [ [startMillis], [endMillis] ) -- feeds [com.xemmy.mmbank.domain.budget.DailyBudgetPacing]. */
    @Query(
        """
        SELECT COALESCE(SUM(CAST(amount AS REAL)), 0.0) FROM transactions
        WHERE isDemo = 0 AND excludeFromStats = 0 AND direction = 'DEBIT' AND currencyCode = :currencyCode
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
        SELECT COALESCE(SUM(CAST(amount AS REAL)), 0.0) FROM transactions
        WHERE isDemo = 0 AND excludeFromStats = 0 AND direction = 'DEBIT' AND currencyCode = :currencyCode
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
        WHERE isDemo = 0 AND excludeFromStats = 0 AND direction = 'DEBIT'
          AND merchant IS NOT NULL AND LOWER(TRIM(merchant)) = LOWER(TRIM(:merchant))
        ORDER BY timestamp ASC
        """
    )
    suspend fun findRealDebitsByMerchant(merchant: String): List<TransactionEntity>

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

    /** Marks a transfer-reconciliation match: both sides call this with each other's id. */
    @Query(
        "UPDATE transactions SET linkedTransactionId = :linkedId, excludeFromStats = 1, spendingCategory = :category " +
            "WHERE id = :id"
    )
    suspend fun linkAsTransfer(id: Long, linkedId: Long, category: com.xemmy.mmbank.domain.model.SpendingCategory)

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

    // Capture auto-assignment only ever binds to REAL accounts, so a demo
    // account can never end up owning a real transaction.
    @Query("SELECT * FROM accounts WHERE archived = 0 AND isDemo = 0 ORDER BY name")
    suspend fun getActive(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

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
}
