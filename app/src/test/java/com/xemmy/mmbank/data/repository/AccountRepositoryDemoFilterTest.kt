package com.xemmy.mmbank.data.repository

import com.xemmy.mmbank.data.local.dao.AccountDao
import com.xemmy.mmbank.data.local.entity.AccountEntity
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
        override suspend fun getActive(): List<AccountEntity> = rows.filter { !it.archived && !it.isDemo }
        override suspend fun getById(id: Long): AccountEntity? = rows.firstOrNull { it.id == id }
        override suspend fun deleteDemo(): Int {
            val n = rows.count { it.isDemo }; rows.removeAll { it.isDemo }; return n
        }
        override suspend fun countDemo(): Int = rows.count { it.isDemo }
        override suspend fun getAll(): List<AccountEntity> = rows.filter { !it.isDemo }
        override suspend fun clearAll() = rows.clear()
        override suspend fun insertAll(entities: List<AccountEntity>) { rows.addAll(entities) }
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
        val repo = AccountRepositoryImpl(dao(), flag)

        assertEquals(listOf("Real Checking", "Real Savings"), repo.observeActive().first().map { it.name })

        flag.value = true
        assertEquals(listOf("Demo Card"), repo.observeActive().first().map { it.name })

        flag.value = false
        assertEquals(listOf("Real Checking", "Real Savings"), repo.observeActive().first().map { it.name })
    }

    @Test
    fun `observeActiveReal always returns real accounts regardless of the flag`() = runBlocking {
        val repo = AccountRepositoryImpl(dao(), MutableStateFlow(true))
        assertEquals(listOf("Real Checking", "Real Savings"), repo.observeActiveReal().first().map { it.name })
    }
}
