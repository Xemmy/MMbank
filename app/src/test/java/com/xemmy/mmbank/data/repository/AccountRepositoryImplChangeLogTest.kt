package com.xemmy.mmbank.data.repository

import com.xemmy.mmbank.data.local.dao.AccountChangeDao
import com.xemmy.mmbank.data.local.dao.AccountDao
import com.xemmy.mmbank.data.local.dao.ParsingRuleDao
import com.xemmy.mmbank.data.local.entity.AccountChangeEntity
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountChangeType
import com.xemmy.mmbank.domain.model.AccountType
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every account write leaves its entry in the change log. Pure JVM: in-memory
 * fake DAOs, no Room (the atomic wrapper is a pass-through without a database).
 */
class AccountRepositoryImplChangeLogTest {

    private class FakeAccountDao : AccountDao {
        val rows = mutableListOf<AccountEntity>()
        private var nextId = 1L
        override suspend fun insert(entity: AccountEntity): Long {
            val id = nextId++
            rows.add(entity.copy(id = id))
            return id
        }
        override suspend fun update(entity: AccountEntity) {
            val i = rows.indexOfFirst { it.id == entity.id }
            if (i >= 0) rows[i] = entity
        }
        override suspend fun delete(entity: AccountEntity) { rows.removeAll { it.id == entity.id } }
        override fun observeActive(demo: Boolean): Flow<List<AccountEntity>> = flowOf(emptyList())
        override fun observeArchived(demo: Boolean): Flow<List<AccountEntity>> = flowOf(emptyList())
        override suspend fun getActive(): List<AccountEntity> = emptyList()
        override suspend fun getById(id: Long): AccountEntity? = rows.firstOrNull { it.id == id }
        override suspend fun setOpeningBalance(id: Long, openingBalance: BigDecimal) {}
        override suspend fun deleteDemo(): Int = 0
        override suspend fun countDemo(): Int = 0
        override suspend fun getAll(): List<AccountEntity> = rows.toList()
        override suspend fun clearAll() = rows.clear()
        override suspend fun insertAll(entities: List<AccountEntity>) { rows.addAll(entities) }
    }

    private class NoRulesDao : ParsingRuleDao {
        override suspend fun upsert(entity: ParsingRuleEntity): Long = 0
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

    private class FakeChangeDao : AccountChangeDao {
        val rows = mutableListOf<AccountChangeEntity>()
        override suspend fun insert(entity: AccountChangeEntity): Long { rows.add(entity); return rows.size.toLong() }
        override suspend fun insertAll(entities: List<AccountChangeEntity>) { rows.addAll(entities) }
        override fun observeForAccount(accountId: Long): Flow<List<AccountChangeEntity>> = flowOf(rows.filter { it.accountId == accountId })
        override suspend fun getAll(): List<AccountChangeEntity> = rows.toList()
        override suspend fun clearAll() = rows.clear()
        override suspend fun deleteOlderThan(cutoffMillis: Long): Int = 0
        override suspend fun deleteDemo(): Int = 0
    }

    private val accounts = FakeAccountDao()
    private val log = FakeChangeDao()
    private val repo = AccountRepositoryImpl(
        dao = accounts,
        parsingRuleDao = NoRulesDao(),
        demoMode = MutableStateFlow(false),
        changes = log,
        clock = { 1234L },
    )

    private fun draft(opening: String = "500") = Account(
        name = "Cash", type = AccountType.CASH, currencyCode = "AED", openingBalance = BigDecimal(opening), createdAt = 0,
    )

    @Test
    fun `saving a new account logs its creation with the id it was given`() = runBlocking {
        val id = repo.save(draft("500"))
        val entry = log.rows.single()
        assertEquals(AccountChangeType.CREATED, entry.type)
        assertEquals(id, entry.accountId)
        assertEquals(BigDecimal("500"), entry.balanceDelta)
        assertEquals(1234L, entry.timestamp)
    }

    @Test
    fun `editing an account logs only what changed`() = runBlocking {
        val id = repo.save(draft("500"))
        log.rows.clear()
        val saved = accounts.getById(id)!!.toDomain()

        repo.save(saved.copy(openingBalance = BigDecimal("650"), name = "Wallet"))

        assertEquals(
            listOf(AccountChangeType.OPENING_BALANCE_CHANGED, AccountChangeType.DETAILS_EDITED),
            log.rows.map { it.type },
        )
        assertEquals(BigDecimal("150"), log.rows[0].balanceDelta)
    }

    @Test
    fun `saving with nothing changed logs nothing`() = runBlocking {
        val id = repo.save(draft())
        log.rows.clear()
        repo.save(accounts.getById(id)!!.toDomain())
        assertTrue(log.rows.isEmpty())
    }

    @Test
    fun `archive restore and delete are each logged, and the log keeps the name after delete`() = runBlocking {
        val id = repo.save(draft())
        log.rows.clear()
        val account = accounts.getById(id)!!.toDomain()

        repo.archive(account)
        repo.unarchive(account.copy(archived = true))
        repo.delete(account)

        assertEquals(
            listOf(AccountChangeType.ARCHIVED, AccountChangeType.RESTORED, AccountChangeType.DELETED),
            log.rows.map { it.type },
        )
        assertTrue(accounts.rows.isEmpty())
        assertEquals("Cash", log.rows.last().accountName)
    }

    @Test
    fun `without a change dao the repository behaves as before`() = runBlocking {
        val plain = AccountRepositoryImpl(accounts, NoRulesDao(), MutableStateFlow(false))
        val id = plain.save(draft())
        plain.archive(accounts.getById(id)!!.toDomain())
        assertTrue(log.rows.isEmpty())
    }
}
