package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionTotalsTest {
    private val utc = ZoneOffset.UTC
    private fun millis(d: LocalDate) = d.atTime(12, 0).atZone(utc).toInstant().toEpochMilli()

    private fun sub(
        amount: String, currency: String = "AED", cadence: SubscriptionCadence = SubscriptionCadence.MONTHLY,
        lines: Int = 1, dismissed: Boolean = false, due: LocalDate = LocalDate.of(2026, 5, 1),
    ) = Subscription(
        merchant = "S", averageAmount = BigDecimal(amount), currencyCode = currency, cadence = cadence,
        nextDueDateMillis = millis(due), lastSeenMillis = 0, occurrenceCount = 3, lines = lines, dismissed = dismissed,
    )

    @Test fun `monthly counts parallel lines`() {
        assertEquals(BigDecimal("100.00"), SubscriptionTotals.monthly(sub("50.00", lines = 2)))
    }

    @Test fun `a yearly charge is a twelfth per month`() {
        val m = SubscriptionTotals.monthly(sub("365.00", cadence = SubscriptionCadence.YEARLY))
        assertEquals(0, m.compareTo(BigDecimal("30.00")))
    }

    @Test fun `totals are per currency and skip dismissed rows`() {
        val totals = SubscriptionTotals.byCurrency(
            listOf(sub("10.00"), sub("20.00"), sub("5.00", currency = "USD"), sub("99.00", dismissed = true)),
        )
        assertEquals(listOf("AED", "USD"), totals.map { it.currency })
        assertEquals(BigDecimal("30.00"), totals[0].perMonth)
        assertEquals(BigDecimal("360.00"), totals[0].perYear)
    }

    @Test fun `summary lines list the count then one line per currency`() {
        val lines = SubscriptionTotals.summaryLines(listOf(sub("10.00"), sub("5.00", currency = "USD"))) { a, c -> "$c ${a.toPlainString()}" }
        assertEquals("2 active", lines[0])
        assertEquals(3, lines.size)
        assertEquals(emptyList<String>(), SubscriptionTotals.summaryLines(emptyList()) { a, c -> "$c $a" })
    }

    @Test fun `groups split overdue, this week and later, soonest first`() {
        val today = LocalDate.of(2026, 4, 15)
        val overdue = sub("1", due = LocalDate.of(2026, 4, 10))
        val todayDue = sub("2", due = today)
        val week = sub("3", due = LocalDate.of(2026, 4, 22))
        val later = sub("4", due = LocalDate.of(2026, 4, 23))
        val g = SubscriptionGroups.group(listOf(later, week, overdue, todayDue), today, utc)
        assertEquals(listOf(overdue), g.overdue)
        assertEquals(listOf(todayDue, week), g.thisWeek)
        assertEquals(listOf(later), g.later)
    }
}
