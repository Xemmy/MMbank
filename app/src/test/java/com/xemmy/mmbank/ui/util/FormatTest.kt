package com.xemmy.mmbank.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `asMoneyWhole drops the cents and rounds to the nearest unit`() {
        assertEquals("18,269 AED", 18269.49.asMoneyWhole("AED"))
        assertEquals("18,270 AED", 18269.50.asMoneyWhole("AED"))
        assertEquals("0 AED", 0.0.asMoneyWhole("AED"))
    }

    @Test
    fun `asMoneyWhole keeps a negative sign`() {
        assertEquals("-4,470 AED", (-4470.32).asMoneyWhole("AED"))
    }

    @Test
    fun `asMoneyWholeOrMask masks when hidden`() {
        assertEquals(AMOUNT_MASK, 18269.49.asMoneyWholeOrMask("AED", hidden = true))
        assertEquals("18,269 AED", 18269.49.asMoneyWholeOrMask("AED", hidden = false))
    }

    @Test
    fun `asMoneyWhole still splits cleanly for the AmountText typography`() {
        val display = 22739.90.asMoneyWhole("AED")
        assertEquals(MoneyParts("22,740", "AED"), splitMoneyDisplay(display))
    }

    @Test
    fun `fiat currencies use their ISO fraction digits`() {
        assertEquals("1,234.50 AED", 1234.5.asMoney("AED"))
        assertEquals("1,235 JPY", 1234.5.asMoney("JPY"))
        assertEquals("1.235 KWD", 1.2349.asMoney("KWD"))
    }

    @Test
    fun `crypto keeps up to 8 decimals and is not rounded to 0_00`() {
        assertEquals("0.00012345 BTC", 0.00012345.asMoney("BTC"))
        assertEquals("1.50 USDT", 1.5.asMoney("USDT"))
    }

    @Test
    fun `unknown currency code falls back to 2 decimals`() {
        assertEquals(2, fractionDigitsFor("ZZZZ"))
        assertEquals("3.10 ZZZZ", 3.1.asMoney("ZZZZ"))
    }
}
