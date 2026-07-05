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
            Account Title: MOHAMED KHALIFA RASHED ALSHAMSI
            Account Number: 13579246810001
            IBAN: AE210030013579246810001
            Currency: AED
            SWIFT code: ADCBAEAA
            """.trimIndent()
        )
        assertEquals("Abu Dhabi Commercial Bank PJSC", r.bankName)
        assertEquals("MOHAMED KHALIFA RASHED ALSHAMSI", r.accountTitle)
        assertEquals("13579246810001", r.accountNumber)
        assertEquals("AE210030013579246810001", r.iban)
        assertEquals("AED", r.currencyCode)
        assertEquals("ADCBAEAA", r.swift)
    }

    @Test
    fun `parses ADIB format with different labels`() {
        val r = BankDetailsParser.parse(
            """
            Bank Name: ADIB
            Account Holder Name: FATIMA SALEM JUMA ALSUWAIDI
            Account Number: 24681357
            IBAN: AE350500000000024681357
            SWIFT: ABDIAEADXXX
            Currency: AED
            """.trimIndent()
        )
        assertEquals("ADIB", r.bankName)
        assertEquals("FATIMA SALEM JUMA ALSUWAIDI", r.accountTitle)
        assertEquals("24681357", r.accountNumber)
        assertEquals("AE350500000000024681357", r.iban)
        assertEquals("ABDIAEADXXX", r.swift)
        assertEquals("AED", r.currencyCode)
    }

    @Test
    fun `recovers iban and swift without labels`() {
        val r = BankDetailsParser.parse(
            "My ADCB account, IBAN is AE210030013579246810001 and the bic ADCBAEAA, in AED."
        )
        assertEquals("AE210030013579246810001", r.iban)
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
