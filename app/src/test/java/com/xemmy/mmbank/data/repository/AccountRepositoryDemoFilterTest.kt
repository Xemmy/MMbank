package com.xemmy.mmbank.data.repository

import com.xemmy.mmbank.data.local.dao.AccountDao
import com.xemmy.mmbank.data.local.dao.ParsingRuleDao
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.domain.model.AccountType
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves Demo mode's reactive partition switch: flipping the demo StateFlow
 * makes observeActive() re-subscribe to the other partition (flatMapLatest),
 * while observeActiveReal() always ignores it. In-memory fake DAO — pure JVM.
 */
class AccountRepositoryDemoFilterTest {

    private class FakeAccountDao(private val rows: MutableList<AccountEntity>) : AccountDao {
        override suspend fun insert(entity: AccountEntity): Long { rows.add(entity); return entity.id }
        override suspend fun update(entity: AccountEntity) {}
        override suspend fun delete(entity: AccountEntity) {}
        override fun observeActive(demo: Boolean): Flow<List<AccountEntity>> =
            flowOf(rows.filter { !it.archived && it.isDemo == demo }.sortedBy { it.name })
        override fun observeArchived(demo: Boolean): Flow<List<AccountEntity>> =
            flowOf(rows.filter { it.archived && it.isDemo == demo }.sortedBy { it.name })
        override suspend fun getActive(): List<AccountEntity> = rows.filter { !it.archived && !it.isDemo }
        override suspend fun getById(id: Long): AccountEntity? = rows.firstOrNull { it.id == id }
        override suspend fun setOpeningBalance(id: Long, openingBalance: BigDecimal) {}
        override suspend fun deleteDemo(): Int {
            val n = rows.count { it.isDemo }; rows.removeAll { it.isDemo }; return n
        }
        override suspend fun countDemo(): Int = rows.count { it.isDemo }
        override suspend fun getAll(): List<AccountEntity> = rows.filter { !it.isDemo }
        override suspend fun clearAll() = rows.clear()
        override suspend fun insertAll(entities: List<AccountEntity>) { rows.addAll(entities) }
    }

    /** No behavior under test here -- delete() sequencing is covered by AccountRepositoryImplDeleteTest. */
    private class NoopParsingRuleDao : ParsingRuleDao {
        override suspend fun upsert(entity: ParsingRuleEntity): Long = entity.id
        override suspend fun deleteById(id: Long) {}
        override fun observeAll(demo: Boolean): Flow<List<ParsingRuleEntity>> = flowOf(emptyList())
        override suspend fun getEnabled(): List<ParsingRuleEntity> = emptyList()
        override suspend fun getById(id: Long): ParsingRuleEntity? = null
        override suspend fun deleteDemo(): Int = 0
        override suspend fun countDemo(): Int = 0
        override suspend fun getAll(): List<ParsingRuleEntity> = emptyList()
        override suspend fun clearAll() {}
        override suspend fun insertAll(entities: List<ParsingRuleEntity>) {}
        override suspend fun clearAssignAccountId(accountId: Long): Int = 0
    }

    private fun acct(id: Long, name: String, isDemo: Boolean) = AccountEntity(
        id = id, name = name, type = AccountType.CHECKING, currencyCode = "USD",
        openingBalance = BigDecimal.ZERO, createdAt = 0, isDemo = isDemo,
    )

    private fun dao() = FakeAccountDao(
        mutableListOf(
            acct(1, "Real Checking", isDemo = false),
            acct(2, "Real Savings", isDemo = false),
            acct(3, "Demo Card", isDemo = true),
        )
    )

    @Test
    fun `observeActive shows real off, demo on, and switches with the flag`() = runBlocking {
        val flag = MutableStateFlow(false)
        val repo = AccountRepositoryImpl(dao(), NoopParsingRuleDao(), flag)

        assertEquals(listOf("Real Checking", "Real Savings"), repo.observeActive().first().map { it.name })

        flag.value = true
        assertEquals(listOf("Demo Card"), repo.observeActive().first().map { it.name })

        flag.value = false
        assertEquals(listOf("Real Checking", "Real Savings"), repo.observeActive().first().map { it.name })
    }

    @Test
    fun `observeActiveReal always returns real accounts regardless of the flag`() = runBlocking {
        val repo = AccountRepositoryImpl(dao(), NoopParsingRuleDao(), MutableStateFlow(true))
        assertEquals(listOf("Real Checking", "Real Savings"), repo.observeActiveReal().first().map { it.name })
    }
}
