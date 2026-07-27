package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BulkUpdateTransactionsUseCaseTest {

    private class RecordingBulkRepository : TransactionRepository {
        var lastCategoryIds: List<Long>? = null
        var lastCategory: SpendingCategory? = null
        var lastExcludeIds: List<Long>? = null
        var lastExcluded: Boolean? = null
        var lastDeletedIds: List<Long>? = null

        override suspend fun bulkSetCategory(ids: List<Long>, category: SpendingCategory): Int {
            lastCategoryIds = ids
            lastCategory = category
            return ids.size
        }

        override suspend fun bulkSetExcludeFromStats(ids: List<Long>, excluded: Boolean): Int {
            lastExcludeIds = ids
            lastExcluded = excluded
            return ids.size
        }

        override suspend fun deleteMany(ids: List<Long>): Int {
            lastDeletedIds = ids
            return ids.size
        }

        override fun observeTransactions(): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeRecent(limit: Int): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeNetByAccount(): Flow<Map<Long, Double>> = flowOf(emptyMap())
        override suspend fun recordParsed(parsed: ParsedTransaction, message: CapturedMessage): IngestResult =
            IngestResult.Inserted(1L)
        override suspend fun recordManual(transaction: Transaction): Long = 1L
        override suspend fun update(transaction: Transaction) = Unit
        override suspend fun assignAccount(transactionId: Long, accountId: Long?) = Unit
        override suspend fun assignAccountForRule(ruleName: String, accountId: Long): Int = 0
        override suspend fun setPerson(transactionId: Long, person: String?) = Unit
        override suspend fun setSpendingCategory(transactionId: Long, category: SpendingCategory) = Unit
        override suspend fun renamePerson(from: String, to: String?) = Unit
        override suspend fun delete(transactionId: Long) = Unit
        override fun observeUncategorized(): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun setCategory(transactionId: Long, categoryId: Long?) = Unit
        override suspend fun dismissCategorySuggestion(transactionId: Long) = Unit
    }

    private val repo = RecordingBulkRepository()
    private val useCase = BulkUpdateTransactionsUseCase(repo)

    @Test
    fun `patch with only category set calls bulkSetCategory and leaves exclude untouched`() = runBlocking {
        useCase(listOf(1L, 2L, 3L), BulkTransactionPatch(spendingCategory = SpendingCategory.GROCERIES))
        assertEquals(listOf(1L, 2L, 3L), repo.lastCategoryIds)
        assertEquals(SpendingCategory.GROCERIES, repo.lastCategory)
        assertNull(repo.lastExcludeIds)
    }

    @Test
    fun `patch with only excludeFromStats set calls bulkSetExcludeFromStats and leaves category untouched`() = runBlocking {
        useCase(listOf(4L, 5L), BulkTransactionPatch(excludeFromStats = true))
        assertEquals(listOf(4L, 5L), repo.lastExcludeIds)
        assertEquals(true, repo.lastExcluded)
        assertNull(repo.lastCategoryIds)
    }

    @Test
    fun `patch with both fields set calls both repository methods`() = runBlocking {
        useCase(listOf(6L), BulkTransactionPatch(spendingCategory = SpendingCategory.DINING, excludeFromStats = false))
        assertEquals(listOf(6L), repo.lastCategoryIds)
        assertEquals(SpendingCategory.DINING, repo.lastCategory)
        assertEquals(listOf(6L), repo.lastExcludeIds)
        assertEquals(false, repo.lastExcluded)
    }

    @Test
    fun `empty ids list short-circuits without calling the repository`() = runBlocking {
        val affected = useCase(emptyList(), BulkTransactionPatch(spendingCategory = SpendingCategory.GROCERIES))
        assertEquals(0, affected)
        assertNull(repo.lastCategoryIds)
    }

    @Test
    fun `delete calls deleteMany with the given ids`() = runBlocking {
        useCase.delete(listOf(7L, 8L))
        assertEquals(listOf(7L, 8L), repo.lastDeletedIds)
    }

    @Test
    fun `delete with empty ids list short-circuits to zero without calling the repository`() = runBlocking {
        val affected = useCase.delete(emptyList())
        assertEquals(0, affected)
        assertNull(repo.lastDeletedIds)
    }
}
