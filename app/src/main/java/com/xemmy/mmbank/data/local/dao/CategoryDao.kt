package com.xemmy.mmbank.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xemmy.mmbank.data.local.entity.CategoryEntity
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

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
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
}
