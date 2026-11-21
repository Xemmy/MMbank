package com.xemmy.mmbank.ui.designsystem

import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MoneyFormatTest {
    @Test
    fun `tone follows the kind`() {
        assertEquals(MoneyTone.IN, moneyToneFor(TransactionKind.INCOME))
        assertEquals(MoneyTone.IN, moneyToneFor(TransactionKind.CASHBACK))
        assertEquals(MoneyTone.OUT, moneyToneFor(TransactionKind.SPEND))
        assertEquals(MoneyTone.OUT, moneyToneFor(TransactionKind.ATM))
        assertEquals(MoneyTone.NEUTRAL, moneyToneFor(TransactionKind.TRANSFER))
        assertEquals(MoneyTone.NEUTRAL, moneyToneFor(TransactionKind.ADJUSTMENT))
    }

    @Test
    fun `signs per kind and adjustment direction`() {
        assertEquals("+", moneySignFor(TransactionKind.INCOME, 5.0))
        assertEquals("−", moneySignFor(TransactionKind.SPEND, 5.0))
        assertEquals("", moneySignFor(TransactionKind.TRANSFER, 5.0))
        assertEquals("+", moneySignFor(TransactionKind.ADJUSTMENT, 5.0))
        assertEquals("−", moneySignFor(TransactionKind.ADJUSTMENT, -5.0))
    }

    @Test
    fun `display string uses currency digits and sign`() {
        assertEquals("−46.12 AED", moneyDisplayString(46.12, "AED", TransactionKind.SPEND, false))
        assertEquals("+1,234.50 AED", moneyDisplayString(1234.5, "AED", TransactionKind.INCOME, false))
        assertEquals("−1,500 JPY", moneyDisplayString(1500.0, "JPY", TransactionKind.SPEND, false))
        assertEquals("0.50 AED", moneyDisplayString(0.5, "AED", TransactionKind.TRANSFER, false))
    }

    @Test
    fun `hidden display is the mask and never contains the figure`() {
        val shown = moneyDisplayString(46.12, "AED", TransactionKind.SPEND, true)
        assertEquals(AMOUNT_MASK, shown)
        assertFalse(shown.contains("46"))
    }

    @Test
    fun `spoken form uses major and minor units`() {
        assertEquals("spent 46 dirhams 12 fils", moneyContentDescription(46.12, "AED", TransactionKind.SPEND, false))
        assertEquals("received 1 dirham", moneyContentDescription(1.0, "AED", TransactionKind.INCOME, false))
        assertEquals("spent 12 fils", moneyContentDescription(0.12, "AED", TransactionKind.SPEND, false))
        assertEquals("spent 5 dollars 1 cent", moneyContentDescription(5.01, "USD", TransactionKind.SPEND, false))
        assertEquals("spent 2 pounds 1 penny", moneyContentDescription(2.01, "GBP", TransactionKind.SPEND, false))
        assertEquals("spent 0 dirhams", moneyContentDescription(0.0, "AED", TransactionKind.SPEND, false))
    }

    @Test
    fun `spoken form falls back to number and code for other currencies`() {
        assertEquals("spent 1,500 JPY", moneyContentDescription(1500.0, "JPY", TransactionKind.SPEND, false))
        assertEquals("received 0.05 BTC", moneyContentDescription(0.05, "BTC", TransactionKind.INCOME, false))
    }

    @Test
    fun `hidden spoken form leaks nothing`() {
        assertEquals("amount hidden", moneyContentDescription(46.12, "AED", TransactionKind.SPEND, true))
    }

    @Test
    fun `compact number keeps whole units below a million then abbreviates`() {
        assertEquals("0", compactMoneyNumber(0.0))
        assertEquals("4,464", compactMoneyNumber(4464.37))
        assertEquals("999,999", compactMoneyNumber(999_999.2))
        assertEquals("1.06M", compactMoneyNumber(1_064_795.18))
        assertEquals("10.6M", compactMoneyNumber(10_640_000.0))
        assertEquals("1.2B", compactMoneyNumber(1.2e9))
        assertEquals("1M", compactMoneyNumber(999_999.9))
        assertEquals("4,464", compactMoneyNumber(-4464.37))
    }

    @Test
    fun `compact display string carries sign and code, mask when hidden`() {
        assertEquals("−4,464 AED", moneyDisplayStringCompact(4464.37, "AED", TransactionKind.SPEND, false))
        assertEquals("+1.06M AED", moneyDisplayStringCompact(1_064_795.0, "AED", TransactionKind.INCOME, false))
        assertEquals(AMOUNT_MASK, moneyDisplayStringCompact(5.0, "AED", TransactionKind.SPEND, true))
    }
}
