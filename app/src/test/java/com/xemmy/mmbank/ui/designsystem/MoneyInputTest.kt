package com.xemmy.mmbank.ui.designsystem

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyInputTest {
    @Test
    fun `parses plain, leading-dot and grouped amounts`() {
        assertEquals(BigDecimal("125.50"), parseMoneyInput("125.50"))
        assertEquals(BigDecimal("0.5"), parseMoneyInput(".5"))
        assertEquals(BigDecimal("1250.50"), parseMoneyInput("1,250.50"))
        assertEquals(BigDecimal("12"), parseMoneyInput(" 12 "))
    }

    @Test
    fun `rejects blank, negative and garbage`() {
        assertNull(parseMoneyInput(""))
        assertNull(parseMoneyInput("-5"))
        assertNull(parseMoneyInput("abc"))
        assertNull(parseMoneyInput("1.2.3"))
        assertNull(parseMoneyInput("12,34"))
    }

    @Test
    fun `sanitize keeps digits commas and one dot`() {
        assertEquals("1,250.5", sanitizeMoneyInput("1,250.5"))
        assertEquals("12.50", sanitizeMoneyInput("a1 2.5-0"))
        assertEquals("1.23", sanitizeMoneyInput("1.2.3"))
    }

    @Test
    fun `error rules`() {
        assertNull(moneyFieldError(""))
        assertEquals(MONEY_FIELD_HINT, moneyFieldError("", required = true))
        assertEquals(MONEY_FIELD_HINT, moneyFieldError("abc"))
        assertEquals("Amount must be greater than 0", moneyFieldError("0"))
        assertEquals("Use at most 2 decimal places", moneyFieldError("1.234"))
        assertEquals("Use a whole number", moneyFieldError("1.5", maxFractionDigits = 0))
        assertNull(moneyFieldError("125.50"))
        assertNull(moneyFieldError("1.234", maxFractionDigits = 3))
    }

    @Test
    fun `indian grouping and arabic digits read as the full amount`() {
        assertEquals(BigDecimal("100000"), parseMoneyInput("1,00,000"))
        assertEquals(BigDecimal("1234567.50"), parseMoneyInput("12,34,567.50"))
        assertEquals(BigDecimal("1250.5"), parseMoneyInput("١٬٢٥٠٫٥"))
        assertEquals("1250.5", sanitizeMoneyInput("١٢٥٠٫٥"))
        assertNull(moneyFieldError("١٢٥٫٥٠"))
    }
}
