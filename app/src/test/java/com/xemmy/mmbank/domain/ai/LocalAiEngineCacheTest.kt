package com.xemmy.mmbank.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

private class FakeEngine : LocalAiEngine {
    override val isReady: Boolean = true
    override suspend fun initialize(): Result<Unit> = Result.success(Unit)
    override suspend fun generate(prompt: String, maxTokens: Int): Result<String> = Result.success("")
}

class LocalAiEngineCacheTest {

    @Test
    fun `get invokes the factory only once`() {
        var factoryCalls = 0
        val cache = LocalAiEngineCache {
            factoryCalls++
            FakeEngine()
        }

        val first = cache.get()
        val second = cache.get()

        assertSame(first, second)
        assertEquals(1, factoryCalls)
    }

    @Test
    fun `invalidate forces the next get to call the factory again`() {
        var factoryCalls = 0
        val cache = LocalAiEngineCache {
            factoryCalls++
            FakeEngine()
        }

        val first = cache.get()
        cache.invalidate()
        val second = cache.get()

        assertNotSame(first, second)
        assertEquals(2, factoryCalls)
    }
}
