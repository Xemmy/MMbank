package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.IgnoredKeyword
import com.xemmy.mmbank.domain.model.IgnoredMessagePattern
import com.xemmy.mmbank.domain.repository.IgnoredKeywordRepository
import com.xemmy.mmbank.domain.repository.IgnoredMessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoredMessageCacheTest {

    private class FakeRepository(val patterns: MutableStateFlow<List<IgnoredMessagePattern>>) :
        IgnoredMessageRepository {
        override fun observeAll(): Flow<List<IgnoredMessagePattern>> = patterns
        override suspend fun add(sample: String, now: Long): Boolean = true
        override suspend fun delete(id: Long) = Unit
    }

    /** Stands in for a Room read that blows up mid-stream. */
    private class FailingRepository : IgnoredMessageRepository {
        override fun observeAll(): Flow<List<IgnoredMessagePattern>> = flow { error("db is down") }
        override suspend fun add(sample: String, now: Long): Boolean = true
        override suspend fun delete(id: Long) = Unit
    }

    private fun pattern(sample: String, id: Long = 1) = IgnoredMessagePattern(
        id = id,
        fingerprint = MessageSimilarity.fingerprint(sample),
        sample = sample,
        createdAt = 0L,
    )

    private val flow = MutableStateFlow<List<IgnoredMessagePattern>>(emptyList())

    // Unconfined scope: the cache's stateIn collects the fake flow synchronously.
    private fun cache(repository: IgnoredMessageRepository = FakeRepository(flow)) =
        IgnoredMessageCache(repository, CoroutineScope(Dispatchers.Unconfined))

    private class FakeKeywords(words: List<String>) : IgnoredKeywordRepository {
        private val items = words.mapIndexed { i, w -> IgnoredKeyword(i + 1L, w, 0L) }
        override fun observeAll(): Flow<List<IgnoredKeyword>> = MutableStateFlow(items)
        override suspend fun add(keyword: String, now: Long) = true
        override suspend fun delete(id: Long) = Unit
    }

    private fun keywordCache(vararg words: String) =
        IgnoredMessageCache(FakeRepository(flow), CoroutineScope(Dispatchers.Unconfined), FakeKeywords(words.toList()))

    @Test
    fun `a keyword matches case-insensitively in the body`() = runBlocking {
        assertTrue(keywordCache("promo").shouldIgnore("Special PROMO: spent AED 50 at Noon"))
    }

    @Test
    fun `a keyword also matches in the title`() = runBlocking {
        assertTrue(keywordCache("promo").shouldIgnore("AED 50 at Noon", title = "Promo alert"))
    }

    @Test
    fun `a text without any keyword is not ignored`() = runBlocking {
        assertFalse(keywordCache("promo").shouldIgnore("Spent AED 50 at Noon", title = "Card alert"))
    }

    @Test
    fun `an empty list ignores nothing`() = runBlocking {
        assertFalse(cache().shouldIgnore("Enjoy 50% off at Noon this weekend"))
    }

    @Test
    fun `the same template with different numbers is ignored`() = runBlocking {
        flow.value = listOf(pattern("Your OTP is 483920. Valid for 5 minutes."))
        assertTrue(cache().shouldIgnore("Your OTP is 112233. Valid for 9 minutes."))
    }

    @Test
    fun `a near-miss template is ignored via the sample, not the fingerprint`() = runBlocking {
        flow.value = listOf(
            pattern("Dear customer, your statement for account 1234 is ready to view online now")
        )
        assertTrue(
            cache().shouldIgnore(
                "Dear customer, your statement for account 9876 is ready to download online now"
            )
        )
    }

    @Test
    fun `a real transaction is not ignored`() = runBlocking {
        flow.value = listOf(pattern("Enjoy 50% off at Noon this weekend. Use code SAVE50. T&C apply."))
        assertFalse(cache().shouldIgnore("AED 42.50 was spent on your card ending 1234 at CARREFOUR DUBAI"))
    }

    @Test
    fun `text with no fingerprint is never ignored`() = runBlocking {
        flow.value = listOf(pattern("Your OTP is 483920"))
        assertFalse(cache().shouldIgnore("!!!"))
    }

    @Test
    fun `deleting the last pattern stops the ignoring immediately`() = runBlocking {
        flow.value = listOf(pattern("Your OTP is 483920"))
        val cache = cache()
        assertTrue(cache.shouldIgnore("Your OTP is 112233"))

        flow.value = emptyList()
        assertFalse(cache.shouldIgnore("Your OTP is 112233"))
    }

    @Test
    fun `a failing repository ignores nothing rather than killing capture`() = runBlocking {
        assertFalse(cache(FailingRepository()).shouldIgnore("Your OTP is 112233"))
    }
}
