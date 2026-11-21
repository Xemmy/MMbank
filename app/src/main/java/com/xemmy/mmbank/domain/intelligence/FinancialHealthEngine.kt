package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.FinancialHealthMetrics

/**
 * Computes the Dashboard's Financial Health Summary Card metrics: savings
 * rate, cash runway, and net cash flow. Pure and top-level so it's
 * JVM-testable; the caller supplies period income/expenses (already
 * converted to the base currency, same pattern as [AiWeeklyDigestGenerator])
 * and the liquid balance / fixed-expense figures behind runway.
 */
object FinancialHealthEngine {

    /** Everything [calculate] needs, all already in the caller's base currency. */
    data class HealthInputs(
        val periodIncome: Double,
        val periodExpenses: Double,
        /** Cash-equivalent balance (checking/savings/cash accounts). */
        val liquidBalance: Double,
        /** Recurring committed monthly spend (rent, installments, subscriptions, …). */
        val averageMonthlyFixedExpenses: Double,
    )

    /**
     * - Savings rate: `((Income - Expenses) / Income) * 100`; 0 when there's
     *   no income to divide by.
     * - Cash runway: `LiquidBalance / AverageMonthlyFixedExpenses`; null when
     *   there are no fixed expenses to divide by (an undefined runway, not a
     *   misleading zero or infinity).
     * - Net cash flow: `Income - Expenses`.
     */
    fun calculate(inputs: HealthInputs): FinancialHealthMetrics {
        val savingsRate = if (inputs.periodIncome > 0.0) {
            (inputs.periodIncome - inputs.periodExpenses) / inputs.periodIncome * 100.0
        } else {
            0.0
        }
        val runway = if (inputs.averageMonthlyFixedExpenses > 0.0) {
            inputs.liquidBalance / inputs.averageMonthlyFixedExpenses
        } else {
            null
        }
        return FinancialHealthMetrics(
            savingsRatePercent = savingsRate,
            cashRunwayMonths = runway,
            netCashFlow = inputs.periodIncome - inputs.periodExpenses,
            income = inputs.periodIncome,
            expenses = inputs.periodExpenses,
        )
    }
}
