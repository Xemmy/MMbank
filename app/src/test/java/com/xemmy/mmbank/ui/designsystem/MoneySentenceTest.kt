package com.xemmy.mmbank.ui.designsystem

import com.xemmy.mmbank.ui.screens.dashboard.InsightTone
import com.xemmy.mmbank.ui.screens.dashboard.SmartInsight
import com.xemmy.mmbank.ui.screens.insights.deltaSegments
import com.xemmy.mmbank.ui.screens.insights.insightSegments
import com.xemmy.mmbank.ui.screens.insights.joinSegments
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneySentenceTest {

    @Test
    fun `amounts become money segments and hidden masks them`() {
        val segs = insightSegments(SmartInsight.NoIncomeSpend(655.05), "AED")
        assertEquals(1, segs.count { it is MoneySegment.Money })
        assertEquals("No income recorded this period — 655.05 AED went out", plainText(segs, hidden = false))
        val hidden = plainText(segs, hidden = true)
        assertTrue(hidden.contains(AMOUNT_MASK))
        assertFalse(hidden.contains("655"))
    }

    @Test
    fun `unusual charge keeps the charge currency and cleans the merchant`() {
        val segs = insightSegments(SmartInsight.UnusualCharge("AL FUTTAIM TRADING LLC", 500.0, 100.0, 5.0, "USD"), "AED")
        val text = plainText(segs, false)
        assertTrue(text.contains("500.00 USD"))
        assertTrue(text.contains("100.00 USD"))
        assertTrue(text.contains("5.0×"))
        assertFalse(text.contains("AL FUTTAIM"))
        assertEquals(2, segs.count { it is MoneySegment.Money })
    }

    @Test
    fun `percent only insights have no money segments`() {
        val segs = insightSegments(SmartInsight.SpendTrend(-16.0, InsightTone.POSITIVE), "AED")
        assertTrue(segs.none { it is MoneySegment.Money })
        assertEquals("Spending is 16% down vs. the previous period", plainText(segs, false))
    }

    @Test
    fun `negative money keeps its minus`() {
        assertEquals("−5.00 AED", plainText(listOf(money(-5.0, "AED")), false))
    }

    @Test
    fun `delta lines point by direction and join with a dot`() {
        val line = joinSegments(
            listOf(deltaSegments(-8249.13, "AED", "vs 6 months ago"), deltaSegments(100.0, "AED", "vs a year ago")),
        )
        assertEquals("▼ 8,249.13 AED vs 6 months ago  ·  ▲ 100.00 AED vs a year ago", plainText(line, false))
    }
}
