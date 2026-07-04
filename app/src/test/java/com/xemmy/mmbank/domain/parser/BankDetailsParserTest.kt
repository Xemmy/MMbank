package com.xemmy.mmbank.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankDetailsParserTest {

    @Test
    fun `parses ADCB format`() {
        val r = BankDetailsParser.parse(
            """
            Bank Name: Abu Dhabi Commercial Bank PJSC
            Account Title: AHMED SAEED SULAIMAN B ALMAKHZOUMI
            Account Number: 14362927810001
            IBAN: AE060030014362927810001
            Currency: AED
            SWIFT code: ADCBAEAA
            """.trimIndent()
        )
        assertEquals("Abu Dhabi Commercial Bank PJSC", r.bankName)
        assertEquals("AHMED SAEED SULAIMAN B ALMAKHZOUMI", r.accountTitle)
        assertEquals("14362927810001", r.accountNumber)
        assertEquals("AE060030014362927810001", r.iban)
        assertEquals("AED", r.currencyCode)
        assertEquals("ADCBAEAA", r.swift)
    }

    @Test
    fun `parses ADIB format with different labels`() {
        val r = BankDetailsParser.parse(
            """
            Bank Name: ADIB
            Account Holder Name: AHMED SAEED SULAIMAN BINZAYED ALMAKHZOUMI
            Account Number: 17525963
            IBAN: AE900500000000017525963
            SWIFT: ABDIAEADXXX
            Currency: AED
            """.trimIndent()
        )
        assertEquals("ADIB", r.bankName)
        assertEquals("AHMED SAEED SULAIMAN BINZAYED ALMAKHZOUMI", r.accountTitle)
        assertEquals("17525963", r.accountNumber)
        assertEquals("AE900500000000017525963", r.iban)
        assertEquals("ABDIAEADXXX", r.swift)
        assertEquals("AED", r.currencyCode)
    }

    @Test
    fun `recovers iban and swift without labels`() {
        val r = BankDetailsParser.parse(
            "My ADCB account, IBAN is AE060030014362927810001 and the bic ADCBAEAA, in AED."
        )
        assertEquals("AE060030014362927810001", r.iban)
        assertEquals("ADCBAEAA", r.swift)
        assertEquals("AED", r.currencyCode)
    }

    @Test
    fun `empty text yields nothing`() {
        val r = BankDetailsParser.parse("   \n  \n")
        assertNull(r.bankName)
        assertNull(r.iban)
        assertTrue(!r.hasAnything)
    }
}
