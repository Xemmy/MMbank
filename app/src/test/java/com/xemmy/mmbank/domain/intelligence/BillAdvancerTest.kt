package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.BillRecurrence
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillAdvancerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dubai")

    private fun bill(recurrence: BillRecurrence?, due: LocalDateTime) = Bill(
        id = 1,
        name = "Internet",
        amount = BigDecimal("299"),
        currencyCode = "AED",
        recurrence = recurrence,
        nextDueDateMillis = due.atZone(zone).toInstant().toEpochMilli(),
        createdAt = 0L,
    )

    private fun advancedDate(recurrence: BillRecurrence, due: LocalDateTime): LocalDateTime =
        java.time.Instant.ofEpochMilli(BillAdvancer.advance(bill(recurrence, due), zone)!!.nextDueDateMillis)
            .atZone(zone)
            .toLocalDateTime()

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 30) =
        LocalDateTime.of(year, month, day, hour, minute)

    @Test
    fun `a monthly bill keeps its day of month instead of drifting by 30 days`() {
        assertEquals(at(2026, 2, 1), advancedDate(BillRecurrence.MONTHLY, at(2026, 1, 1)))
    }

    @Test
    fun `a monthly bill on the first stays on the first for a full year`() {
        // The 30-day step this replaced landed on Jan 31, Mar 2, Apr 1, ...
        // walking backwards through the month; a calendar step never moves.
        var due = at(2026, 1, 1)
        repeat(12) {
            due = advancedDate(BillRecurrence.MONTHLY, due)
            assertEquals(1, due.dayOfMonth)
        }
        assertEquals(at(2027, 1, 1), due)
    }

    @Test
    fun `a bill due on a month's last day advances to the next month's last day`() {
        assertEquals(at(2026, 2, 28), advancedDate(BillRecurrence.MONTHLY, at(2026, 1, 31)))
    }

    @Test
    fun `a month-end bill recovers its day after a short month instead of sticking at 28`() {
        val february = advancedDate(BillRecurrence.MONTHLY, at(2026, 1, 31))
        val march = advancedDate(BillRecurrence.MONTHLY, february)
        val april = advancedDate(BillRecurrence.MONTHLY, march)
        assertEquals(at(2026, 2, 28), february)
        assertEquals(at(2026, 3, 31), march)
        assertEquals(at(2026, 4, 30), april)
    }

    @Test
    fun `a mid-month bill is never snapped to month end`() {
        assertEquals(at(2026, 2, 15), advancedDate(BillRecurrence.MONTHLY, at(2026, 1, 15)))
    }

    @Test
    fun `a weekly bill advances seven calendar days and never snaps to month end`() {
        assertEquals(at(2026, 2, 7), advancedDate(BillRecurrence.WEEKLY, at(2026, 1, 31)))
    }

    @Test
    fun `a yearly bill keeps its calendar date across a leap year`() {
        // 2028 is a leap year, so a flat 365-day step from Mar 1 2027 landed on
        // Feb 29 2028 -- a day early, and every later year drifts further.
        assertEquals(at(2028, 3, 1), advancedDate(BillRecurrence.YEARLY, at(2027, 3, 1)))
        assertEquals(at(2027, 3, 1), advancedDate(BillRecurrence.YEARLY, at(2026, 3, 1)))
    }

    @Test
    fun `a yearly bill on Feb 29 clamps to Feb 28 in a common year`() {
        assertEquals(at(2029, 2, 28), advancedDate(BillRecurrence.YEARLY, at(2028, 2, 29)))
    }

    @Test
    fun `the due date keeps its time of day`() {
        val advanced = advancedDate(BillRecurrence.MONTHLY, at(2026, 1, 10, hour = 23, minute = 45))
        assertEquals(23, advanced.hour)
        assertEquals(45, advanced.minute)
    }

    @Test
    fun `a due date crossing a DST boundary keeps its local time`() {
        // Europe/London moves to BST on 2026-03-29; a fixed-millis step would
        // shift a 09:30 bill to 10:30.
        val london = ZoneId.of("Europe/London")
        val due = LocalDateTime.of(2026, 3, 10, 9, 30)
        val advanced = BillAdvancer.advance(
            Bill(
                id = 1,
                name = "Rent",
                amount = BigDecimal("1200"),
                currencyCode = "GBP",
                recurrence = BillRecurrence.MONTHLY,
                nextDueDateMillis = due.atZone(london).toInstant().toEpochMilli(),
                createdAt = 0L,
            ),
            london,
        )!!
        val result = java.time.Instant.ofEpochMilli(advanced.nextDueDateMillis).atZone(london).toLocalDateTime()
        assertEquals(LocalDateTime.of(2026, 4, 10, 9, 30), result)
    }

    @Test
    fun `advancing from an overdue due date still steps one cycle, leaving it overdue`() {
        // Marking a long-overdue bill paid must not silently jump it to the
        // future -- BillReminders is meant to flag it again on the next check.
        val advanced = advancedDate(BillRecurrence.MONTHLY, at(2020, 5, 3))
        assertEquals(at(2020, 6, 3), advanced)
    }

    @Test
    fun `a one-off bill has nothing to advance to`() {
        assertNull(BillAdvancer.advance(bill(recurrence = null, due = at(2026, 1, 1)), zone))
    }

    @Test
    fun `every recurrence carries a calendar period rather than a nominal day count`() {
        assertEquals(7, BillRecurrence.WEEKLY.period.days)
        assertEquals(1L, BillRecurrence.MONTHLY.period.toTotalMonths())
        assertEquals(12L, BillRecurrence.YEARLY.period.toTotalMonths())
        // The month-end snap keys off this: only month-based cycles may snap.
        assertEquals(0L, BillRecurrence.WEEKLY.period.toTotalMonths())
    }

    @Test
    fun `an anchored 31st bill returns to the 31st after February`() {
        val feb = bill(BillRecurrence.MONTHLY, at(2026, 2, 28)).copy(dueDayAnchor = 31)
        val mar = BillAdvancer.advance(feb, zone)!!
        assertEquals(
            at(2026, 3, 31),
            java.time.Instant.ofEpochMilli(mar.nextDueDateMillis).atZone(zone).toLocalDateTime(),
        )
    }

    @Test
    fun `an anchored 30th bill clamps in February then recovers`() {
        val jan = bill(BillRecurrence.MONTHLY, at(2026, 1, 30)).copy(dueDayAnchor = 30)
        val feb = BillAdvancer.advance(jan, zone)!!
        val mar = BillAdvancer.advance(feb, zone)!!
        fun d(b: Bill) = java.time.Instant.ofEpochMilli(b.nextDueDateMillis).atZone(zone).toLocalDateTime()
        assertEquals(at(2026, 2, 28), d(feb))
        assertEquals(at(2026, 3, 30), d(mar))
    }
}
