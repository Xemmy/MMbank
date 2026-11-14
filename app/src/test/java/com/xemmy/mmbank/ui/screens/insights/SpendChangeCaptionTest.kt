package com.xemmy.mmbank.ui.screens.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpendChangeCaptionTest {

    @Test
    fun `caption under the Spent tile`() {
        assertEquals("↑18% vs this point last month", spendChangeCaption(18.2, "vs this point last month"))
        assertEquals("↓5% vs previous period", spendChangeCaption(-5.4, "vs previous period"))
        assertEquals("≈ same vs previous period", spendChangeCaption(0.3, "vs previous period"))
        assertNull(spendChangeCaption(null, "vs previous period"))
        assertNull(spendChangeCaption(10.0, null))
    }
}
