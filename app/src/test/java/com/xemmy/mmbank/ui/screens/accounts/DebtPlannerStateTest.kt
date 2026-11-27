package com.xemmy.mmbank.ui.screens.accounts

import com.xemmy.mmbank.domain.account.DebtCandidates
import com.xemmy.mmbank.domain.account.DebtPayoffSimulator
import com.xemmy.mmbank.domain.model.AccountType
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtPlannerStateTest {

    private fun candidate(id: Long, owed: String, apr: Double?, min: String, currency: String = "AED") =
        DebtCandidates.Candidate(id, "Debt $id", AccountType.CREDIT_CARD, currency, BigDecimal(owed), apr, BigDecimal(min), false)

    private val candidates = listOf(candidate(1, "2000", 30.0, "60"), candidate(2, "500", 5.0, "25"))

    @Test
    fun `plans both strategies for a budget`() {
        val s = computeDebtPlan(candidates, emptyMap(), emptyMap(), "300", "AED", emptyMap())
        assertEquals(BigDecimal("85.00"), s.minimumBudget)
        assertEquals(BigDecimal("250.73"), s.avalanche!!.totalInterest)
        assertEquals(BigDecimal("318.95"), s.snowball!!.totalInterest)
        assertEquals("30", s.rows[0].aprInput)
    }

    @Test
    fun `typed rates and minimums override the account`() {
        val s = computeDebtPlan(candidates, mapOf(1L to "0"), mapOf(2L to "100"), "300", "AED", emptyMap())
        assertEquals("0", s.rows[0].aprInput)
        assertEquals("100", s.rows[1].minimumInput)
        assertTrue(s.avalanche!!.totalInterest < BigDecimal("250.73"))
    }

    @Test
    fun `unknown rates are simulated at zero and named`() {
        val s = computeDebtPlan(listOf(candidate(1, "1000", null, "100")), emptyMap(), emptyMap(), "250", "AED", emptyMap())
        assertEquals(listOf("Debt 1"), s.unknownRate)
        assertEquals(4, s.avalanche!!.months)
    }

    @Test
    fun `a debt in a currency with no rate is left out and named`() {
        val s = computeDebtPlan(candidates + candidate(3, "100", 10.0, "10", currency = "EUR"), emptyMap(), emptyMap(), "300", "AED", emptyMap())
        assertEquals(listOf("Debt 3"), s.unconverted)
        assertEquals(2, s.avalanche!!.debts.size)
    }

    @Test
    fun `no budget, no plan`() {
        val s = computeDebtPlan(candidates, emptyMap(), emptyMap(), "", "AED", emptyMap())
        assertNull(s.avalanche)
        assertNull(s.snowball)
    }

    @Test
    fun `suggested budget is minimums plus a fifth, rounded up to 50`() {
        assertEquals(BigDecimal("150"), suggestedBudget(BigDecimal("85")))
        assertEquals(BigDecimal("0"), suggestedBudget(BigDecimal.ZERO))
    }

    @Test
    fun `comparison names the saving and the quick win`() {
        val debts = listOf(
            DebtPayoffSimulator.Debt(1, "Card", BigDecimal("2000"), 30.0, BigDecimal("60")),
            DebtPayoffSimulator.Debt(2, "Loan", BigDecimal("500"), 5.0, BigDecimal("25")),
        )
        val (a, s) = DebtPayoffSimulator.compare(debts, BigDecimal("300"))
        val line = compareStrategies(a, s) { "AED $it" }
        assertNotNull(line)
        assertEquals("Avalanche saves AED 68.22 in interest. Snowball clears Loan first, in month 3.", line)
    }
}
