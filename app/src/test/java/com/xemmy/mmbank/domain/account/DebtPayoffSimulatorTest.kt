package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.account.DebtPayoffSimulator.Debt
import com.xemmy.mmbank.domain.account.DebtPayoffSimulator.Outcome
import com.xemmy.mmbank.domain.account.DebtPayoffSimulator.Strategy
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtPayoffSimulatorTest {

    private fun debt(id: Long, balance: String, apr: Double, min: String) =
        Debt(id, "Debt $id", BigDecimal(balance), apr, BigDecimal(min))

    private fun simulate(debts: List<Debt>, budget: String, strategy: Strategy = Strategy.AVALANCHE, cap: Int = 600) =
        DebtPayoffSimulator.simulate(debts, BigDecimal(budget), strategy, cap)

    @Test
    fun `one debt at 12 percent matches a hand amortisation`() {
        // 1,000 at 1% a month, 100 a month: 11 payments, 58.98 of interest.
        val plan = simulate(listOf(debt(1, "1000", 12.0, "100")), "100")
        assertEquals(Outcome.PAID_OFF, plan.outcome)
        assertEquals(11, plan.months)
        assertEquals(BigDecimal("58.98"), plan.totalInterest)
    }

    @Test
    fun `zero rate debt is just division`() {
        val plan = simulate(listOf(debt(1, "1000", 0.0, "100")), "250")
        assertEquals(4, plan.months)
        assertEquals(BigDecimal("0.00"), plan.totalInterest)
    }

    private val two = listOf(debt(1, "2000", 30.0, "60"), debt(2, "500", 5.0, "25"))

    @Test
    fun `avalanche costs less interest than snowball`() {
        val (avalanche, snowball) = DebtPayoffSimulator.compare(two, BigDecimal("300"))
        assertEquals(BigDecimal("250.73"), avalanche.totalInterest)
        assertEquals(BigDecimal("318.95"), snowball.totalInterest)
        assertTrue(avalanche.totalInterest < snowball.totalInterest)
        assertEquals(10, avalanche.months)
        assertEquals(10, snowball.months)
    }

    @Test
    fun `snowball clears the smallest balance first`() {
        val snowball = simulate(two, "300", Strategy.SNOWBALL)
        assertEquals(listOf(2L, 1L), snowball.debts.map { it.id })
        assertEquals(3, snowball.debts.first().payoffMonth)
        val avalanche = simulate(two, "300", Strategy.AVALANCHE)
        assertEquals(listOf(1L, 2L), avalanche.debts.map { it.id })
        assertEquals(9, avalanche.debts.first().payoffMonth)
    }

    @Test
    fun `a budget below the minimums is refused`() {
        val plan = simulate(two, "80")
        assertEquals(Outcome.BUDGET_BELOW_MINIMUMS, plan.outcome)
        assertEquals(BigDecimal("85"), DebtPayoffSimulator.minimumBudget(two))
    }

    @Test
    fun `a budget that doesn't cover interest never pays off`() {
        // 10,000 at 36% accrues 300 a month.
        assertEquals(Outcome.NEVER_PAYS_OFF, simulate(listOf(debt(1, "10000", 36.0, "100")), "300").outcome)
    }

    @Test
    fun `a very slow payoff stops at the cap`() {
        val plan = simulate(listOf(debt(1, "10000", 36.0, "100")), "301", cap = 24)
        assertEquals(Outcome.HIT_CAP, plan.outcome)
        assertEquals(24, plan.months)
        assertEquals(null, plan.debts.single().payoffMonth)
    }

    @Test
    fun `nothing owed is already paid off`() {
        val plan = simulate(listOf(debt(1, "0", 20.0, "50")), "100")
        assertEquals(Outcome.PAID_OFF, plan.outcome)
        assertEquals(0, plan.months)
        assertTrue(plan.debts.isEmpty())
    }

    @Test
    fun `a minimum larger than the balance only pays the balance`() {
        val plan = simulate(listOf(debt(1, "50", 0.0, "100")), "100")
        assertEquals(1, plan.months)
        assertEquals(BigDecimal("50"), DebtPayoffSimulator.minimumBudget(listOf(debt(1, "50", 0.0, "100"))))
    }

    @Test
    fun `monthly interest rounds to the cent`() {
        assertEquals(BigDecimal("8.33"), DebtPayoffSimulator.monthlyInterest(BigDecimal("1000"), 10.0))
        assertEquals(BigDecimal("0.00"), DebtPayoffSimulator.monthlyInterest(BigDecimal("1000"), 0.0))
    }
}
