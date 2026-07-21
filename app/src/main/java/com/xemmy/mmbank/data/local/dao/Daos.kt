package com.xemmy.mmbank.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.AssetBalanceEntity
import com.xemmy.mmbank.data.local.entity.ExchangeRateEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.SenderLabelEntity
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

    @Query("UPDATE transactions SET person = :to WHERE person = :from")
    suspend fun renamePerson(from: String, to: String?)

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
