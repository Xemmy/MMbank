package com.xemmy.mmbank.data.repository

import com.xemmy.mmbank.data.local.dao.AccountDao
import com.xemmy.mmbank.data.local.dao.ParsingRuleDao
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AccountRepositoryImpl.delete() must clear every parsing rule's
 * assignAccountId pointing at the deleted account BEFORE deleting it --
 * ParsingRuleEntity.assignAccountId carries no real FK, so a dangling
 * pointer can later violate transactions.accountId's real FK on capture.
 * In-memory fake DAOs -- pure JVM, no Room.
 */
class AccountRepositoryImplDeleteTest {

    private class FakeAccountDao(private val rows: MutableList<AccountEntity>) : AccountDao {
        override suspend fun insert(entity: AccountEntity): Long { rows.add(entity); return entity.id }
        override suspend fun update(entity: AccountEntity) {}
        override suspend fun delete(entity: AccountEntity) { rows.removeAll { it.id == entity.id } }
        override fun observeActive(demo: Boolean): Flow<List<AccountEntity>> = flowOf(emptyList())
        override suspend fun getActive(): List<AccountEntity> = emptyList()
        override suspend fun getById(id: Long): AccountEntity? = rows.firstOrNull { it.id == id }
        override suspend fun setOpeningBalance(id: Long, openingBalance: BigDecimal) {}
        override suspend fun deleteDemo(): Int = 0
        override suspend fun countDemo(): Int = 0
        override suspend fun getAll(): List<AccountEntity> = rows.toList()
        override suspend fun clearAll() = rows.clear()
        override suspend fun insertAll(entities: List<AccountEntity>) { rows.addAll(entities) }
    }

    private class FakeParsingRuleDao(private val rows: MutableList<ParsingRuleEntity>) : ParsingRuleDao {
        override suspend fun upsert(entity: ParsingRuleEntity): Long { rows.add(entity); return entity.id }
        override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
        override fun observeAll(demo: Boolean): Flow<List<ParsingRuleEntity>> = flowOf(rows.filter { it.isDemo == demo })
        override suspend fun getEnabled(): List<ParsingRuleEntity> = rows.filter { it.enabled && !it.isDemo }
        override suspend fun getById(id: Long): ParsingRuleEntity? = rows.firstOrNull { it.id == id }
        override suspend fun deleteDemo(): Int = 0
        override suspend fun countDemo(): Int = 0
        override suspend fun getAll(): List<ParsingRuleEntity> = rows.filter { !it.isDemo }
        override suspend fun clearAll() = rows.clear()
        override suspend fun insertAll(entities: List<ParsingRuleEntity>) { rows.addAll(entities) }

        // Mirrors the real DAO's "UPDATE parsing_rules SET assignAccountId = NULL
        // WHERE assignAccountId = :accountId" against the in-memory rows.
        override suspend fun clearAssignAccountId(accountId: Long): Int {
            var count = 0
            for (i in rows.indices) {
                if (rows[i].assignAccountId == accountId) {
                    rows[i] = rows[i].copy(assignAccountId = null)
                    count++
                }
            }
            return count
        }
    }

    private fun account(id: Long) = AccountEntity(
        id = id, name = "Acc$id", type = AccountType.CHECKING, currencyCode = "USD",
        openingBalance = BigDecimal.ZERO, createdAt = 0,
    )

    private fun rule(id: Long, assignAccountId: Long?) = ParsingRuleEntity(
        id = id, name = "Rule$id", pattern = ".*", direction = TransactionDirection.DEBIT,
        createdAt = 0, assignAccountId = assignAccountId,
    )

    @Test
    fun `delete clears assignAccountId on rules pointing at the deleted account, but not others`() = runBlocking {
        val accountDao = FakeAccountDao(mutableListOf(account(1), account(2)))
        val ruleDao = FakeParsingRuleDao(mutableListOf(rule(10, assignAccountId = 1), rule(11, assignAccountId = 2)))
        val repo = AccountRepositoryImpl(accountDao, ruleDao, MutableStateFlow(false))

        repo.delete(accountDao.getById(1)!!.toDomain())

        assertNull(ruleDao.getById(10)?.assignAccountId)
        assertEquals(2L, ruleDao.getById(11)?.assignAccountId)
        assertNull(accountDao.getById(1))
    }
}
