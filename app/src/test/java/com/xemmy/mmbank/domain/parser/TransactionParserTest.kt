package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionParserTest {

    private val parser = TransactionParser(BuiltInRules.all, fallbackCurrency = "USD")

    private fun sms(body: String, sender: String? = "BANK") = CapturedMessage(
        source = TransactionSource.SMS,
        sender = sender,
        title = null,
        body = body,
        timestampMillis = 1_700_000_000_000,
    )

    @Test
    fun `parses english debit with currency before amount`() {
        val result = parser.parse(sms("You spent AED 250.75 at CARREFOUR DUBAI. Avl bal AED 1,200"))
        assertNotNull(result)
        assertEquals(BigDecimal("250.75"), result!!.amount)
        assertEquals("AED", result.currencyCode)
        assertEquals(TransactionDirection.DEBIT, result.direction)
        assertEquals("CARREFOUR DUBAI", result.merchant)
    }

    @Test
    fun `parses english credit with amount before currency`() {
        val result = parser.parse(sms("Salary received 12,500.00 SAR in account x1234"))
        assertNotNull(result)
        assertEquals(BigDecimal("12500.00"), result!!.amount)
        assertEquals("SAR", result.currencyCode)
        assertEquals(TransactionDirection.CREDIT, result.direction)
    }

    @Test
    fun `parses arabic debit with arabic-indic digits and arabic currency`() {
        val result = parser.parse(sms("تم خصم مبلغ ٢٥٠٫٧٥ ر.س من بطاقتك في كارفور"))
        assertNotNull(result)
        assertEquals(BigDecimal("250.75"), result!!.amount)
        assertEquals("SAR", result.currencyCode)
        assertEquals(TransactionDirection.DEBIT, result.direction)
    }

    @Test
    fun `parses arabic credit`() {
        val result = parser.parse(sms("تم إيداع مبلغ 5000 د.إ في حسابك"))
        assertNotNull(result)
        assertEquals(BigDecimal("5000"), result!!.amount)
        assertEquals("AED", result.currencyCode)
        assertEquals(TransactionDirection.CREDIT, result.direction)
    }

    @Test
    fun `falls back to default currency when message has none`() {
        val result = parser.parse(sms("Debited 99.90 for subscription"))
        assertNotNull(result)
        assertEquals("USD", result!!.currencyCode)
    }

    @Test
    fun `ignores non-financial messages`() {
        assertNull(parser.parse(sms("Your OTP code is 482913. Do not share it.")))
        assertNull(parser.parse(sms("Hey, are we still meeting at 5?", sender = "+123456")))
    }

    @Test
    fun `custom rule outranks built-ins and respects sender filter`() {
        val custom = RuleCompiler.compile(
            ParsingRule(
                id = 7,
                name = "MyBank weird layout",
                pattern = "TXN:(?<amount>\\d+(?:\\.\\d+)?)/(?<currency>[A-Z]{3})",
                senderPattern = "MYBANK",
                direction = TransactionDirection.DEBIT,
            )
        )!!
        val p = TransactionParser(listOf(custom) + BuiltInRules.all, "USD")

        val matched = p.parse(sms("TXN:42.50/EUR ref 998", sender = "MYBANK"))
        assertNotNull(matched)
        assertEquals("MyBank weird layout", matched!!.matchedRuleName)
        assertEquals(BigDecimal("42.50"), matched.amount)
        assertEquals("EUR", matched.currencyCode)

        // Same body from another sender: custom rule skipped, no built-in matches.
        assertNull(p.parse(sms("TXN:42.50/EUR ref 998", sender = "OTHER")))
    }

    @Test
    fun `rule compiler rejects invalid patterns`() {
        assertNull(RuleCompiler.compile(ParsingRule(name = "bad", pattern = "([", direction = TransactionDirection.DEBIT)))
        assertNull(RuleCompiler.compile(ParsingRule(name = "no-amount", pattern = "spent \\d+", direction = TransactionDirection.DEBIT)))
        assertNotNull(RuleCompiler.validate("(["))
        assertNotNull(RuleCompiler.validate("no amount group"))
        assertNull(RuleCompiler.validate("paid (?<amount>\\d+)"))
    }
}
