package com.xemmy.mmbank.data.ai

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real network seam against a MockWebServer, which binds to
 * 127.0.0.1 -- so the happy path also proves the loopback guard admits a
 * genuine loopback origin, while the non-loopback case proves it blocks
 * everything else without ever opening a socket.
 */
class OpenAiCompatibleEngineTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun engine(model: String = "gemma") =
        OpenAiCompatibleEngine(server.url("/v1/chat/completions").toString(), model, client)

    @Test
    fun `initialize succeeds when the models probe returns 200`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        val engine = engine()
        assertTrue(engine.initialize().isSuccess)
        assertTrue(engine.isReady)
    }

    @Test
    fun `generate returns the assistant content on a 200`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"{\"transaction_intent\":false}"}}]}"""
            )
        )
        val result = engine().generate("prompt")
        assertTrue(result.isSuccess)
        assertEquals("{\"transaction_intent\":false}", result.getOrNull())
    }

    @Test
    fun `generate fails closed on a non-2xx response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertTrue(engine().generate("prompt").isFailure)
    }

    @Test
    fun `generate fails closed on a malformed body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        assertTrue(engine().generate("prompt").isFailure)
    }

    @Test
    fun `a non-loopback endpoint is rejected without any request`() = runBlocking {
        val engine = OpenAiCompatibleEngine("http://192.168.1.10:11434/v1/chat/completions", "gemma", client)
        assertTrue(engine.initialize().isFailure)
        assertTrue(engine.generate("prompt").isFailure)
        assertFalse(engine.isReady)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `online mode allows a non-loopback endpoint`() = runBlocking {
        // MockWebServer itself binds 127.0.0.1, so this only proves the guard is
        // actually skipped (not merely that the URL happens to be loopback) --
        // requireLoopback = false must let a real request through regardless.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        val engine = OpenAiCompatibleEngine(
            chatCompletionsUrl = server.url("/v1/chat/completions").toString(),
            model = "gpt-4o-mini",
            client = client,
            requireLoopback = false,
        )
        assertTrue(engine.initialize().isSuccess)
        assertTrue(engine.isReady)
    }

    @Test
    fun `an api key is sent as a bearer authorization header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        val engine = OpenAiCompatibleEngine(
            chatCompletionsUrl = server.url("/v1/chat/completions").toString(),
            model = "gpt-4o-mini",
            client = client,
            apiKey = "sk-test-123",
            requireLoopback = false,
        )
        engine.initialize()
        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test-123", recorded.getHeader("Authorization"))
    }

    @Test
    fun `no authorization header is sent when the api key is blank`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        val engine = OpenAiCompatibleEngine(
            chatCompletionsUrl = server.url("/v1/chat/completions").toString(),
            model = "gemma",
            client = client,
            apiKey = null,
        )
        engine.initialize()
        val recorded = server.takeRequest()
        assertEquals(null, recorded.getHeader("Authorization"))
    }
}
