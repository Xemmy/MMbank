package com.xemmy.mmbank.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun `converts arabic-indic digits to ascii`() {
        assertEquals("1234567890", TextNormalizer.normalize("١٢٣٤٥٦٧٨٩٠"))
        assertEquals("1234567890", TextNormalizer.normalize("۱۲۳۴۵۶۷۸۹۰"))
    }

    @Test
    fun `converts arabic decimal and thousands separators`() {
        assertEquals("1,250.75", TextNormalizer.normalize("١٬٢٥٠٫٧٥"))
    }

    @Test
    fun `strips bidi controls and collapses whitespace`() {
        assertEquals("خصم 250 ر.س", TextNormalizer.normalize("‏خصم   ٢٥٠‏  ر.س"))
    }

    @Test
    fun `currency symbols map to iso codes`() {
        assertEquals("USD", CurrencyNormalizer.normalize("$"))
        assertEquals("SAR", CurrencyNormalizer.normalize("ر.س"))
        assertEquals("AED", CurrencyNormalizer.normalize("د.إ"))
        assertEquals("EUR", CurrencyNormalizer.normalize("eur"))
        assertEquals(null, CurrencyNormalizer.normalize("...."))
    }
}
