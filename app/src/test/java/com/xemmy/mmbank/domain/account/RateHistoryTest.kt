package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.ExchangeRateOnDay
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateHistoryTest {

    private fun row(quote: String, day: String, rate: String, base: String = "AED") =
        ExchangeRateOnDay(base, quote, LocalDate.parse(day), BigDecimal(rate))

    private val d = LocalDate.parse("2026-11-12")

    // -- snapshot --------------------------------------------------------------

    @Test
    fun `snapshot keeps only held currencies that have a rate`() {
        val rates = mapOf("USD" to BigDecimal("0.2723"), "EUR" to BigDecimal("0.25"), "GBP" to BigDecimal("0.21"))
        val result = RateHistory.snapshot("AED", rates, listOf("usd", "EUR", "JPY"), d)
        assertEquals(listOf("USD", "EUR"), result.map { it.quoteCode })
        assertTrue(result.all { it.baseCode == "AED" && it.day == d })
    }

    @Test
    fun `snapshot skips the base currency, duplicates and non-positive rates`() {
        val rates = mapOf("USD" to BigDecimal("0.2723"), "EUR" to BigDecimal.ZERO)
        val result = RateHistory.snapshot("aed", rates, listOf("AED", "USD", "usd", "EUR", " "), d)
        assertEquals(listOf("USD"), result.map { it.quoteCode })
    }

    @Test
    fun `snapshot of nothing held is empty`() {
        assertTrue(RateHistory.snapshot("AED", mapOf("USD" to BigDecimal.ONE), emptyList(), d).isEmpty())
    }

    // -- rateOn ----------------------------------------------------------------

    private val history = listOf(
        row("USD", "2026-09-01", "0.270"),
        row("USD", "2026-10-01", "0.272"),
        row("USD", "2026-11-01", "0.274"),
        row("EUR", "2026-10-15", "0.250"),
    )

    @Test
    fun `an exact day returns that day's rate`() {
        assertEquals(BigDecimal("0.272"), RateHistory.rateOn(history, "USD", LocalDate.parse("2026-10-01")))
    }

    @Test
    fun `a day between recordings uses the most recent earlier one`() {
        assertEquals(BigDecimal("0.272"), RateHistory.rateOn(history, "USD", LocalDate.parse("2026-10-20")))
    }

    @Test
    fun `a day after the last recording holds the last rate`() {
        assertEquals(BigDecimal("0.274"), RateHistory.rateOn(history, "USD", LocalDate.parse("2027-01-01")))
    }

    @Test
    fun `a day before history began falls back to the earliest recording`() {
        assertEquals(BigDecimal("0.270"), RateHistory.rateOn(history, "usd", LocalDate.parse("2026-01-01")))
    }

    @Test
    fun `an unknown currency or an empty history has no rate`() {
        assertNull(RateHistory.rateOn(history, "JPY", d))
        assertNull(RateHistory.rateOn(emptyList(), "USD", d))
    }

    @Test
    fun `non-positive recordings are ignored`() {
        val bad = listOf(row("USD", "2026-10-01", "0"), row("USD", "2026-09-01", "0.270"))
        assertEquals(BigDecimal("0.270"), RateHistory.rateOn(bad, "USD", LocalDate.parse("2026-10-20")))
    }

    @Test
    fun `ratesOn builds a rate for every recorded quote`() {
        val result = RateHistory.ratesOn(history, LocalDate.parse("2026-10-20"))
        assertEquals(setOf("USD", "EUR"), result.keys)
        assertEquals(BigDecimal("0.272"), result.getValue("USD"))
        assertEquals(BigDecimal("0.250"), result.getValue("EUR"))
    }

    @Test
    fun `forBase drops recordings quoted against another base currency`() {
        val mixed = listOf(row("USD", "2026-10-01", "0.27"), row("USD", "2026-10-01", "3.67", base = "EUR"))
        assertEquals(listOf(BigDecimal("0.27")), RateHistory.forBase(mixed, "aed").map { it.rate })
    }

    @Test
    fun `overlay keeps today's rates for a null day or empty history and overrides per currency otherwise`() {
        val today = mapOf("USD" to BigDecimal("0.25"), "EUR" to BigDecimal("0.23"))
        assertEquals(today, RateHistory.overlay(today, history, null))
        assertEquals(today, RateHistory.overlay(today, emptyList(), LocalDate.parse("2026-10-20")))
        val past = RateHistory.overlay(today, history, LocalDate.parse("2026-10-20"))
        assertEquals(BigDecimal("0.272"), past.getValue("USD"))
        assertEquals(BigDecimal("0.250"), past.getValue("EUR"))
    }
}
