package com.xemmy.mmbank.domain.filter

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityDrillDownTest {

    private val oct1 = LocalDate.of(2026, 10, 1)
    private val oct31 = LocalDate.of(2026, 10, 31)

    @Test
    fun `round trip`() {
        val d = ActivityDrillDown(categoryIds = setOf(13, 12), from = oct1, to = oct31, query = "careem")
        assertEquals("cat=12,13;from=2026-10-01;to=2026-10-31;q=careem", d.encode())
        assertEquals(d, ActivityDrillDown.parse(d.encode()))
    }

    @Test
    fun `special characters in the query survive`() {
        val d = ActivityDrillDown(query = "Café & Co; a=b 100%")
        assertEquals(d, ActivityDrillDown.parse(d.encode()))
    }

    @Test
    fun `malformed parts are skipped`() {
        assertNull(ActivityDrillDown.parse("cat=abc;from=nope;junk;=x"))
        assertNull(ActivityDrillDown.parse(""))
        assertNull(ActivityDrillDown.parse(null))
        assertEquals(setOf(5L), ActivityDrillDown.parse("cat=5,x;from=bad")!!.categoryIds)
    }

    @Test
    fun `reversed dates are swapped`() {
        val d = ActivityDrillDown.parse("from=2026-10-31;to=2026-10-01")!!
        assertEquals(oct1, d.from)
        assertEquals(oct31, d.to)
    }

    @Test
    fun `applies to fresh filters`() {
        val f = ActivityDrillDown(categoryIds = setOf(2), from = oct1, to = oct31, query = "zuma").applyTo()
        assertEquals(DateRangeFilter.Custom(oct1, oct31), f.dateRange)
        assertEquals("zuma", f.query)
        assertEquals(setOf(2L), f.categoryIds)
    }

    @Test
    fun `missing parts keep the base filters`() {
        val base = ActivityFilters(dateRange = DateRangeFilter.Past7Days, query = "x")
        val f = ActivityDrillDown(categoryIds = setOf(2)).applyTo(base)
        assertEquals(DateRangeFilter.Past7Days, f.dateRange)
        assertEquals("x", f.query)
        assertEquals(setOf(2L), f.categoryIds)
    }
}
