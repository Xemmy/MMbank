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
}
