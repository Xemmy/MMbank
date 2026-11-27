package com.xemmy.mmbank.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The income/expense semantic extension properties on [androidx.compose.material3.ColorScheme]
 * pick their token pair off [androidx.compose.material3.ColorScheme.surface] luminance, since
 * ColorScheme carries no other signal of which theme built it.
 */
class SemanticColorExtensionsTest {

    @Test
    fun `dark surface resolves to dark income and expense tokens`() {
        val scheme = darkColorScheme(surface = DarkSurface)
        assertEquals(IncomeContainerDark, scheme.incomeContainer)
        assertEquals(OnIncomeContainerDark, scheme.onIncomeContainer)
        assertEquals(ExpenseContainerDark, scheme.expenseContainer)
        assertEquals(OnExpenseContainerDark, scheme.onExpenseContainer)
    }

    @Test
    fun `light surface resolves to light income and expense tokens`() {
        val scheme = lightColorScheme(surface = LightSurface)
        assertEquals(IncomeContainerLight, scheme.incomeContainer)
        assertEquals(OnIncomeContainerLight, scheme.onIncomeContainer)
        assertEquals(ExpenseContainerLight, scheme.expenseContainer)
        assertEquals(OnExpenseContainerLight, scheme.onExpenseContainer)
    }
}
