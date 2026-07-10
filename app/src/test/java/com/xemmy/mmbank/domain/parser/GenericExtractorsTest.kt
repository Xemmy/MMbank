package com.xemmy.mmbank.domain.parser

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GenericExtractorsTest {

    // --- Amount & currency ---

    @Test
    fun `spaced currency-first form`() {
        val r = GenericExtractors.extractAmount("Your transaction of AED 46.12 was approved")
        assertNotNull(r)
        assertEquals(BigDecimal("46.12"), r!!.amount)
        assertEquals("AED", r.currencyCode)
    }

    @Test
    fun `non-spaced currency-first form`() {
        val r = GenericExtractors.extractAmount("Transaction of AED229.59 At Sun & Sand Sports")
        assertNotNull(r)
        assertEquals(BigDecimal("229.59"), r!!.amount)
        assertEquals("AED", r.currencyCode)
    }

    @Test
    fun `trailing currency form`() {
        val r = GenericExtractors.extractAmount("Salary of 12,500.00 SAR received")
        assertNotNull(r)
        assertEquals(BigDecimal("12500.00"), r!!.amount)
        assertEquals("SAR", r.currencyCode)
    }

    @Test
    fun `thousands separators`() {
        val r = GenericExtractors.extractAmount("Charged AED 1,234.56 today")
        assertNotNull(r)
        assertEquals(BigDecimal("1234.56"), r!!.amount)
    }

    @Test
    fun `currency symbol maps to iso code`() {
        val r = GenericExtractors.extractAmount("Payment of $25.00 processed")
        assertNotNull(r)
        assertEquals(BigDecimal("25.00"), r!!.amount)
        assertEquals("USD", r.currencyCode)
    }

    @Test
    fun `first plausible match wins over later amounts`() {
        val r = GenericExtractors.extractAmount(
            "Transaction of AED229.59 At X was successful. Your available Tabby Card limit is AED 6446.53."
        )
        assertNotNull(r)
        assertEquals(BigDecimal("229.59"), r!!.amount)
    }

    @Test
    fun `more than two decimals is not plausible`() {
        assertNull(GenericExtractors.extractAmount("Rate applied AED 46.123 per unit"))
    }

    @Test
    fun `bare number without adjacent currency is not plausible`() {
        assertNull(GenericExtractors.extractAmount("you were debited 99.90 for subscription renewal"))
    }

    @Test
    fun `zero amount is skipped in favor of a later positive one`() {
        val r = GenericExtractors.extractAmount("Fee AED 0.00 waived, total charged AED 12.50")
        assertNotNull(r)
        assertEquals(BigDecimal("12.50"), r!!.amount)
    }

    @Test
    fun `zero amount alone yields nothing`() {
        assertNull(GenericExtractors.extractAmount("Fee AED 0.00 waived"))
    }

    @Test
    fun `arabic currency abbreviation normalizes`() {
        val r = GenericExtractors.extractAmount("تم خصم 250.75 ر.س من بطاقتك")
        assertNotNull(r)
        assertEquals(BigDecimal("250.75"), r!!.amount)
        assertEquals("SAR", r.currencyCode)
    }

    @Test
    fun `random words are never a currency`() {
        assertNull(GenericExtractors.extractAmount("Meeting room 46.12 booked for you"))
    }

    // --- Merchant ---

    @Test
    fun `at clause stops before status verb`() {
        assertEquals(
            "Tips and Toes",
            GenericExtractors.extractMerchant("Transaction of AED 20.00 At Tips and Toes was successful.")
        )
    }

    @Test
    fun `at clause stops at comma`() {
        assertEquals(
            "NOON MINUTES",
            GenericExtractors.extractMerchant("You spent AED 12.00 at NOON MINUTES, Dubai")
        )
    }

    @Test
    fun `to clause stops before trailing date`() {
        assertEquals(
            "JOHN DOE",
            GenericExtractors.extractMerchant("Transfer of AED 500.00 to JOHN DOE on 12/06/2026")
        )
    }

    @Test
    fun `at clause stops before reference code`() {
        assertEquals(
            "CITY STORE",
            GenericExtractors.extractMerchant("Paid AED 30.00 at CITY STORE ref 88213")
        )
    }

    @Test
    fun `falls back to vendor header label`() {
        assertEquals(
            "Binance Mastercard",
            GenericExtractors.extractMerchant("Binance Mastercard: service notice")
        )
    }

    @Test
    fun `header with digits is not a vendor label`() {
        assertNull(GenericExtractors.extractMerchant("Ref 12345: payment received"))
    }

    @Test
    fun `no clause and no header yields null`() {
        assertNull(GenericExtractors.extractMerchant("Statement ready for download"))
    }

    @Test
    fun `notification title is the merchant when no clause exists`() {
        assertEquals(
            "CARREFOUR-MAF MKT YAS",
            GenericExtractors.extractMerchant(
                "CARREFOUR-MAF MKT YAS AED229.59 with Binance Mastercard ••2957",
                title = "CARREFOUR-MAF MKT YAS",
            )
        )
    }

    @Test
    fun `title with digits is not a vendor label`() {
        assertNull(
            GenericExtractors.extractMerchant(
                "AED 75.50 charged with your card",
                title = "Transaction of AED 75.50",
            )
        )
    }

    @Test
    fun `at clause wins over the title`() {
        assertEquals(
            "Tesla Motors Netherlan",
            GenericExtractors.extractMerchant(
                "Transaction of AED 46.12 At Tesla Motors Netherlan was successful. " +
                    "Your available Tabby Card limit is AED 5125.48.",
                title = "Tabby",
            )
        )
    }

    // --- Account/card suffix ---

    @Test
    fun `bullet card suffix is extracted`() {
        assertEquals(
            "2957",
            GenericExtractors.extractAccountSuffix("AED229.59 with Binance Mastercard ••2957")
        )
    }

    @Test
    fun `star account suffix is extracted`() {
        assertEquals("617", GenericExtractors.extractAccountSuffix("debited from your account *617."))
    }

    @Test
    fun `no marker means no suffix`() {
        assertNull(GenericExtractors.extractAccountSuffix("Card limit is AED 5125.48."))
    }
}
