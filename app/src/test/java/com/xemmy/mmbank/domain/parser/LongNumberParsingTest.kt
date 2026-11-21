package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Amounts and account numbers of 6+ digits read the same way in every extractor. */
class LongNumberParsingTest {

    private val parser = TransactionParser(BuiltInRules.all, fallbackCurrency = "USD")

    private fun sms(body: String) = CapturedMessage(
        source = TransactionSource.SMS,
        sender = "BANK",
        title = null,
        body = body,
        timestampMillis = 1_700_000_000_000,
    )

    @Test fun `indian lakh grouping is the full amount, not the first digit`() {
        assertEquals(BigDecimal("100000.00"), parser.parse(sms("You spent INR 1,00,000.00 at CROMA MUMBAI"))!!.amount)
        assertEquals(BigDecimal("1234567.89"), parser.parse(sms("You spent INR 12,34,567.89 at CROMA MUMBAI"))!!.amount)
    }

    @Test fun `western grouping and plain amounts still read as before`() {
        assertEquals(BigDecimal("1234567.00"), parser.parse(sms("You spent AED 1,234,567.00 at SHOP"))!!.amount)
        assertEquals(BigDecimal("123456.50"), parser.parse(sms("You spent AED 123456.50 at SHOP"))!!.amount)
        assertEquals(BigDecimal("12345.00"), parser.parse(sms("You spent AED 12,345.00 at SHOP"))!!.amount)
    }

    @Test fun `the signature extractor and the balance reader use the same grouping`() {
        val amount = GenericExtractors.extractAmount("Payment INR 1,00,000 done")!!
        assertEquals(BigDecimal("100000"), amount.amount)
        assertEquals("INR", amount.currencyCode)
        assertEquals(
            BigDecimal("1234567.89"),
            BalanceExtractor.extract("Available balance is INR 12,34,567.89", "INR")!!.value,
        )
    }

    @Test fun `a long digit run next to a currency is not an amount`() {
        assertNull(GenericExtractors.extractAmount("Order 12345678901 AED"))
    }

    @Test fun `account numbers longer than 9 digits are kept whole`() {
        assertEquals("1234567890", GenericExtractors.extractAccountSuffix("Paid AED 50 with card **1234567890"))
        val parsed = parser.parse(sms("You spent AED 50.00 at SHOP from account *1234567890123"))!!
        assertEquals("1234567890123", parsed.accountNumberSuffix)
        assertEquals("810001", parser.parse(sms("You spent AED 50.00 at SHOP from account *XXX810001"))!!.accountNumberSuffix?.filter(Char::isDigit))
    }
}
