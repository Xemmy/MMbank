package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions from the whole-codebase review: parser findings 2, 3 and 4. */
class ParserReviewFindingsTest {

    private val parser = TransactionParser(BuiltInRules.all, fallbackCurrency = "AED")

    private fun parse(body: String) = parser.parse(
        CapturedMessage(TransactionSource.SMS, "BANK", null, body, 1_700_000_000_000),
    )

    // --- Finding 2: non-transactions and mis-signed card messages ---

    @Test fun `blocked card notice is not a transaction`() {
        assertNull(parse("Your Debit Card ending 1234 has been blocked"))
    }

    @Test fun `credit card transaction is spend not income`() {
        val r = parse("Your Credit Card ending 1234: transaction AED 75.00 at SPINNEYS")
        assertNotNull(r)
        assertEquals(TransactionDirection.DEBIT, r!!.direction)
        assertEquals(BigDecimal("75.00"), r.amount)
        assertEquals("AED", r.currencyCode)
        assertEquals("SPINNEYS", r.merchant)
    }

    @Test fun `credited transaction wording stays a credit`() {
        val r = parse("Transaction: AED 500.00 credited to your account")
        // Whatever rule reads it, it must never be turned into spend by the transaction rule.
        if (r != null) assertTrue(r.matchedRuleName != "builtin.en.debit.transaction")
    }

    @Test fun `otp message is not a transaction`() {
        assertNull(parse("Your OTP is 123456 for AED 100.00 purchase at SHOP. Do not share it."))
    }

    @Test fun `declined purchase is not a transaction`() {
        assertNull(parse("Your purchase of AED 50.00 at SHOP was declined"))
    }

    @Test fun `card ending is never read as the amount`() {
        assertNull(parse("Debit card ending 1234 was used"))
    }

    // --- Finding 3: debit flipped to credit ---

    @Test fun `will be received by beneficiary does not flip a debit`() {
        val r = parse("AED 5,000.00 has been debited from your account. Funds will be received by the beneficiary")
        assertNotNull(r)
        assertEquals(TransactionDirection.DEBIT, r!!.direction)
        assertEquals(BigDecimal("5000.00"), r.amount)
    }

    @Test fun `direction override ignores future received wording`() {
        assertEquals(
            TransactionDirection.DEBIT,
            GenericExtractors.detectDirectionOverride("AED 50 debited. Funds will be received by the beneficiary"),
        )
        assertEquals(
            TransactionDirection.CREDIT,
            GenericExtractors.detectDirectionOverride("You have received AED 50"),
        )
    }

    // --- Finding 4: unknown currency stored as the default ---

    @Test fun `known extra currency is kept`() {
        val r = parse("You spent THB 1,500.00 at BANGKOK SHOP")
        assertNotNull(r)
        assertEquals("THB", r!!.currencyCode)
        assertEquals(BigDecimal("1500.00"), r.amount)
    }

    @Test fun `dhs maps to AED`() {
        assertEquals("AED", CurrencyNormalizer.normalize("Dhs"))
        assertEquals("AED", parse("You spent Dhs 40.00 at SHOP")?.currencyCode)
    }

    @Test fun `unrecognised currency code is not stored as the default`() {
        assertNull(parse("You spent XYZ 1,500.00 at SHOP"))
    }

    // --- "Credit Card" / "Cr Limit" / "Debit Card" name a product, not a direction (0.140.1) ---

    @Test fun `spend on a credit card is the spend, not the remaining limit`() {
        val r = parse("AED 75.00 spent on your Credit Card. Avl Cr Limit: AED 9,925.00")
        assertNotNull(r)
        assertEquals(TransactionDirection.DEBIT, r!!.direction)
        assertEquals(BigDecimal("75.00"), r.amount)
        assertEquals("AED", r.currencyCode)
    }

    @Test fun `card payment debit is the payment, not the balance`() {
        val r = parse("AED 3,600.00 debited from your account *963 towards Credit Card payment. Avl Bal AED 5,000.00")
        assertNotNull(r)
        assertEquals(TransactionDirection.DEBIT, r!!.direction)
        assertEquals(BigDecimal("3600.00"), r.amount)
        assertEquals("963", r.accountNumberSuffix)
    }

    @Test fun `credit to a debit card is the credit, not the balance`() {
        val r = parse("AED 120.00 credited to your Debit Card *1234. Avl Bal AED 3,450.00")
        assertNotNull(r)
        assertEquals(TransactionDirection.CREDIT, r!!.direction)
        assertEquals(BigDecimal("120.00"), r.amount)
    }

    @Test fun `a debit card message with no other verb still parses as before`() {
        val r = parse("Debit card transaction of AED 50.00 at CARREFOUR")
        assertNotNull(r)
        assertEquals(TransactionDirection.DEBIT, r!!.direction)
        assertEquals(BigDecimal("50.00"), r.amount)
    }

    // --- Non-bank notifications read by the no-currency fallback rules (0.140.1) ---

    @Test fun `an order number is not a credit`() {
        assertNull(parse("We received your order #78402!"))
        assertNull(parse("We received your order 78402, thank you"))
        assertNull(parse("Payment received for invoice no. 5521"))
    }

    @Test fun `a clock time is not an amount`() {
        assertNull(parse("Reminder: appointment with Dr. Ali at 4:30 PM"))
        assertNull(parse("Dr Sara confirmed your visit at 9 am"))
    }

    @Test fun `a plain no-currency spend still parses`() {
        val r = parse("You spent 45.50 at SHOP")
        assertNotNull(r)
        assertEquals(BigDecimal("45.50"), r!!.amount)
        assertEquals("AED", r.currencyCode)
    }

    // --- ParseGuards ---

    @Test fun `guards detect identifiers and unknown codes`() {
        val t1 = "card ending 1234"
        assertTrue(ParseGuards.isIdentifierAmount(t1, 12..15))
        val t2 = "spent 1234 at shop"
        assertFalse(ParseGuards.isIdentifierAmount(t2, 6..9))
        val t3 = "XYZ 1500"
        assertTrue(ParseGuards.hasUnrecognisedCurrencyBefore(t3, 4..7))
        assertFalse(ParseGuards.hasUnrecognisedCurrencyBefore("spent 1500", 6..9))
        assertTrue(ParseGuards.isNonTransaction("Purchase DECLINED"))
        assertFalse(ParseGuards.isNonTransaction("You spent AED 5 at shop"))
    }
}
