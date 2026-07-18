package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.RuleCompiler
import com.xemmy.mmbank.domain.parser.TransactionParser
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexSynthesizerTest {

    private fun message(body: String, sender: String? = "tabby", title: String? = null) =
        CapturedMessage(
            source = TransactionSource.PUSH_NOTIFICATION,
            sender = sender,
            title = title,
            body = body,
            timestampMillis = 0L,
        )

    private val tabbyBody =
        "You spent AED 110.90 at CAREEM RIDE. Your available Tabby Card limit is now AED 6111.71."
    private val tabbyExtraction =
        AiExtraction.Transaction(BigDecimal("110.90"), "AED", "CAREEM RIDE")

    private fun parseWith(rule: com.xemmy.mmbank.domain.model.ParsingRule, msg: CapturedMessage) =
        TransactionParser(listOf(RuleCompiler.compile(rule)!!)).parse(msg)

    @Test
    fun `learned rule reparses the source message to the model's own values`() {
        val rule = RegexSynthesizer.synthesize(message(tabbyBody), tabbyExtraction, TransactionDirection.DEBIT)!!
        val parsed = parseWith(rule, message(tabbyBody))!!
        assertEquals(0, BigDecimal("110.90").compareTo(parsed.amount))
        assertEquals("AED", parsed.currencyCode)
        assertEquals("CAREEM RIDE", parsed.merchant)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals(rule.name, parsed.matchedRuleName)
    }

    @Test
    fun `learned rule generalizes to future messages of the same shape`() {
        val rule = RegexSynthesizer.synthesize(message(tabbyBody), tabbyExtraction, TransactionDirection.DEBIT)!!
        val mutant =
            "You spent AED 55.20 at NOON MART. Your available Tabby Card limit is now AED 6056.51."
        val parsed = parseWith(rule, message(mutant))!!
        assertEquals(0, BigDecimal("55.20").compareTo(parsed.amount))
        assertEquals("AED", parsed.currencyCode)
        assertEquals("NOON MART", parsed.merchant)
    }

    @Test
    fun `learned rule is bound to the sender it was learned from`() {
        val rule = RegexSynthesizer.synthesize(message(tabbyBody), tabbyExtraction, TransactionDirection.DEBIT)!!
        assertNull(parseWith(rule, message(tabbyBody, sender = "com.random.other")))
    }

    @Test
    fun `amount that never occurs in the text yields no rule`() {
        assertNull(
            RegexSynthesizer.synthesize(
                message(tabbyBody),
                tabbyExtraction.copy(amount = BigDecimal("999.99")),
                TransactionDirection.DEBIT,
            )
        )
    }

    @Test
    fun `merchant absent from the text degrades to a merchantless rule`() {
        val rule = RegexSynthesizer.synthesize(
            message(tabbyBody),
            tabbyExtraction.copy(merchant = "TOTALLY ABSENT LLC"),
            TransactionDirection.DEBIT,
        )!!
        val parsed = parseWith(rule, message(tabbyBody))!!
        assertEquals(0, BigDecimal("110.90").compareTo(parsed.amount))
        assertNull(parsed.merchant)
    }

    @Test
    fun `symbol-only currency is preserved through defaultCurrency`() {
        val body = "You received ${'$'}1,250.00 from JOHN DOE"
        val rule = RegexSynthesizer.synthesize(
            message(body, sender = "BANK"),
            AiExtraction.Transaction(BigDecimal("1250.00"), "USD", "JOHN DOE"),
            TransactionDirection.CREDIT,
        )!!
        assertEquals("USD", rule.defaultCurrency)
        val parsed = parseWith(rule, message(body, sender = "BANK"))!!
        assertEquals(0, BigDecimal("1250.00").compareTo(parsed.amount))
        assertEquals("USD", parsed.currencyCode)
        assertEquals("JOHN DOE", parsed.merchant)
        assertEquals(TransactionDirection.CREDIT, parsed.direction)
    }

    @Test
    fun `learned rules sit below hand-written custom rules`() {
        val rule = RegexSynthesizer.synthesize(message(tabbyBody), tabbyExtraction, TransactionDirection.DEBIT)!!
        assertEquals(RegexSynthesizer.LEARNED_RULE_PRIORITY, rule.priority)
        assertTrue(rule.priority < 100)
        assertTrue(rule.name.startsWith(RegexSynthesizer.LEARNED_RULE_PREFIX))
    }

    @Test
    fun `title joins the body exactly like the parser's own matching text`() {
        val msg = message("AED229.59 with Binance Mastercard ••2957", title = "CARREFOUR-MAF MKT YAS")
        val rule = RegexSynthesizer.synthesize(
            msg,
            AiExtraction.Transaction(BigDecimal("229.59"), "AED", "CARREFOUR-MAF MKT YAS"),
            TransactionDirection.DEBIT,
        )!!
        val parsed = parseWith(rule, msg)!!
        assertEquals(0, BigDecimal("229.59").compareTo(parsed.amount))
        assertEquals("CARREFOUR-MAF MKT YAS", parsed.merchant)
    }
}
