package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.IngestResult
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFallbackPipelineTest {

    private val message = CapturedMessage(
        source = TransactionSource.SMS,
        sender = "BANK",
        title = null,
        body = "Payment of AED 46.12 at CARREFOUR with card ••1234",
        timestampMillis = 0L,
    )

    private val extraction =
        AiExtraction.Transaction(BigDecimal("46.12"), "AED", "CARREFOUR")

    private class Harness(
        extract: AiExtraction?,
        recordResult: IngestResult = IngestResult.Inserted(1L),
    ) {
        val recorded = mutableListOf<ParsedTransaction>()
        val savedRules = mutableListOf<ParsingRule>()
        val pipeline = AiFallbackPipeline(
            extract = { extract },
            record = { parsed, _ -> recorded += parsed; recordResult },
            enabledRules = { savedRules.toList() },
            saveRule = { savedRules += it; 42L },
            fallbackCurrency = { "USD" },
        )
    }

    @Test
    fun `unusable model output records nothing`() = runBlocking {
        val h = Harness(extract = null)
        assertEquals(AiFallbackPipeline.Outcome.Unavailable, h.pipeline.run(message))
        assertTrue(h.recorded.isEmpty())
        assertTrue(h.savedRules.isEmpty())
    }

    @Test
    fun `not-a-transaction verdict records nothing`() = runBlocking {
        val h = Harness(extract = AiExtraction.NotATransaction)
        assertEquals(AiFallbackPipeline.Outcome.NotATransaction, h.pipeline.run(message))
        assertTrue(h.recorded.isEmpty())
    }

    @Test
    fun `extraction is recorded and a rule is learned`() = runBlocking {
        val h = Harness(extract = extraction)
        val outcome = h.pipeline.run(message) as AiFallbackPipeline.Outcome.Recorded

        val parsed = h.recorded.single()
        assertEquals(AiFallbackPipeline.AI_RULE_NAME, parsed.matchedRuleName)
        assertEquals(0, BigDecimal("46.12").compareTo(parsed.amount))
        assertEquals("AED", parsed.currencyCode)
        assertEquals("CARREFOUR", parsed.merchant)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals("1234", parsed.accountNumberSuffix)

        assertNotNull(outcome.learnedRule)
        assertEquals(h.savedRules.single().pattern, outcome.learnedRule!!.pattern)
        assertEquals(42L, outcome.learnedRule!!.id)
    }

    @Test
    fun `explicit received wording flips the direction to credit`() = runBlocking {
        val credit = message.copy(body = "You have received a payment of 710.00 USDT")
        val h = Harness(extract = AiExtraction.Transaction(BigDecimal("710.00"), "USDT", null))
        h.pipeline.run(credit)
        assertEquals(TransactionDirection.CREDIT, h.recorded.single().direction)
    }

    @Test
    fun `unknown currency falls back to the app default`() = runBlocking {
        val h = Harness(extract = extraction.copy(currency = null))
        h.pipeline.run(message)
        assertEquals("USD", h.recorded.single().currencyCode)
    }

    @Test
    fun `duplicate delivery does not learn a second rule`() = runBlocking {
        val h = Harness(extract = extraction, recordResult = IngestResult.Duplicate(7L))
        val outcome = h.pipeline.run(message) as AiFallbackPipeline.Outcome.Recorded
        assertNull(outcome.learnedRule)
        assertTrue(h.savedRules.isEmpty())
    }

    @Test
    fun `an already-learned pattern is not saved twice`() = runBlocking {
        val h = Harness(extract = extraction)
        val first = h.pipeline.run(message) as AiFallbackPipeline.Outcome.Recorded
        assertNotNull(first.learnedRule)
        val second = h.pipeline.run(message) as AiFallbackPipeline.Outcome.Recorded
        assertNull(second.learnedRule)
        assertEquals(1, h.savedRules.size)
    }
}
