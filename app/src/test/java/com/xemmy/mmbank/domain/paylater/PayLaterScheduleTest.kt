package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterCadence
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayLaterScheduleTest {

    private val utc = ZoneOffset.UTC
    private fun date(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(utc).toLocalDate()

    @Test
    fun `an even total splits equally`() {
        assertEquals(List(4) { "81.83" }, PayLaterSchedule.splitAmounts(BigDecimal("327.32"), 4).map { it.toPlainString() })
    }

    @Test
    fun `the rounding remainder lands on the last installment and the parts add up exactly`() {
        val parts = PayLaterSchedule.splitAmounts(BigDecimal("100.00"), 3)
        assertEquals(listOf("33.33", "33.33", "33.34"), parts.map { it.toPlainString() })
        assertEquals(0, BigDecimal("100.00").compareTo(parts.fold(BigDecimal.ZERO) { a, b -> a + b }))
    }

    @Test
    fun `a total with no cents is given cents`() {
        assertEquals(listOf("25.00", "25.00", "25.00", "25.00"), PayLaterSchedule.splitAmounts(BigDecimal("100"), 4).map { it.toPlainString() })
    }

    @Test
    fun `monthly dates step from the first date so a 31st returns after a short month`() {
        val first = LocalDate.of(2026, 1, 31)
        val dates = (0..3).map { PayLaterSchedule.dueDate(first, PayLaterCadence.MONTHLY, it) }
        assertEquals(
            listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)),
            dates,
        )
    }

    @Test
    fun `weekly and biweekly dates`() {
        val first = LocalDate.of(2026, 3, 1)
        assertEquals(LocalDate.of(2026, 3, 22), PayLaterSchedule.dueDate(first, PayLaterCadence.WEEKLY, 3))
        assertEquals(LocalDate.of(2026, 3, 29), PayLaterSchedule.dueDate(first, PayLaterCadence.BIWEEKLY, 2))
    }

    @Test
    fun `generate numbers the installments from one and stores due dates at the start of the day`() {
        val list = PayLaterSchedule.generate(BigDecimal("327.32"), 4, LocalDate.of(2026, 1, 15), PayLaterCadence.MONTHLY, utc)
        assertEquals(listOf(1, 2, 3, 4), list.map { it.number })
        assertEquals(LocalDate.of(2026, 4, 15), date(list.last().dueDateMillis))
        assertEquals(PayLaterSchedule.millisOf(LocalDate.of(2026, 1, 15), utc), list.first().dueDateMillis)
        assertTrue(list.all { it.planId == 0L && !it.isPaid })
    }

    @Test
    fun `the first installment can start out paid, stamped at its due date`() {
        val list = PayLaterSchedule.generate(BigDecimal("400"), 4, LocalDate.of(2026, 1, 15), PayLaterCadence.MONTHLY, utc, firstPaid = true)
        assertTrue(list.first().isPaid)
        assertEquals(list.first().dueDateMillis, list.first().paidAtMillis)
        assertNull(list.first().paymentTransactionId)
        assertFalse(list[1].isPaid)
    }
}
