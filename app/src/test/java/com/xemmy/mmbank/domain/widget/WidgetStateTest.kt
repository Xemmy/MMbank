package com.xemmy.mmbank.domain.widget

import com.xemmy.mmbank.domain.budget.BudgetListRow
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.report.CategoryShare
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateTest {
    private val data = SpendingWidgetData(120.0, listOf(NamedAmount("Food", 80.0)), "AED")

    @Test
    fun `locked beats hidden beats empty beats normal`() {
        assertEquals(WidgetState.Locked, WidgetGate.decide(locked = true, hideBalances = true, data = data))
        assertEquals(WidgetState.Hidden, WidgetGate.decide(locked = false, hideBalances = true, data = data))
        assertEquals(WidgetState.Empty, WidgetGate.decide<SpendingWidgetData>(locked = false, hideBalances = false, data = null))
        assertEquals(
            WidgetState.Empty,
            WidgetGate.decide(false, false, SpendingWidgetData(0.0, emptyList(), "AED")) { it.isEmpty },
        )
        assertEquals(WidgetState.Normal(data), WidgetGate.decide(false, false, data) { it.isEmpty })
    }

    @Test
    fun `a locked or hidden state carries no figures`() {
        val locked = WidgetGate.decide(true, false, data)
        val hidden = WidgetGate.decide(false, true, data)
        assertTrue(locked !is WidgetState.Normal && hidden !is WidgetState.Normal)
    }

    private fun share(name: String, amount: Double) =
        CategoryShare(Category(name = name, icon = "x", colorHex = "#000000"), emptySet(), amount, 1, 0.0)

    @Test
    fun `top categories are the biggest first and capped`() {
        val top = WidgetData.topCategories(listOf(share("A", 10.0), share("B", 50.0), share("C", 30.0), share("D", 20.0)))
        assertEquals(listOf("B", "C", "D"), top.map { it.name })
    }

    private fun row(name: String, spent: String, cap: String?) = BudgetListRow(
        Category(name = name, icon = "x", colorHex = "#000000"),
        BigDecimal(spent), BigDecimal.ZERO, cap?.let(::BigDecimal), null,
    )

    @Test
    fun `budget lines keep only capped categories, fullest first, at most four`() {
        val lines = WidgetData.budgetLines(
            listOf(
                row("None", "999", null), row("A", "10", "100"), row("B", "150", "100"), row("C", "50", "100"),
                row("D", "80", "100"), row("E", "5", "100"),
            ),
        )
        assertEquals(listOf("B", "D", "C", "A"), lines.map { it.name })
        assertTrue(lines.first().over)
        assertEquals(1.5, lines.first().fraction, 1e-9)
    }

    @Test
    fun `change sign ignores sub-cent noise`() {
        assertEquals(1, WidgetData.changeSign(12.0))
        assertEquals(-1, WidgetData.changeSign(-0.5))
        assertEquals(0, WidgetData.changeSign(0.001))
    }
}
