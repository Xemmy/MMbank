package com.xemmy.mmbank.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * splitMoneyDisplay feeds the split amount typography (small light ISO code,
 * high-contrast numerals). It must recognize exactly the `asMoney` shape and
 * pass anything else — masks, quantities, plain text — through untouched.
 */
class MoneyPartsTest {

    @Test
    fun `plain money splits into numerals and code`() {
        assertEquals(MoneyParts("1,234.56", "AED"), splitMoneyDisplay("1,234.56 AED"))
        assertEquals(MoneyParts("46.12", "USD"), splitMoneyDisplay("46.12 USD"))
    }

    @Test
    fun `four and five letter crypto codes split like fiat`() {
        // USDT (Binance) and other 3–5 letter tickers are real codes now, so
        // their trailing token must get the same small-light code styling.
        assertEquals(MoneyParts("710.00", "USDT"), splitMoneyDisplay("710.00 USDT"))
        assertEquals(MoneyParts("+710.00", "USDT"), splitMoneyDisplay("+710.00 USDT"))
        assertEquals(MoneyParts("12.50", "USDC"), splitMoneyDisplay("12.50 USDC"))
        assertEquals(MoneyParts("3.00", "MATIC"), splitMoneyDisplay("3.00 MATIC"))
    }

    @Test
    fun `sign prefix stays with the numerals`() {
        assertEquals(MoneyParts("+229.59", "AED"), splitMoneyDisplay("+229.59 AED"))
        assertEquals(MoneyParts("-1,000.00", "EUR"), splitMoneyDisplay("-1,000.00 EUR"))
    }

    @Test
    fun `round-trip matches the asMoney formatter`() {
        val display = 1234.5.asMoney("AED")
        assertEquals(MoneyParts("1,234.50", "AED"), splitMoneyDisplay(display))
    }

    @Test
    fun `hide-balances mask passes through whole`() {
        assertEquals(MoneyParts(AMOUNT_MASK, null), splitMoneyDisplay(AMOUNT_MASK))
    }

    @Test
    fun `strings without a trailing ISO code pass through whole`() {
        // Lowercase / too long / no space — none of these are currency codes.
        assertEquals(MoneyParts("1,234.56 aed", null), splitMoneyDisplay("1,234.56 aed"))
        assertEquals(MoneyParts("12.00 TOOLONG", null), splitMoneyDisplay("12.00 TOOLONG"))
        assertEquals(MoneyParts("0.00420000", null), splitMoneyDisplay("0.00420000"))
        assertEquals(MoneyParts("", null), splitMoneyDisplay(""))
    }

    @Test
    fun `multi-word text only splits the final token`() {
        assertEquals(
            MoneyParts("≈ 50.00", "AED"),
            splitMoneyDisplay("≈ 50.00 AED"),
        )
    }

    @Test
    fun `leading sign splits off so the dirham glyph can slot between sign and digits`() {
        assertEquals("+" to "229.59", splitLeadingSign("+229.59"))
        assertEquals("-" to "1,000.00", splitLeadingSign("-1,000.00"))
        assertEquals("≈ " to "129,289.66", splitLeadingSign("≈ 129,289.66"))
        assertEquals("" to "85", splitLeadingSign("85"))
        // No digits at all (the hide-balances mask): everything stays in the numeral half.
        assertEquals("" to AMOUNT_MASK, splitLeadingSign(AMOUNT_MASK))
    }
}
