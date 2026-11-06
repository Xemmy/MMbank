package com.xemmy.mmbank.data.repository

import com.xemmy.mmbank.data.local.dao.IgnoredKeywordDao
import com.xemmy.mmbank.data.local.entity.IgnoredKeywordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoredKeywordRepositoryImplTest {

    private class FakeDao : IgnoredKeywordDao {
        val rows = MutableStateFlow<List<IgnoredKeywordEntity>>(emptyList())
        override fun observeAll(): Flow<List<IgnoredKeywordEntity>> = rows
        override suspend fun upsert(entity: IgnoredKeywordEntity): Long {
            rows.value = rows.value.filterNot { it.keyword == entity.keyword } + entity
            return 1
        }
        override suspend fun deleteById(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
        override suspend fun getAll() = rows.value
        override suspend fun clearAll() { rows.value = emptyList() }
        override suspend fun insertAll(entities: List<IgnoredKeywordEntity>) { rows.value = entities }
    }

    @Test
    fun `stores the keyword trimmed and lowercased`() = runBlocking {
        val dao = FakeDao()
        assertTrue(IgnoredKeywordRepositoryImpl(dao).add("  PromO ", 5L))
        assertEquals(listOf("promo"), dao.rows.value.map { it.keyword })
    }

    @Test
    fun `rejects keywords shorter than three characters`() = runBlocking {
        val dao = FakeDao()
        assertFalse(IgnoredKeywordRepositoryImpl(dao).add(" a ", 5L))
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `re-adding the same keyword does not duplicate it`() = runBlocking {
        val dao = FakeDao()
        val repo = IgnoredKeywordRepositoryImpl(dao)
        repo.add("promo", 1L)
        repo.add("PROMO", 2L)
        assertEquals(1, dao.rows.value.size)
    }
}
