package com.xemmy.mmbank.ui.screens.dashboard

import com.xemmy.mmbank.ui.util.asMoney
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Net stat tile is income − spend ([netFlow]). This locks in that a spend
 * larger than income yields a correctly-signed, full-digit money string — the
 * reported "-¥" (currency symbol with no digits) must never recur.
 */
class DashboardNetFormatTest {

    @Test
    fun negativeNet_keepsSignAndDigits() {
        // Income 2,282.46 and Spent 3,430.68 → net −1,148.22.
        val formatted = netFlow(income = 2282.46, spend = 3430.68).asMoney("USD")
        // Digits must survive (the reported "-¥" dropped them). Strip the
        // thousands separator so the grouping comma doesn't hide the match.
        assertTrue("expected digits in [$formatted]", formatted.replace(",", "").contains("1148.22"))
        assertTrue("expected leading minus in [$formatted]", formatted.startsWith("-"))
        assertEquals("-1,148.22 USD", formatted)
    }

    @Test
    fun positiveNet_hasNoSign() {
        val formatted = netFlow(income = 5000.0, spend = 1200.0).asMoney("USD")
        assertEquals("3,800.00 USD", formatted)
    }

    @Test
    fun zeroNet_formatsCleanly() {
        val formatted = netFlow(income = 1000.0, spend = 1000.0).asMoney("AED")
        assertEquals("0.00 AED", formatted)
    }
}
