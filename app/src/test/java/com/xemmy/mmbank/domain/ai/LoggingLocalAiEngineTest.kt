package com.xemmy.mmbank.domain.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScriptedEngine(
    override val isReady: Boolean = true,
    private val result: Result<String>,
) : LocalAiEngine {
    override suspend fun initialize(): Result<Unit> = Result.success(Unit)
    override suspend fun generate(prompt: String, maxTokens: Int): Result<String> = result
}

class LoggingLocalAiEngineTest {

    @Test
    fun `logs the prompt and successful result then returns it unchanged`() = runBlocking {
        var loggedPrompt: String? = null
        var loggedResult: Result<String>? = null
        val engine = LoggingLocalAiEngine(ScriptedEngine(result = Result.success("hello"))) { prompt, result ->
            loggedPrompt = prompt
            loggedResult = result
        }

        val result = engine.generate("some prompt")

        assertEquals("some prompt", loggedPrompt)
        assertEquals(Result.success("hello"), loggedResult)
        assertEquals(Result.success("hello"), result)
    }

    @Test
    fun `logs a failed result then returns it unchanged`() = runBlocking {
        val failure = Result.failure<String>(IllegalStateException("boom"))
        var loggedResult: Result<String>? = null
        val engine = LoggingLocalAiEngine(ScriptedEngine(result = failure)) { _, result -> loggedResult = result }

        val result = engine.generate("some prompt")

        assertTrue(loggedResult?.isFailure == true)
        assertTrue(result.isFailure)
    }

    @Test
    fun `isReady and initialize delegate through untouched`() = runBlocking {
        val engine = LoggingLocalAiEngine(ScriptedEngine(isReady = false, result = Result.success(""))) { _, _ -> }

        assertFalse(engine.isReady)
        assertTrue(engine.initialize().isSuccess)
    }
}
