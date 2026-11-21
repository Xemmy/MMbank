package com.xemmy.mmbank.domain.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinancialHealthEngineTest {

    @Test
    fun `savings rate is the share of income kept`() {
        val metrics = FinancialHealthEngine.calculate(
            FinancialHealthEngine.HealthInputs(
                periodIncome = 5000.0,
                periodExpenses = 3500.0,
                liquidBalance = 10000.0,
                averageMonthlyFixedExpenses = 2000.0,
            )
        )
        assertEquals(30.0, metrics.savingsRatePercent, 0.001)
        assertEquals(1500.0, metrics.netCashFlow, 0.001)
    }

    @Test
    fun `savings rate is zero when there is no income to divide by`() {
        val metrics = FinancialHealthEngine.calculate(
            FinancialHealthEngine.HealthInputs(
                periodIncome = 0.0,
                periodExpenses = 500.0,
                liquidBalance = 1000.0,
                averageMonthlyFixedExpenses = 500.0,
            )
        )
        assertEquals(0.0, metrics.savingsRatePercent, 0.001)
        assertEquals(-500.0, metrics.netCashFlow, 0.001)
    }

    @Test
    fun `savings rate goes negative when expenses exceed income`() {
        val metrics = FinancialHealthEngine.calculate(
            FinancialHealthEngine.HealthInputs(
                periodIncome = 1000.0,
                periodExpenses = 1500.0,
                liquidBalance = 500.0,
                averageMonthlyFixedExpenses = 500.0,
            )
        )
        assertEquals(-50.0, metrics.savingsRatePercent, 0.001)
    }

    @Test
    fun `cash runway is liquid balance over average monthly fixed expenses`() {
        val metrics = FinancialHealthEngine.calculate(
            FinancialHealthEngine.HealthInputs(
                periodIncome = 4000.0,
                periodExpenses = 3000.0,
                liquidBalance = 6000.0,
                averageMonthlyFixedExpenses = 1500.0,
            )
        )
        assertEquals(4.0, requireNotNull(metrics.cashRunwayMonths), 0.001)
    }

    @Test
    fun `cash runway is null when there are no fixed expenses to divide by`() {
        val metrics = FinancialHealthEngine.calculate(
            FinancialHealthEngine.HealthInputs(
                periodIncome = 4000.0,
                periodExpenses = 3000.0,
                liquidBalance = 6000.0,
                averageMonthlyFixedExpenses = 0.0,
            )
        )
        assertNull(metrics.cashRunwayMonths)
    }

    @Test
    fun `net cash flow and echoed income-expenses are returned as given`() {
        val metrics = FinancialHealthEngine.calculate(
            FinancialHealthEngine.HealthInputs(
                periodIncome = 2200.0,
                periodExpenses = 2200.0,
                liquidBalance = 0.0,
                averageMonthlyFixedExpenses = 0.0,
            )
        )
        assertEquals(0.0, metrics.netCashFlow, 0.001)
        assertEquals(2200.0, metrics.income, 0.001)
        assertEquals(2200.0, metrics.expenses, 0.001)
    }
}
