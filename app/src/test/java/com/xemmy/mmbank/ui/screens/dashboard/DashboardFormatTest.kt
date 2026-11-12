package com.xemmy.mmbank.ui.screens.dashboard

import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.designsystem.moneyLabelWhole
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DashboardFormatTest {

    @Test
    fun `net worth shows amount, approximate prefix and mask`() {
        assertEquals("1,234.50 AED", netWorthDisplay(1234.5, "AED", approximate = false, hidden = false))
        assertEquals("≈ 1,234.50 AED", netWorthDisplay(1234.5, "AED", approximate = true, hidden = false))
        assertEquals("-10.00 USD", netWorthDisplay(-10.0, "USD", approximate = false, hidden = false))
    }

    @Test
    fun `hidden net worth is the mask even when approximate`() {
        val shown = netWorthDisplay(1234.5, "AED", approximate = true, hidden = true)
        assertEquals(AMOUNT_MASK, shown)
        assertFalse(shown.contains("1"))
    }

    @Test
    fun `home rows map direction to income or spend`() {
        assertEquals(TransactionKind.INCOME, homeRowKind(TransactionDirection.CREDIT))
        assertEquals(TransactionKind.SPEND, homeRowKind(TransactionDirection.DEBIT))
    }

    @Test
    fun `usd value label handles missing price and hiding`() {
        assertEquals("12.30 USD", usdValueLabel(BigDecimal("12.3"), hidden = false))
        assertEquals("—", usdValueLabel(null, hidden = false))
        assertEquals(AMOUNT_MASK, usdValueLabel(BigDecimal("12.3"), hidden = true))
        assertEquals(AMOUNT_MASK, usdValueLabel(null, hidden = true))
    }

    @Test
    fun `unassigned totals list one entry per currency`() {
        val totals = linkedMapOf("AED" to BigDecimal("10"), "USD" to BigDecimal("-5.5"))
        assertEquals("10.00 AED  -5.50 USD", unassignedTotalsLabel(totals, hidden = false))
        assertEquals("$AMOUNT_MASK  $AMOUNT_MASK", unassignedTotalsLabel(totals, hidden = true))
        assertEquals("", unassignedTotalsLabel(emptyMap(), hidden = false))
    }

    @Test
    fun `money labels keep sign, whole rounding and masking`() {
        assertEquals("-3.25 AED", moneyLabel(-3.25, "AED"))
        assertEquals("3.25 AED", moneyLabel(BigDecimal("3.25"), "AED", hidden = false))
        assertEquals(AMOUNT_MASK, moneyLabel(3.25, "AED", hidden = true))
        assertEquals("1,235 AED", moneyLabelWhole(1234.6, "AED"))
        assertEquals(AMOUNT_MASK, moneyLabelWhole(1234.6, "AED", hidden = true))
    }
}
