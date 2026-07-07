package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.TransactionCategory
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
    fun `tabby card and cash rules parse and pin their own accounts`() {
        val card = RuleCompiler.compile(
            ParsingRule(
                id = 1,
                name = "Tabby Card spend",
                pattern = "You spent (?<currency>[A-Z]{3}) (?<amount>[\\d,]+(?:\\.\\d+)?) " +
                    "at (?<merchant>[^.]+?)\\. Your available Tabby Card limit",
                senderPattern = "tabby",
                direction = TransactionDirection.DEBIT,
                priority = 120,
                assignAccountId = 11L,
            )
        )!!
        val cash = RuleCompiler.compile(
            ParsingRule(
                id = 2,
                name = "Tabby Cash spend",
                pattern = "You spent (?<currency>[A-Z]{3}) (?<amount>[\\d,]+(?:\\.\\d+)?) " +
                    "at (?<merchant>[^.]+?)\\. Your available Tabby Cash balance",
                senderPattern = "tabby",
                direction = TransactionDirection.DEBIT,
                priority = 120,
                assignAccountId = 22L,
            )
        )!!
        val p = TransactionParser(listOf(card, cash) + BuiltInRules.all, "USD")

        val cardTx = p.parse(
            sms(
                "You spent AED 110.90 at CAREEM RIDE. Your available Tabby Card limit is now AED 6111.71.",
                sender = "com.tabby.customer",
            )
        )
        assertNotNull(cardTx)
        assertEquals("Tabby Card spend", cardTx!!.matchedRuleName)
        assertEquals(BigDecimal("110.90"), cardTx.amount)
        assertEquals("AED", cardTx.currencyCode)
        assertEquals("CAREEM RIDE", cardTx.merchant)
        assertEquals(11L, cardTx.assignAccountId)

        val cashTx = p.parse(
            sms(
                "You spent AED 32.95 at CARREFOUR-MAF MKT YAS. Your available Tabby Cash balance is now AED 1675.59.",
                sender = "tabby",
            )
        )
        assertNotNull(cashTx)
        assertEquals("Tabby Cash spend", cashTx!!.matchedRuleName)
        assertEquals(BigDecimal("32.95"), cashTx.amount)
        assertEquals("CARREFOUR-MAF MKT YAS", cashTx.merchant)
        assertEquals(22L, cashTx.assignAccountId)
    }

    // --- Account-number extraction: real-world message formats ---

    @Test
    fun `english amount-first debit extracts account number`() {
        val r = parser.parse(
            sms("Dear Customer, AED 1895.00 was debited from your account *617. Your available account balance is AED 46120.85")
        )
        assertNotNull(r)
        assertEquals(BigDecimal("1895.00"), r!!.amount)
        assertEquals("AED", r.currencyCode)
        assertEquals(TransactionDirection.DEBIT, r.direction)
        assertEquals("617", r.accountNumberSuffix)
    }

    @Test
    fun `english amount-first credit extracts account number`() {
        val r = parser.parse(
            sms("Dear Customer, AED 1.00 was credited to your account *963. Your available account balance is AED 5961.60")
        )
        assertNotNull(r)
        assertEquals(BigDecimal("1.00"), r!!.amount)
        assertEquals("AED", r.currencyCode)
        assertEquals(TransactionDirection.CREDIT, r.direction)
        assertEquals("963", r.accountNumberSuffix)
    }

    @Test
    fun `tabby spend parses as debit with merchant and no account`() {
        val r = parser.parse(
            sms("You spent AED 32.95 at CARREFOUR-MAF MKT YAS. Your available Tabby Cash balance is now AED 1675.59.")
        )
        assertNotNull(r)
        assertEquals(BigDecimal("32.95"), r!!.amount)
        assertEquals("AED", r.currencyCode)
        assertEquals(TransactionDirection.DEBIT, r.direction)
        assertEquals("CARREFOUR-MAF MKT YAS", r.merchant)
        assertNull(r.accountNumberSuffix)
    }

    @Test
    fun `tabby installment purchase parses as debit`() {
        val r = parser.parse(
            sms("Your AED 2575.63 purchase at SHEIN is confirmed. To pay as low as 205.46/month, extend your payment plan in the app")
        )
        assertNotNull(r)
        assertEquals(BigDecimal("2575.63"), r!!.amount)
        assertEquals("AED", r.currencyCode)
        assertEquals(TransactionDirection.DEBIT, r.direction)
    }

    @Test
    fun `arabic outgoing transfer parses as debit with account`() {
        val r = parser.parse(
            sms("تم بنجاح تسجيل طلبك للتحويل النقدي عن طريق خدمة الإنترنت المصرفية الشخصية ل AED45000.00 من حسابك رقمXXX810001 بتاريخ. Jun 28 2026 7:08PM")
        )
        assertNotNull(r)
        assertEquals(BigDecimal("45000.00"), r!!.amount)
        assertEquals("AED", r.currencyCode)
        assertEquals(TransactionDirection.DEBIT, r.direction)
        assertEquals("XXX810001", r.accountNumberSuffix)
    }

    @Test
    fun `arabic salary credit parses with account`() {
        val r = parser.parse(
            sms("لقد تمّ تحويل راتبك AED62350.00 إلى حسابك رقم XXX810001 بتاريخ Jun 25 2026 6:23AM. الرصيد المتوفر 106494.95.")
        )
        assertNotNull(r)
        assertEquals(BigDecimal("62350.00"), r!!.amount)
        assertEquals("AED", r.currencyCode)
        assertEquals(TransactionDirection.CREDIT, r.direction)
        assertEquals("XXX810001", r.accountNumberSuffix)
    }

    @Test
    fun `arabic Cr operation parses as credit with account`() {
        val r = parser.parse(
            sms("تمت عملية Cr بمبلغ AED1880.39 على حسابك رقم XXX810001 بنجاح. رصيد حسابك الحالي AED44081.95")
        )
        assertNotNull(r)
        assertEquals(BigDecimal("1880.39"), r!!.amount)
        assertEquals("AED", r.currencyCode)
        assertEquals(TransactionDirection.CREDIT, r.direction)
        assertEquals("XXX810001", r.accountNumberSuffix)
    }

    @Test
    fun `amount 1 AED with account marker is not mistaken for the account number`() {
        // Regression: the fallback bare rule used to capture "617" (from *617)
        // as the amount. The amount must be 1.00 and the account must be 617.
        val debit = parser.parse(
            sms("Dear Customer, AED 1.00 was debited from your account *617. Your available account balance is AED 46122.24")
        )
        assertNotNull(debit)
        assertEquals(BigDecimal("1.00"), debit!!.amount)
        assertEquals("AED", debit.currencyCode)
        assertEquals(TransactionDirection.DEBIT, debit.direction)
        assertEquals("617", debit.accountNumberSuffix)

        val credit = parser.parse(
            sms("Dear Customer, AED 1.00 was credited to your account *963. Your available account balance is AED 5961.60")
        )
        assertNotNull(credit)
        assertEquals(BigDecimal("1.00"), credit!!.amount)
        assertEquals("AED", credit.currencyCode)
        assertEquals(TransactionDirection.CREDIT, credit.direction)
        assertEquals("963", credit.accountNumberSuffix)
    }

    // --- Tabby purchase confirmations ("Transaction of ... At {merchant} ...") ---

    @Test
    fun `tabby purchase confirmation - was successful shape`() {
        val r = parser.parse(
            sms(
                "Transaction of AED 20.00 At Tips and Toes was successful. Your available Tabby Card limit is AED 6676.12.",
                sender = "com.tabby.customer",
            )
        )
        assertNotNull(r)
        assertEquals(BigDecimal("20.00"), r!!.amount)
        assertEquals("AED", r.currencyCode)
        assertEquals(TransactionDirection.DEBIT, r.direction)
        assertEquals("Tips and Toes", r.merchant)
    }

    @Test
    fun `tabby purchase confirmation - careem ride`() {
        val r = parser.parse(
            sms(
                "Transaction of AED 44.65 At CAREEM RIDE was successful. Your available Tabby Card limit is AED 6696.12.",
                sender = "com.tabby.customer",
            )
        )
        assertNotNull(r)
        assertEquals(BigDecimal("44.65"), r!!.amount)
        assertEquals(TransactionDirection.DEBIT, r.direction)
        assertEquals("CAREEM RIDE", r.merchant)
    }

    @Test
    fun `tabby purchase confirmation - patchi llc`() {
        val r = parser.parse(
            sms(
                "Transaction of AED 72.25 At PATCHI LLC was successful. Your available Tabby Card limit is AED 6740.77.",
                sender = "com.tabby.customer",
            )
        )
        assertNotNull(r)
        assertEquals(BigDecimal("72.25"), r!!.amount)
        assertEquals(TransactionDirection.DEBIT, r.direction)
        assertEquals("PATCHI LLC", r.merchant)
    }

    @Test
    fun `tabby purchase confirmation with split-purchase suffix - regression for card-limit mis-fire`() {
        // Regression: a lower-priority rule used to match "purchase" later in
        // this message and wrongly grab the *remaining card limit* (6813.02)
        // as the amount, with no merchant. The real purchase is 1553.00 at
        // Sephora ME.
        val r = parser.parse(
            sms(
                "Transaction of AED 1553.00 At Sephora ME. Your Tabby Card limit is now AED 6813.02. " +
                    "You can now split your purchase into 8 months.",
                sender = "com.tabby.customer",
            )
        )
        assertNotNull(r)
        assertEquals(BigDecimal("1553.00"), r!!.amount)
        assertEquals("Sephora ME", r.merchant)
        assertEquals(TransactionDirection.DEBIT, r.direction)
    }

    // --- Tabby cashback-earned notifications ---

    @Test
    fun `tabby cashback earned parses as credit tagged with cashback category`() {
        val r = parser.parse(
            sms(
                "You earned AED 1.00! You got cashback with Tabby+. Your cashback will be processed after next statement is paid.",
                sender = "com.tabby.customer",
            )
        )
        assertNotNull(r)
        assertEquals(BigDecimal("1.00"), r!!.amount)
        assertEquals("AED", r.currencyCode)
        assertEquals(TransactionDirection.CREDIT, r.direction)
        assertEquals(TransactionCategory.CASHBACK_EARNED, r.category)
    }

    @Test
    fun `tabby cashback earned - decimal amount`() {
        val r = parser.parse(
            sms(
                "You earned AED 2.23! You got cashback with Tabby+. Your cashback will be processed after next statement is paid.",
                sender = "com.tabby.customer",
            )
        )
        assertNotNull(r)
        assertEquals(BigDecimal("2.23"), r!!.amount)
        assertEquals(TransactionCategory.CASHBACK_EARNED, r.category)
    }

    @Test
    fun `tabby cashback earned - larger amount`() {
        val r = parser.parse(
            sms(
                "You earned AED 77.65! You got cashback with Tabby+. Your cashback will be processed after next statement is paid.",
                sender = "com.tabby.customer",
            )
        )
        assertNotNull(r)
        assertEquals(BigDecimal("77.65"), r!!.amount)
        assertEquals(TransactionCategory.CASHBACK_EARNED, r.category)
    }

    @Test
    fun `ordinary transactions default to NORMAL category`() {
        val r = parser.parse(sms("You spent AED 250.75 at CARREFOUR DUBAI. Avl bal AED 1,200"))
        assertNotNull(r)
        assertEquals(TransactionCategory.NORMAL, r!!.category)
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
