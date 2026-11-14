package com.xemmy.mmbank.ui.screens.review.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [highlightRanges] backs the raw SMS inspector's key-term highlighting
 * (amount, merchant, date/time tokens). Pure and Compose-free so it runs
 * under a plain JVM unit test.
 */
class TransactionDetailReviewCardTest {

    @Test
    fun `finds the amount and merchant wherever they occur, case-insensitively`() {
        val raw = "You spent AED 229.59 at CARREFOUR-MAF MKT on 12/07/2026."
        val ranges = highlightRanges(raw, amount = "229.59", merchant = "carrefour-maf mkt")

        assertTrue(ranges.any { raw.substring(it.first, it.last + 1).equals("229.59", ignoreCase = true) })
        assertTrue(ranges.any { raw.substring(it.first, it.last + 1).equals("CARREFOUR-MAF MKT", ignoreCase = true) })
    }

    @Test
    fun `finds a date-like token even without an amount or merchant match`() {
        val raw = "Payment received on 12/07/2026 at 10:45."
        val ranges = highlightRanges(raw, amount = null, merchant = null)

        assertTrue(ranges.any { raw.substring(it.first, it.last + 1) == "12/07/2026" })
        assertTrue(ranges.any { raw.substring(it.first, it.last + 1) == "10:45" })
    }

    @Test
    fun `blank raw text yields no ranges`() {
        assertEquals(emptyList<IntRange>(), highlightRanges("", amount = "10.00", merchant = "Shop"))
    }

    @Test
    fun `overlapping or adjacent matches are merged into one range`() {
        // "10:45" would match twice if searched independently for both terms;
        // merging keeps the highlighted span from double-rendering.
        val raw = "at 10:45"
        val ranges = highlightRanges(raw, amount = "10:45", merchant = null)
        assertEquals(1, ranges.size)
    }

    @Test
    fun `no terms and no date tokens yields no ranges`() {
        val raw = "Thank you for your business."
        assertEquals(emptyList<IntRange>(), highlightRanges(raw, amount = null, merchant = null))
    }
}
