package com.xemmy.mmbank.util

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountInputTest {
    private fun p(s: String) = AmountInput.parse(s)

    @Test
    fun decimalCommaIsNotMultipliedByHundred() {
        assertEquals(0, BigDecimal("12.50").compareTo(p("12,50")))
        assertEquals(0, BigDecimal("0.5").compareTo(p("0,5")))
    }

    @Test
    fun thousandsSeparatorsAreStripped() {
        assertEquals(0, BigDecimal("1250").compareTo(p("1,250")))
        assertEquals(0, BigDecimal("1250.50").compareTo(p("1,250.50")))
        assertEquals(0, BigDecimal("1234567").compareTo(p("1,234,567")))
    }

    @Test
    fun europeanFormatIsRead() {
        assertEquals(0, BigDecimal("1250.50").compareTo(p("1.250,50")))
        assertEquals(0, BigDecimal("1250000").compareTo(p("1.250.000")))
    }

    @Test
    fun spacesAndSignAreHandled() {
        assertEquals(0, BigDecimal("2000").compareTo(p("2 000")))
        assertEquals(0, BigDecimal("-300").compareTo(p(" -300 ")))
        assertEquals(0, BigDecimal("2000").compareTo(p("2 000")))
    }

    @Test
    fun blankAndGarbageAreNull() {
        assertNull(p(""))
        assertNull(p("  "))
        assertNull(p("abc"))
    }
}
