package com.xemmy.mmbank.data.repository

import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.entity.MerchantCategoryMappingEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.data.local.entity.toEntity
import androidx.room.withTransaction
import com.xemmy.mmbank.domain.budget.CategoryMerge
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.CategoryFlow
import kotlinx.coroutines.flow.first
import com.xemmy.mmbank.domain.model.MerchantCategoryMapping
import com.xemmy.mmbank.domain.parser.CategoryMatcher
import com.xemmy.mmbank.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CategoryRepositoryImpl(private val database: AppDatabase) : CategoryRepository {

    private val categoryDao get() = database.categoryDao()
    private val mappingDao get() = database.merchantCategoryDao()

    override fun observeAll(): Flow<List<Category>> =
        categoryDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeMappings(): Flow<List<MerchantCategoryMapping>> =
        mappingDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: Long): Category? = categoryDao.getById(id)?.toDomain()

    override suspend fun save(category: Category): Long =
        if (category.id == 0L) {
            // A new category goes to the end of the list unless the caller chose a position.
            val order = if (category.sortOrder == 0) categoryDao.maxSortOrder() + 1 else category.sortOrder
            categoryDao.insert(category.copy(sortOrder = order).toEntity())
        } else {
            categoryDao.update(category.toEntity())
            category.id
        }

    override suspend fun delete(id: Long) {
        categoryDao.clearParent(id)
        categoryDao.deleteById(id)
    }

    override suspend fun setOrder(orderedIds: List<Long>) {
        database.withTransaction {
            orderedIds.forEachIndexed { index, id -> categoryDao.setSortOrder(id, index) }
        }
    }

    override suspend fun setFlow(id: Long, flow: CategoryFlow) = categoryDao.setFlow(id, flow.name)

    override suspend fun merge(fromId: Long, intoId: Long): Boolean {
        var ok = false
        database.withTransaction {
            val all = categoryDao.observeAll().first().map { it.toDomain() }
            val plan = CategoryMerge.plan(all, fromId, intoId) ?: return@withTransaction
            database.transactionDao().reassignCategory(fromId, intoId, false)
            database.transactionDao().reassignCategory(fromId, intoId, true)
            repointMappings(fromId, intoId)
            categoryDao.reparentChildren(fromId, intoId)
            val into = categoryDao.getById(intoId) ?: return@withTransaction
            categoryDao.update(into.copy(parentCategoryId = plan.newParentOfInto, monthlyCap = plan.newCap))
            categoryDao.deleteById(fromId)
            ok = true
        }
        return ok
    }

    override suspend fun repointMappings(oldCategoryId: Long, newCategoryId: Long?): Int =
        if (newCategoryId != null) {
            mappingDao.repointCategory(oldCategoryId, newCategoryId)
        } else {
            // A rule that resolves to nothing has no meaning -- drop it and let
            // the merchant fall back to the uncategorized/AI-suggestion path.
            mappingDao.deleteByCategory(oldCategoryId)
        }

    override suspend fun findCategoryForMerchant(merchant: String): Long? {
        if (merchant.isBlank()) return null
        val mappings = mappingDao.getConfirmed().map { it.toDomain() }
        return CategoryMatcher.match(merchant, mappings)
    }

    override suspend fun findPendingSuggestion(merchant: String): MerchantCategoryMapping? =
        if (merchant.isBlank()) null else mappingDao.findPending(merchant)?.toDomain()

    override suspend fun findCategoryIdByName(name: String): Long? = categoryDao.getByName(name)?.id

    override suspend fun learnMapping(merchant: String, categoryId: Long, isExactMatch: Boolean): Int {
        mappingDao.upsert(
            MerchantCategoryMappingEntity(
                merchantPattern = merchant,
                categoryId = categoryId,
                isExactMatch = isExactMatch,
                pending = false,
            )
        )
        return database.transactionDao().backfillCategoryByMerchant(merchant, categoryId)
    }

    override suspend fun suggestPending(merchant: String, name: String, icon: String?, colorHex: String?): Int {
        mappingDao.upsert(
            MerchantCategoryMappingEntity(
                merchantPattern = merchant,
                categoryId = null,
                isExactMatch = true,
                pending = true,
                suggestedName = name,
                suggestedIcon = icon,
                suggestedColorHex = colorHex,
            )
        )
        return database.transactionDao().backfillCategorySuggestionByMerchant(merchant, name, icon, colorHex)
    }
}
