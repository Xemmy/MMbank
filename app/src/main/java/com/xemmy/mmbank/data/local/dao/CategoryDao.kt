package com.xemmy.mmbank.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xemmy.mmbank.data.local.entity.CategoryEntity
import com.xemmy.mmbank.data.local.entity.MerchantAccountMappingEntity
import com.xemmy.mmbank.data.local.entity.MerchantCategoryMappingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert
    suspend fun insert(entity: CategoryEntity): Long

    @Update
    suspend fun update(entity: CategoryEntity)

    @Query("SELECT * FROM categories ORDER BY isCustom, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): CategoryEntity?

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Detaches every direct child of [parentId] before it's deleted, so they
     * become top-level in the data itself rather than only in
     * [com.xemmy.mmbank.domain.budget.CategoryHierarchy.buildTree]'s defensive
     * fallback for an unresolvable parent.
     */
    @Query("UPDATE categories SET parentCategoryId = NULL WHERE parentCategoryId = :parentId")
    suspend fun clearParent(parentId: Long): Int

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    // --- Backup support ---

    @Query("SELECT * FROM categories")
    suspend fun getAll(): List<CategoryEntity>

    @Query("DELETE FROM categories")
    suspend fun clearAll()

    @Insert
    suspend fun insertAll(entities: List<CategoryEntity>)
}

/**
 * Learned merchant -> category rules, plus unconfirmed AI guesses ([pending]
 * = 1) awaiting human approval in the Uncategorized queue. [merchantPattern]
 * carries a unique index so re-suggesting the same merchant replaces the
 * previous row instead of accumulating duplicates.
 */
@Dao
interface MerchantCategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MerchantCategoryMappingEntity): Long

    @Query("SELECT * FROM merchant_category_mappings ORDER BY isExactMatch DESC")
    fun observeAll(): Flow<List<MerchantCategoryMappingEntity>>

    /** Confirmed rules only -- what [com.xemmy.mmbank.domain.parser.CategoryMatcher] matches against. */
    @Query("SELECT * FROM merchant_category_mappings WHERE pending = 0 ORDER BY isExactMatch DESC")
    suspend fun getConfirmed(): List<MerchantCategoryMappingEntity>

    @Query("SELECT * FROM merchant_category_mappings WHERE pending = 1 AND merchantPattern = :merchant COLLATE NOCASE LIMIT 1")
    suspend fun findPending(merchant: String): MerchantCategoryMappingEntity?

    @Query("DELETE FROM merchant_category_mappings WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Points every learned rule for [oldCategoryId] at [newCategoryId] when its
     * category is deleted and its transactions are reassigned. Without this the
     * rule keeps resolving to a category that no longer exists, and the next
     * transaction from that merchant is filed onto the dead id -- quietly
     * undoing the reassignment the user just made.
     */
    @Query("UPDATE merchant_category_mappings SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun repointCategory(oldCategoryId: Long, newCategoryId: Long): Int

    /** Drops learned rules for a deleted category when no replacement was chosen. */
    @Query("DELETE FROM merchant_category_mappings WHERE categoryId = :categoryId")
    suspend fun deleteByCategory(categoryId: Long): Int

    // --- Backup support ---

    @Query("SELECT * FROM merchant_category_mappings")
    suspend fun getAll(): List<MerchantCategoryMappingEntity>

    @Query("DELETE FROM merchant_category_mappings")
    suspend fun clearAll()

    @Insert
    suspend fun insertAll(entities: List<MerchantCategoryMappingEntity>)
}

/** A still-unassigned transaction's id and merchant, for learned-rule backfill. */
data class UnassignedMerchantRow(val id: Long, val merchant: String)

/**
 * Learned merchant -> account rules. Full CRUD rather than
 * [MerchantCategoryDao]'s pending/confirmed split: there is no AI path
 * proposing accounts, so every row here exists because the user assigned one.
 */
@Dao
interface MerchantAccountDao {

    /**
     * REPLACE on conflict, against a `COLLATE NOCASE` unique index on
     * merchantPattern, so re-assigning the same merchant updates its rule
     * rather than accumulating a second contradictory one.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MerchantAccountMappingEntity): Long

    @Query("SELECT * FROM merchant_account_mappings")
    suspend fun getAll(): List<MerchantAccountMappingEntity>

    @Query("SELECT * FROM merchant_account_mappings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MerchantAccountMappingEntity>>

    @Query("SELECT * FROM merchant_account_mappings WHERE accountId = :accountId")
    suspend fun getForAccount(accountId: Long): List<MerchantAccountMappingEntity>

    @Query("DELETE FROM merchant_account_mappings WHERE merchantPattern = :merchant COLLATE NOCASE")
    suspend fun deleteByMerchant(merchant: String): Int

    // --- Backup support ---

    @Query("DELETE FROM merchant_account_mappings")
    suspend fun clearAll()

    @Insert
    suspend fun insertAll(entities: List<MerchantAccountMappingEntity>)
}
