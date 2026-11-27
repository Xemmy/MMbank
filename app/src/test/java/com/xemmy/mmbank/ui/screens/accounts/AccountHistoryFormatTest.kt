package com.xemmy.mmbank.ui.screens.accounts

import com.xemmy.mmbank.domain.account.AccountTimeline
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountHistoryFormatTest {

    private val fmt: (BigDecimal) -> String = { it.toPlainString() + " AED" }

    @Test
    fun `amounts carry their sign`() {
        assertEquals("+12.50 AED", signedMoney(BigDecimal("12.50"), hidden = false, format = fmt))
        assertEquals("−12.50 AED", signedMoney(BigDecimal("-12.50"), hidden = false, format = fmt))
        assertEquals("+0 AED", signedMoney(BigDecimal.ZERO, hidden = false, format = fmt))
    }

    @Test
    fun `hidden balances show the mask and no sign`() {
        assertEquals(AMOUNT_MASK, signedMoney(BigDecimal("12.50"), hidden = true, format = fmt))
        assertEquals(AMOUNT_MASK, signedMoney(BigDecimal("-12.50"), hidden = true, format = fmt))
    }

    @Test
    fun `old to new values`() {
        assertEquals("A → B", valuesLabel("A", "B", sensitive = false, hidden = false))
        assertEquals("B", valuesLabel(null, "B", sensitive = false, hidden = false))
        assertEquals("A", valuesLabel("A", null, sensitive = false, hidden = false))
        assertNull(valuesLabel(null, null, sensitive = true, hidden = true))
    }

    @Test
    fun `values with amounts are masked only while hidden`() {
        assertEquals("1000 → 1250", valuesLabel("1000", "1250", sensitive = true, hidden = false))
        assertEquals(AMOUNT_MASK, valuesLabel("1000", "1250", sensitive = true, hidden = true))
        // A name is not an amount: it stays readable with balances hidden.
        assertEquals("Old → New", valuesLabel("Old", "New", sensitive = false, hidden = true))
    }

    @Test
    fun `the empty state says why`() {
        assertTrue(emptyMessage(AccountTimeline.Filter.ALL, 0).startsWith("Nothing here yet"))
        assertTrue(emptyMessage(AccountTimeline.Filter.BALANCE, 0).contains("Earlier changes weren't recorded"))
        assertEquals("Nothing under this filter.", emptyMessage(AccountTimeline.Filter.EDITS, 4))
    }
}
