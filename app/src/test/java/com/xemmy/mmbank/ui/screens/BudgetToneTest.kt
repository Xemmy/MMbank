package com.xemmy.mmbank.ui.screens

import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator.BudgetState
import com.xemmy.mmbank.ui.designsystem.MMTone
import com.xemmy.mmbank.ui.screens.budgets.budgetTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BudgetToneTest {
    @Test fun underUsesAccent() = assertNull(budgetTone(BudgetState.UNDER))
    @Test fun nearWarns() = assertEquals(MMTone.WARNING, budgetTone(BudgetState.NEAR))
    @Test fun overIsDanger() = assertEquals(MMTone.DANGER, budgetTone(BudgetState.OVER))
}
