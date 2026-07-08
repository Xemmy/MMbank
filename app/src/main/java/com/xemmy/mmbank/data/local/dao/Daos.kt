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

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT accountId,
               SUM(CASE WHEN direction = 'CREDIT' THEN CAST(amount AS REAL)
                        ELSE -CAST(amount AS REAL) END) AS net
        FROM transactions
        WHERE accountId IS NOT NULL
        GROUP BY accountId
        """
    )
    fun observeNetByAccount(): Flow<List<AccountNetRow>>

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

    @Query("UPDATE transactions SET person = :to WHERE person = :from")
    suspend fun renamePerson(from: String, to: String?)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    // --- Backup support ---

    @Query("SELECT * FROM transactions")
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

    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY name")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY name")
    suspend fun getActive(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    // --- Backup support ---

    @Query("SELECT * FROM accounts")
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

    @Query("SELECT * FROM parsing_rules ORDER BY priority DESC, name")
    fun observeAll(): Flow<List<ParsingRuleEntity>>

    @Query("SELECT * FROM parsing_rules WHERE enabled = 1 ORDER BY priority DESC")
    suspend fun getEnabled(): List<ParsingRuleEntity>

    @Query("SELECT * FROM parsing_rules WHERE id = :id")
    suspend fun getById(id: Long): ParsingRuleEntity?

    // --- Backup support ---

    @Query("SELECT * FROM parsing_rules")
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
