package com.xemmy.mmbank.domain.budget

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyBudgetPacingTest {

    @Test
    fun `dailyAllowance splits the remaining budget evenly across remaining days`() {
        val allowance = DailyBudgetPacing.dailyAllowance(
            monthlyBudget = BigDecimal("3000"),
            fixedExpenses = BigDecimal("500"),
            spentSoFar = BigDecimal("1000"),
            remainingDaysInMonth = 10,
        )
        // spent (1000) already covers the 500 fixed: (3000 - 1000) / 10 = 200
        assertEquals(BigDecimal("200.00"), allowance)
    }

    @Test
    fun `dailyAllowance can go negative once spend has outpaced budget`() {
        val allowance = DailyBudgetPacing.dailyAllowance(
            monthlyBudget = BigDecimal("1000"),
            fixedExpenses = BigDecimal("200"),
            spentSoFar = BigDecimal("1500"),
            remainingDaysInMonth = 5,
        )
        // (1000 - 1500) / 5 = -100
        assertEquals(BigDecimal("-100.00"), allowance)
    }

    @Test
    fun `fixed expenses are not counted twice once they have been paid`() {
        // 3100 budget, 1000 rent. Before rent: (3100 - 1000) / 10 = 210.
        val before = DailyBudgetPacing.dailyAllowance(
            BigDecimal("3100"), BigDecimal("1000"), BigDecimal.ZERO, 10,
        )
        // After the 1000 rent debit lands, the allowance must not drop.
        val after = DailyBudgetPacing.dailyAllowance(
            BigDecimal("3100"), BigDecimal("1000"), BigDecimal("1000"), 10,
        )
        assertEquals(BigDecimal("210.00"), before)
        assertEquals(before, after)
    }

    @Test
    fun `dailyAllowance with zero remaining days is zero, not a division error`() {
        val allowance = DailyBudgetPacing.dailyAllowance(
            monthlyBudget = BigDecimal("1000"),
            fixedExpenses = BigDecimal.ZERO,
            spentSoFar = BigDecimal.ZERO,
            remainingDaysInMonth = 0,
        )
        assertEquals(BigDecimal.ZERO, allowance)
    }

    @Test
    fun `remainingDaysInMonth counts today plus the rest of a 31-day month`() {
        assertEquals(31, DailyBudgetPacing.remainingDaysInMonth(LocalDate.of(2026, 7, 1)))
        assertEquals(1, DailyBudgetPacing.remainingDaysInMonth(LocalDate.of(2026, 7, 31)))
        assertEquals(5, DailyBudgetPacing.remainingDaysInMonth(LocalDate.of(2026, 7, 27)))
    }

    @Test
    fun `remainingDaysInMonth handles a February leap-year rollover`() {
        // 2028 is a leap year: 29 days in February.
        assertEquals(29, DailyBudgetPacing.remainingDaysInMonth(LocalDate.of(2028, 2, 1)))
        assertEquals(1, DailyBudgetPacing.remainingDaysInMonth(LocalDate.of(2028, 2, 29)))
    }

    @Test
    fun `no monthly budget set short-circuits to NoBudgetSet`() {
        val result = DailyBudgetPacing.checkPacing(
            transactionAmount = BigDecimal("50"),
            monthlyBudget = BigDecimal.ZERO,
            fixedExpenses = BigDecimal.ZERO,
            spentSoFarIncludingThis = BigDecimal("50"),
            remainingDaysInMonth = 10,
        )
        assertEquals(DailyBudgetPacing.PacingResult.NoBudgetSet, result)
    }

    @Test
    fun `a transaction within today's allowance is on track`() {
        // Allowance before this transaction: (1000 - 0 - 0) / 10 = 100.
        val result = DailyBudgetPacing.checkPacing(
            transactionAmount = BigDecimal("80"),
            monthlyBudget = BigDecimal("1000"),
            fixedExpenses = BigDecimal.ZERO,
            spentSoFarIncludingThis = BigDecimal("80"),
            remainingDaysInMonth = 10,
        )
        assertTrue(result is DailyBudgetPacing.PacingResult.OnTrack)
        assertEquals(BigDecimal("100.00"), (result as DailyBudgetPacing.PacingResult.OnTrack).dailyAllowance)
    }

    @Test
    fun `a transaction exceeding today's allowance triggers Overspend with the correct amount`() {
        // Allowance before this transaction: (1000 - 0 - 0) / 10 = 100.
        val result = DailyBudgetPacing.checkPacing(
            transactionAmount = BigDecimal("150"),
            monthlyBudget = BigDecimal("1000"),
            fixedExpenses = BigDecimal.ZERO,
            spentSoFarIncludingThis = BigDecimal("150"),
            remainingDaysInMonth = 10,
        )
        assertTrue(result is DailyBudgetPacing.PacingResult.Overspend)
        val overspend = result as DailyBudgetPacing.PacingResult.Overspend
        assertEquals(BigDecimal("100.00"), overspend.dailyAllowance)
        assertEquals(BigDecimal("50.00"), overspend.overspendAmount)
    }

    @Test
    fun `a transaction landing on an already-negative allowance still reports Overspend`() {
        val result = DailyBudgetPacing.checkPacing(
            transactionAmount = BigDecimal("10"),
            monthlyBudget = BigDecimal("100"),
            fixedExpenses = BigDecimal("50"),
            // Already spent 200 before this 10 -- deep in the hole regardless of today's transaction.
            spentSoFarIncludingThis = BigDecimal("210"),
            remainingDaysInMonth = 5,
        )
        assertTrue(result is DailyBudgetPacing.PacingResult.Overspend)
    }
}
