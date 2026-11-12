package com.xemmy.mmbank.ui.screens.plan

import com.xemmy.mmbank.ui.navigation.Routes

/** One tool on the Plan hub. [route] is the NavHost destination it opens. */
enum class PlanEntry(val route: String, val title: String, val subtitle: String) {
    BUDGETS(Routes.CATEGORIES, "Budgets", "Category caps and how much of each you've used"),
    BILLS(Routes.BILLS, "Bills", "Recurring bills and what's due soon"),
    SUBSCRIPTIONS(Routes.SUBSCRIPTIONS, "Subscriptions", "Recurring charges the app has detected"),
    GOALS(Routes.GOALS, "Goals", "Savings targets and progress"),
    DEBT(Routes.DEBT_PLANNER, "Debt payoff", "Plan the fastest or cheapest way out"),
    SPLITS(Routes.SPLITS, "Splits", "Shared expenses and who owes whom"),
    REWARDS(Routes.CASHBACK, "Rewards", "Cashback earned across your cards"),
}
