package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Card-ending routing tier (Signature → Keyword/Regex → **Card Ending** →
 * unparsed). Covers the confirmed real-world miss: a merchant-side spend
 * notification "AED27.60 with Tabby Cash Card ••4543" that carries no verb
 * keyword and no signature, so it matched nothing and was silently dropped
 * while the paired issuer "You've earned AED 0.83 cashback" was captured.
 */
class CardEndingRoutingTest {

    private val parser = TransactionParser(BuiltInRules.all)

    private fun msg(body: String) = CapturedMessage(
        source = TransactionSource.PUSH_NOTIFICATION,
        sender = "com.tabby.app",
        title = null,
        body = body,
        timestampMillis = 1_700_000_000_000,
    )

    /** 1. The failing message now routes via its card ending. */
    @Test
    fun tabbyCashCardSpend_routesByCardEnding() {
        val parsed = parser.parse(msg("AED27.60 with Tabby Cash Card ••4543"))

        assertNotNull("keyword-less card-ending message must be captured", parsed)
        parsed!!
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals(0, BigDecimal("27.60").compareTo(parsed.amount))
        assertEquals("AED", parsed.currencyCode)
        assertEquals("4543", parsed.accountNumberSuffix)
        assertEquals("cardending.tabby.cash", parsed.matchedRuleName)
    }

    /** 2. The extractor recognizes every card-marker form and nothing else. */
    @Test
    fun cardEndingExtractor_marks() {
        assertEquals("4543", CardEndingExtractor.extract("Tabby Cash Card ••4543"))
        assertEquals("4543", CardEndingExtractor.extract("card •4543"))
        assertEquals("4543", CardEndingExtractor.extract("card *4543"))
        assertEquals("4543", CardEndingExtractor.extract("ending 4543"))
        assertEquals("4543", CardEndingExtractor.extract("ending in 4543"))
        assertNull(CardEndingExtractor.extract("invoice 2024 total AED27.60"))
    }

    /** 3. An untrusted ending is never consumed — no fabricated transaction. */
    @Test
    fun unknownCardEnding_fallsThrough() {
        assertNull(parser.parse(msg("AED27.60 with Tabby Cash Card ••9999")))
    }

    /** 4. Regression: the non-spaced, no-thousands amount parses reliably. */
    @Test
    fun genericAmount_parsesNonSpacedAmount() {
        val extracted = GenericExtractors.extractAmount("AED27.60")
        assertNotNull(extracted)
        assertEquals(0, BigDecimal("27.60").compareTo(extracted!!.amount))
        assertEquals("AED", extracted.currencyCode)
    }
}
