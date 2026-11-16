package com.xemmy.mmbank.domain.bills

import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.BillRecurrence
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepeatPlannerTest {
    private val zone = ZoneOffset.UTC

    private fun plan(repeat: Repeat, date: LocalDate, name: String? = "Rent", amount: String = "3000") =
        RepeatPlanner.billFor(repeat, name, "Payment", BigDecimal(amount), "aed", date, zone, 1L)

    private fun due(b: Bill): LocalDate = Instant.ofEpochMilli(b.nextDueDateMillis).atZone(zone).toLocalDate()

    @Test
    fun `never and a non-positive amount make no reminder`() {
        assertNull(plan(Repeat.NEVER, LocalDate.of(2026, 3, 1)))
        assertNull(plan(Repeat.MONTHLY, LocalDate.of(2026, 3, 1), amount = "0"))
    }

    @Test
    fun `the first reminder is due one cycle after the logged payment`() {
        assertEquals(LocalDate.of(2026, 3, 8), due(plan(Repeat.WEEKLY, LocalDate.of(2026, 3, 1))!!))
        assertEquals(LocalDate.of(2026, 4, 1), due(plan(Repeat.MONTHLY, LocalDate.of(2026, 3, 1))!!))
        assertEquals(LocalDate.of(2027, 3, 1), due(plan(Repeat.YEARLY, LocalDate.of(2026, 3, 1))!!))
    }

    @Test
    fun `a month-end payment keeps its day through a short month`() {
        val b = plan(Repeat.MONTHLY, LocalDate.of(2026, 1, 31))!!
        assertEquals(LocalDate.of(2026, 2, 28), due(b))
        assertEquals(31, b.dueDayAnchor)
        assertEquals(LocalDate.of(2026, 3, 31), due(com.xemmy.mmbank.domain.intelligence.BillAdvancer.advance(b, zone)!!))
    }

    @Test
    fun `the reminder carries the name, amount, upper-cased currency and recurrence`() {
        val b = plan(Repeat.MONTHLY, LocalDate.of(2026, 3, 1))!!
        assertEquals("Rent", b.name)
        assertEquals(0, BigDecimal("3000").compareTo(b.amount))
        assertEquals("AED", b.currencyCode)
        assertEquals(BillRecurrence.MONTHLY, b.recurrence)
    }

    @Test
    fun `a blank name falls back and weekly bills have no day anchor`() {
        assertEquals("Payment", plan(Repeat.WEEKLY, LocalDate.of(2026, 3, 1), name = "  ")!!.name)
        assertNull(plan(Repeat.WEEKLY, LocalDate.of(2026, 3, 1))!!.dueDayAnchor)
    }

    @Test
    fun `prefill takes the bill's amount, currency and name`() {
        val bill = Bill(name = "Gym", amount = BigDecimal("150"), currencyCode = "AED", nextDueDateMillis = 0, createdAt = 0)
        val p = RepeatPlanner.prefillFor(bill)
        assertEquals("Gym", p.merchant)
        assertEquals("AED", p.currency)
        assertEquals(0, BigDecimal("150").compareTo(p.amount))
    }

    @Test
    fun `stored value falls back to never`() {
        assertEquals(Repeat.YEARLY, Repeat.fromStored("YEARLY"))
        assertEquals(Repeat.NEVER, Repeat.fromStored("x"))
        assertEquals(Repeat.NEVER, Repeat.fromStored(null))
    }
}
