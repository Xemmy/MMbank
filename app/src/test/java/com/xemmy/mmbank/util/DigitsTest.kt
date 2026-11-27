package com.xemmy.mmbank.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DigitsTest {

    @Test
    fun `arabic-indic and extended digits fold to ascii`() {
        assertEquals("0551234567", Digits.fold("٠٥٥١٢٣٤٥٦٧"))
        assertEquals("1234567890", Digits.fold("۱۲۳۴۵۶۷۸۹۰"))
        assertEquals("card 12-34", Digits.fold("card ١٢-٣٤"))
    }

    @Test
    fun `text without arabic digits is returned as is`() {
        val s = "AED 1,250.50"
        assertSame(s, Digits.fold(s))
    }

    @Test
    fun `only keeps the digits, in ascii`() {
        assertEquals("0734", Digits.only("AE07-٣٤"))
        assertEquals("810001", Digits.only("XXX810001"))
        assertEquals("", Digits.only(null))
    }
}
