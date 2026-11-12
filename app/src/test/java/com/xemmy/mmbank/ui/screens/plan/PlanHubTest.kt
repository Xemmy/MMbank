package com.xemmy.mmbank.ui.screens.plan

import com.xemmy.mmbank.ui.navigation.AppTab
import com.xemmy.mmbank.ui.navigation.TabRouting
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanHubTest {
    @Test fun everyPlanToolKeepsThePlanTabHighlighted() {
        PlanEntry.entries.forEach { assertEquals(it.name, AppTab.PLAN, TabRouting.tabFor(it.route)) }
    }

    @Test fun routesAndTitlesAreUnique() {
        assertEquals(PlanEntry.entries.size, PlanEntry.entries.map { it.route }.toSet().size)
        assertEquals(PlanEntry.entries.size, PlanEntry.entries.map { it.title }.toSet().size)
    }

    @Test fun listsTheSevenPlannedTools() {
        assertEquals(
            listOf("Budgets", "Bills", "Subscriptions", "Goals", "Debt payoff", "Splits", "Rewards"),
            PlanEntry.entries.map { it.title },
        )
    }
}
