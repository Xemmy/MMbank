package com.xemmy.mmbank.ui.screens.plan

import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator
import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator.BudgetState
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.model.SavingsGoal
import com.xemmy.mmbank.domain.paylater.PayLaterSummary
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanStatusTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L
    private val money: (BigDecimal, String) -> String = { v, c -> "$c ${v.toPlainString()}" }

    private fun status(state: BudgetState) = CategoryBudgetCalculator.CategoryBudgetStatus(
        category = Category(name = "C", icon = "", colorHex = "#000000", monthlyCap = BigDecimal("100")),
        spent = BigDecimal.ZERO, cap = BigDecimal("100"), fraction = 0.0, state = state,
    )

    private fun bill(name: String, dueInDays: Long) = Bill(
        name = name, amount = BigDecimal("50"), currencyCode = "AED", nextDueDateMillis = now + dueInDays * day + 1000, createdAt = 0,
    )

    private fun sub(amount: String, cadence: SubscriptionCadence, currency: String = "AED") = Subscription(
        merchant = "M", averageAmount = BigDecimal(amount), currencyCode = currency, cadence = cadence,
        nextDueDateMillis = 0, lastSeenMillis = 0, occurrenceCount = 3,
    )

    private fun goal(title: String, saved: String, target: String = "100", archived: Boolean = false) = SavingsGoal(
        title = title, targetAmount = BigDecimal(target), currentAmount = BigDecimal(saved), archived = archived,
    )

    @Test
    fun `budgets say what is over or near, or that all are on track`() {
        assertNull(PlanStatus.budgets(emptyList()))
        assertEquals("All 2 on track", PlanStatus.budgets(listOf(status(BudgetState.UNDER), status(BudgetState.UNDER))))
        assertEquals("1 near their cap", PlanStatus.budgets(listOf(status(BudgetState.NEAR), status(BudgetState.UNDER))))
        assertEquals("2 over budget", PlanStatus.budgets(listOf(status(BudgetState.OVER), status(BudgetState.OVER))))
        assertEquals("1 over budget · 1 near their cap", PlanStatus.budgets(listOf(status(BudgetState.OVER), status(BudgetState.NEAR))))
    }

    @Test
    fun `bills count overdue and due this week, else name the next one`() {
        assertNull(PlanStatus.bills(emptyList(), now))
        assertEquals("1 overdue", PlanStatus.bills(listOf(bill("Rent", -3), bill("Gym", 30)), now))
        assertEquals("1 due this week", PlanStatus.bills(listOf(bill("Rent", 5), bill("Gym", 30)), now))
        assertEquals("1 overdue · 2 due this week", PlanStatus.bills(listOf(bill("A", -1), bill("B", 0), bill("C", 7)), now))
        assertEquals("Next: Gym in 12 days", PlanStatus.bills(listOf(bill("Rent", 40), bill("Gym", 12)), now))
    }

    @Test
    fun `a goal just short of its target never reads as 100 percent`() {
        assertEquals("1 goal · closest: Car 99%", PlanStatus.goals(listOf(goal("Car", "99.6"))))
    }

    @Test
    fun `subscriptions show the monthly total only when they share a currency`() {
        assertNull(PlanStatus.subscriptions(emptyList(), money))
        // 30.00 monthly + 7.00 weekly (= 30.00 per 30 days) = 60.00
        assertEquals(
            "2 active · AED 60.00/month",
            PlanStatus.subscriptions(listOf(sub("30", SubscriptionCadence.MONTHLY), sub("7", SubscriptionCadence.WEEKLY)), money),
        )
        assertEquals(
            "2 active",
            PlanStatus.subscriptions(listOf(sub("30", SubscriptionCadence.MONTHLY), sub("10", SubscriptionCadence.MONTHLY, "USD")), money),
        )
    }

    @Test
    fun `a yearly subscription is divided down to a monthly figure`() {
        // 365.00 a year = 30.00 per 30 days
        assertEquals("1 active · AED 30.00/month", PlanStatus.subscriptions(listOf(sub("365", SubscriptionCadence.YEARLY)), money))
    }

    @Test
    fun `dismissed subscriptions do not count`() {
        assertNull(PlanStatus.subscriptions(listOf(sub("30", SubscriptionCadence.MONTHLY).copy(dismissed = true)), money))
    }

    @Test
    fun `goals name the closest unfinished one, ignore archived, and celebrate when done`() {
        assertNull(PlanStatus.goals(emptyList()))
        assertNull(PlanStatus.goals(listOf(goal("Old", "10", archived = true))))
        assertEquals("2 goals · closest: Trip 80%", PlanStatus.goals(listOf(goal("Car", "10"), goal("Trip", "80"))))
        assertEquals("1 goal · closest: Car 10%", PlanStatus.goals(listOf(goal("Car", "10"))))
        assertEquals("All 2 reached", PlanStatus.goals(listOf(goal("A", "100"), goal("B", "150"))))
    }

    @Test
    fun `forEntries only includes cards that have something to say`() {
        val map = PlanStatus.forEntries(emptyList(), listOf(bill("Rent", 3)), emptyList(), emptyList(), now, money)
        assertEquals(setOf(PlanEntry.BILLS), map.keys)
        assertTrue(map.getValue(PlanEntry.BILLS).contains("due this week"))
    }

    private fun payLaterRow(inDays: Long, amount: String = "81.83") = PayLaterSummary.Row(
        plan = PayLaterPlan(id = 1, merchant = "Carrefour", totalAmount = BigDecimal("327.32"), currencyCode = "AED", installmentCount = 4, createdAt = 0),
        installment = PayLaterInstallment(planId = 1, number = 1, amount = BigDecimal(amount), dueDateMillis = 0),
        dueDate = java.time.LocalDate.of(2026, 1, 1).plusDays(inDays),
        daysUntil = inDays,
    )

    @Test
    fun `payLater says what is due next, or how many are overdue`() {
        assertNull(PlanStatus.payLater(emptyList(), money))
        assertEquals("AED 81.83 due in 9 days", PlanStatus.payLater(listOf(payLaterRow(9)), money))
        assertEquals("AED 81.83 due today", PlanStatus.payLater(listOf(payLaterRow(0)), money))
        assertEquals("AED 81.83 due tomorrow", PlanStatus.payLater(listOf(payLaterRow(1)), money))
        assertEquals("2 overdue", PlanStatus.payLater(listOf(payLaterRow(-3), payLaterRow(-1), payLaterRow(5)), money))
    }

    @Test
    fun `forEntries includes the Pay Later card only when something is owed`() {
        val with = PlanStatus.forEntries(emptyList(), emptyList(), emptyList(), emptyList(), now, money, listOf(payLaterRow(9)))
        assertEquals(setOf(PlanEntry.PAY_LATER), with.keys)
        val without = PlanStatus.forEntries(emptyList(), emptyList(), emptyList(), emptyList(), now, money)
        assertTrue(without.isEmpty())
    }
}
