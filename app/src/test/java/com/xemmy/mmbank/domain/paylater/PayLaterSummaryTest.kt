package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayLaterSummaryTest {

    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 12)

    private fun plan(id: Long, merchant: String, currency: String = "AED", count: Int = 4) =
        PayLaterPlan(id = id, merchant = merchant, totalAmount = BigDecimal("400"), currencyCode = currency, installmentCount = count, createdAt = 0)

    private fun inst(id: Long, planId: Long, number: Int, inDays: Long, amount: String = "100", paid: Boolean = false) =
        PayLaterInstallment(
            id = id, planId = planId, number = number, amount = BigDecimal(amount),
            dueDateMillis = PayLaterSchedule.millisOf(today.plusDays(inDays), utc), paidAtMillis = if (paid) 1L else null,
        )

    @Test
    fun `unpaid rows are ordered by date and carry the payment label`() {
        val plans = listOf(plan(1, "Carrefour"), plan(2, "Noon", count = 3))
        val rows = PayLaterSummary.unpaid(
            plans, listOf(inst(1, 1, 2, 30), inst(2, 2, 1, 9), inst(3, 1, 1, 5, paid = true)), today, utc,
        )
        assertEquals(listOf("Noon", "Carrefour"), rows.map { it.plan.merchant })
        assertEquals("Payment 1 of 3", rows[0].label)
        assertEquals("Payment 2 of 4", rows[1].label)
        assertEquals(listOf(9L, 30L), rows.map { it.daysUntil })
    }

    @Test
    fun `an installment whose plan is gone is ignored`() {
        assertTrue(PayLaterSummary.unpaid(emptyList(), listOf(inst(1, 9, 1, 3)), today, utc).isEmpty())
    }

    @Test
    fun `next due is the earliest date and sums what falls on it in that currency`() {
        // Same-day rows order by merchant, so the USD plan sorts last and the first row decides the currency.
        val plans = listOf(plan(1, "Carrefour"), plan(2, "Noon"), plan(3, "Zara", currency = "USD"))
        val rows = PayLaterSummary.unpaid(
            plans,
            listOf(inst(1, 1, 1, 9, "81.83"), inst(2, 2, 1, 9, "40.00"), inst(3, 3, 1, 9, "10"), inst(4, 1, 2, 40, "81.83")),
            today, utc,
        )
        val next = PayLaterSummary.nextDue(rows)!!
        assertEquals(9L, next.daysUntil)
        assertEquals("AED", next.currencyCode)
        assertEquals(0, BigDecimal("121.83").compareTo(next.amount))
    }

    @Test
    fun `nothing owed means no next due`() {
        assertNull(PayLaterSummary.nextDue(emptyList()))
    }

    @Test
    fun `totals are kept per currency and never mixed`() {
        val plans = listOf(plan(1, "Carrefour"), plan(2, "Amazon", currency = "usd"))
        val rows = PayLaterSummary.unpaid(plans, listOf(inst(1, 1, 1, 9, "81.83"), inst(2, 1, 2, 40, "81.83"), inst(3, 2, 1, 9, "20")), today, utc)
        val totals = PayLaterSummary.totalsByCurrency(rows)
        assertEquals(setOf("AED", "USD"), totals.keys)
        assertEquals(0, BigDecimal("163.66").compareTo(totals.getValue("AED")))
        assertEquals(0, BigDecimal("20").compareTo(totals.getValue("USD")))
    }

    @Test
    fun `rows group by due date in date order`() {
        val plans = listOf(plan(1, "Carrefour"), plan(2, "Noon"))
        val rows = PayLaterSummary.unpaid(plans, listOf(inst(1, 1, 1, 9), inst(2, 2, 1, 9), inst(3, 1, 2, 3)), today, utc)
        val groups = PayLaterSummary.groupByDate(rows)
        assertEquals(listOf(today.plusDays(3), today.plusDays(9)), groups.map { it.first })
        assertEquals(listOf(1, 2), groups.map { it.second.size })
    }

    @Test
    fun `overdue rows are those due before today and are counted`() {
        val rows = PayLaterSummary.unpaid(listOf(plan(1, "Carrefour")), listOf(inst(1, 1, 1, -3), inst(2, 1, 2, 0), inst(3, 1, 3, 20)), today, utc)
        assertEquals(1, PayLaterSummary.overdue(rows).size)
        assertEquals(-3L, rows.first().daysUntil)
    }

    @Test
    fun `history lists paid rows most recent first and a plan is finished only when all are paid`() {
        val plans = listOf(plan(1, "Carrefour"))
        val installments = listOf(inst(1, 1, 1, -30, paid = true), inst(2, 1, 2, -2, paid = true), inst(3, 1, 3, 20))
        val history = PayLaterSummary.history(plans, installments, today, utc)
        assertEquals(listOf(2, 1), history.map { it.installment.number })
        assertFalse(PayLaterSummary.isFinished(installments))
        assertTrue(PayLaterSummary.isFinished(installments.map { it.copy(paidAtMillis = 1L) }))
        assertFalse(PayLaterSummary.isFinished(emptyList()))
    }

    @Test
    fun `due labels`() {
        assertEquals("Due in 9 days", PayLaterSummary.dueLabel(9))
        assertEquals("Due tomorrow", PayLaterSummary.dueLabel(1))
        assertEquals("Due today", PayLaterSummary.dueLabel(0))
        assertEquals("Overdue by 1 day", PayLaterSummary.dueLabel(-1))
        assertEquals("Overdue by 3 days", PayLaterSummary.dueLabel(-3))
    }
}
