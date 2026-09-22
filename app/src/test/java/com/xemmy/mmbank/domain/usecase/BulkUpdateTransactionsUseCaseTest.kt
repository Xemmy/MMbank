package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.account.AccountBalanceCalculator
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
        var lastAccountIds: List<Long>? = null
        var lastAccountId: Long? = null
        var accountAssignCalled = false
        var lastPersonIds: List<Long>? = null
        var lastPerson: String? = null
        var personSetCalled = false
        var lastCategoryIdSetIds: List<Long>? = null
        var lastCategoryIdSet: Long? = null
        var categoryIdSetCalled = false

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

        override suspend fun bulkAssignAccount(ids: List<Long>, accountId: Long?): Int {
            lastAccountIds = ids
            lastAccountId = accountId
            accountAssignCalled = true
            return ids.size
        }

        override suspend fun bulkSetPerson(ids: List<Long>, person: String?): Int {
            lastPersonIds = ids
            lastPerson = person
            personSetCalled = true
            return ids.size
        }

        override suspend fun bulkSetCategoryId(ids: List<Long>, categoryId: Long?): Int {
            lastCategoryIdSetIds = ids
            lastCategoryIdSet = categoryId
            categoryIdSetCalled = true
            return ids.size
        }

        override fun observeTransactions(): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeRecent(limit: Int): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeLedgerEntries(): Flow<List<AccountBalanceCalculator.LedgerEntry>> = flowOf(emptyList())
        override suspend fun recordParsed(
            parsed: ParsedTransaction,
            message: CapturedMessage,
            dedupWindow: Boolean,
        ): IngestResult = IngestResult.Inserted(1L)
        override suspend fun findIdByRawMessage(rawMessage: String): Long? = null
        override suspend fun recordManual(transaction: Transaction): Long = 1L
        override suspend fun update(transaction: Transaction) = Unit
        override suspend fun assignAccount(transactionId: Long, accountId: Long?) = Unit
        override suspend fun assignAccountForRule(ruleName: String, accountId: Long): Int = 0
        override suspend fun setPerson(transactionId: Long, person: String?) = Unit
        override suspend fun setSpendingCategory(transactionId: Long, category: SpendingCategory) = Unit
        override suspend fun renamePerson(from: String, to: String?) = Unit
        override suspend fun delete(transactionId: Long) = Unit
        override suspend fun getAllReal(): List<Transaction> = emptyList()
        override fun observeUncategorized(): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun setCategory(transactionId: Long, categoryId: Long?) = Unit
        override suspend fun dismissCategorySuggestion(transactionId: Long) = Unit
        override suspend fun reassignCategory(oldCategoryId: Long, newCategoryId: Long?): Int = 0
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
        assertEquals(false, repo.accountAssignCalled)
        assertEquals(false, repo.personSetCalled)
    }

    @Test
    fun `patch with accountId Set calls bulkAssignAccount with that account id`() = runBlocking {
        useCase(listOf(1L, 2L), BulkTransactionPatch(accountId = FieldUpdate.Set(9L)))
        assertEquals(listOf(1L, 2L), repo.lastAccountIds)
        assertEquals(9L, repo.lastAccountId)
    }

    @Test
    fun `patch with accountId Set to null calls bulkAssignAccount with null to unassign`() = runBlocking {
        useCase(listOf(3L), BulkTransactionPatch(accountId = FieldUpdate.Set(null)))
        assertEquals(true, repo.accountAssignCalled)
        assertNull(repo.lastAccountId)
    }

    @Test
    fun `patch with accountId left as NoChange does not call bulkAssignAccount`() = runBlocking {
        useCase(listOf(4L), BulkTransactionPatch(spendingCategory = SpendingCategory.DINING))
        assertEquals(false, repo.accountAssignCalled)
    }

    @Test
    fun `patch with person Set calls bulkSetPerson with that name`() = runBlocking {
        useCase(listOf(5L, 6L), BulkTransactionPatch(person = FieldUpdate.Set("Alex")))
        assertEquals(listOf(5L, 6L), repo.lastPersonIds)
        assertEquals("Alex", repo.lastPerson)
    }

    @Test
    fun `patch with person Set to null calls bulkSetPerson with null to clear`() = runBlocking {
        useCase(listOf(7L), BulkTransactionPatch(person = FieldUpdate.Set(null)))
        assertEquals(true, repo.personSetCalled)
        assertNull(repo.lastPerson)
    }

    @Test
    fun `patch with person left as NoChange does not call bulkSetPerson`() = runBlocking {
        useCase(listOf(8L), BulkTransactionPatch(spendingCategory = SpendingCategory.DINING))
        assertEquals(false, repo.personSetCalled)
    }

    @Test
    fun `patch combining category, account, and person calls all three repository methods`() = runBlocking {
        useCase(
            listOf(10L),
            BulkTransactionPatch(
                spendingCategory = SpendingCategory.TRANSPORT,
                accountId = FieldUpdate.Set(11L),
                person = FieldUpdate.Set("Sam"),
            ),
        )
        assertEquals(SpendingCategory.TRANSPORT, repo.lastCategory)
        assertEquals(11L, repo.lastAccountId)
        assertEquals("Sam", repo.lastPerson)
    }

    @Test
    fun `patch with categoryId Set calls bulkSetCategoryId with that category id`() = runBlocking {
        useCase(listOf(1L, 2L), BulkTransactionPatch(categoryId = FieldUpdate.Set(9L)))
        assertEquals(listOf(1L, 2L), repo.lastCategoryIdSetIds)
        assertEquals(9L, repo.lastCategoryIdSet)
    }

    @Test
    fun `patch with categoryId Set to null calls bulkSetCategoryId with null to clear`() = runBlocking {
        useCase(listOf(3L), BulkTransactionPatch(categoryId = FieldUpdate.Set(null)))
        assertEquals(true, repo.categoryIdSetCalled)
        assertNull(repo.lastCategoryIdSet)
    }

    @Test
    fun `patch with categoryId left as NoChange does not call bulkSetCategoryId`() = runBlocking {
        useCase(listOf(4L), BulkTransactionPatch(spendingCategory = SpendingCategory.DINING))
        assertEquals(false, repo.categoryIdSetCalled)
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
