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
        // Lowercase / wrong length / no space — none of these are ISO codes.
        assertEquals(MoneyParts("1,234.56 aed", null), splitMoneyDisplay("1,234.56 aed"))
        assertEquals(MoneyParts("12.00 AEDX", null), splitMoneyDisplay("12.00 AEDX"))
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
}
