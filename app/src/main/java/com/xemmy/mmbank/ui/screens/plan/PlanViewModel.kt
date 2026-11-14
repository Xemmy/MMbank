package com.xemmy.mmbank.ui.screens.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.budget.BudgetStatusBuilder
import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** Feeds the one-line live status under each Plan card; see [PlanStatus]. */
class PlanViewModel(private val container: AppContainer) : ViewModel() {

    private data class Budgeting(
        val categories: List<com.xemmy.mmbank.domain.model.Category>,
        val transactions: List<com.xemmy.mmbank.domain.model.Transaction>,
        val rates: List<com.xemmy.mmbank.domain.model.ExchangeRate>,
    )

    private val budgeting = combine(
        container.categoryRepository.observeAll(),
        container.transactionRepository.observeTransactions(),
        container.ratesRepository.observeRates(),
    ) { categories, transactions, rates -> Budgeting(categories, transactions, rates) }

    val statuses: StateFlow<Map<PlanEntry, String>> = combine(
        budgeting,
        container.billRepository.observeAll(),
        container.subscriptionRepository.observeActive(),
        container.savingsGoalRepository.observeActive(),
        container.hideBalances,
    ) { b, bills, subscriptions, goals, hidden ->
        val base = container.preferences.defaultCurrency
        val zone = ZoneId.systemDefault()
        val built = BudgetStatusBuilder.build(
            categories = b.categories,
            transactions = b.transactions,
            baseCurrency = base,
            convert = CategoryBudgetCalculator.converter(
                base,
                b.rates.filter { it.baseCode.equals(base, ignoreCase = true) }.associate { it.quoteCode.uppercase() to it.rate },
            ),
            today = LocalDate.now(zone),
            zone = zone,
            rollover = container.preferences.budgetRollover,
        )
        PlanStatus.forEntries(
            budgets = built.statuses,
            bills = bills,
            subscriptions = subscriptions,
            goals = goals,
            nowMillis = System.currentTimeMillis(),
            money = { amount, currency -> moneyLabel(amount, currency, hidden) },
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
