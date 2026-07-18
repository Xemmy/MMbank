package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSelfHealingRuleGeneratorTest {

    @Test
    fun `generates a disabled pending rule that reproduces the same fields`() {
        val rawMessage = "You paid 46.12 AED at CoffeeShop today"
        val result = AiParseResult(true, BigDecimal("46.12"), "AED", "CoffeeShop")

        val rule = AiSelfHealingRuleGenerator.generate(rawMessage, result)

        assertNotNull(rule)
        assertFalse(rule!!.enabled)
        assertEquals(TransactionDirection.DEBIT, rule.direction)
        assertEquals(rawMessage, rule.sampleText)

        val match = Regex(rule.pattern, RegexOption.IGNORE_CASE).find(rawMessage)
        assertNotNull(match)
        assertEquals("46.12", match!!.groups["amount"]?.value)
        assertEquals("AED", match.groups["currency"]?.value?.uppercase())
    }

    @Test
    fun `received-a-payment message stages a credit candidate`() {
        // "payment" must not read as a debit cue: the shared parser vocabulary
        // (GenericExtractors.detectDirectionOverride) is word-bounded and
        // "received" is the explicit incoming-money signal.
        val rawMessage = "You received a payment of 85.00 USDT from Alice"
        val result = AiParseResult(true, BigDecimal("85.00"), "USDT", "Alice")

        val rule = AiSelfHealingRuleGenerator.generate(rawMessage, result)

        assertNotNull(rule)
        assertEquals(TransactionDirection.CREDIT, rule!!.direction)
        assertFalse(rule.enabled)
    }

    @Test
    fun `earliest direction keyword wins when both vocabularies appear`() {
        val rawMessage = "You paid 20.00 AED and received a confirmation"
        val result = AiParseResult(true, BigDecimal("20.00"), "AED", null)

        val rule = AiSelfHealingRuleGenerator.generate(rawMessage, result)

        assertNotNull(rule)
        assertEquals(TransactionDirection.DEBIT, rule!!.direction)
    }

    @Test
    fun `works without a merchant field`() {
        val rawMessage = "You paid 10.00 USD for this purchase"
        val result = AiParseResult(true, BigDecimal("10.00"), "USD", null)

        val rule = AiSelfHealingRuleGenerator.generate(rawMessage, result)

        assertNotNull(rule)
        val match = Regex(rule!!.pattern, RegexOption.IGNORE_CASE).find(rawMessage)
        assertNotNull(match)
    }

    @Test
    fun `amount not literally present in the message yields no candidate`() {
        val result = AiParseResult(true, BigDecimal("999.99"), "AED", "Shop")
        assertNull(AiSelfHealingRuleGenerator.generate("You paid 46.12 AED at Shop", result))
    }

    @Test
    fun `ambiguous direction yields no candidate`() {
        val result = AiParseResult(true, BigDecimal("42"), "AED", null)
        assertNull(AiSelfHealingRuleGenerator.generate("Something with a number 42 but no known shape", result))
    }

    @Test
    fun `non-transaction intent yields no candidate`() {
        val result = AiParseResult(false, null, null, null)
        assertNull(AiSelfHealingRuleGenerator.generate("Your statement is ready", result))
    }

    @Test
    fun `thousands-separated amount in the message is matched`() {
        val rawMessage = "You paid 1,234.56 AED for rent"
        val result = AiParseResult(true, BigDecimal("1234.56"), "AED", null)

        val rule = AiSelfHealingRuleGenerator.generate(rawMessage, result)

        assertNotNull(rule)
        val match = Regex(rule!!.pattern, RegexOption.IGNORE_CASE).find(rawMessage)
        assertTrue(match != null)
    }
}
