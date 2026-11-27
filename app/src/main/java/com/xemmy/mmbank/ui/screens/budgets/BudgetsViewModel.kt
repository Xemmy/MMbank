package com.xemmy.mmbank.ui.screens.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.budget.BudgetListBuilder
import com.xemmy.mmbank.domain.budget.BudgetListRow
import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.ui.util.recoverAndRetry
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BudgetsUiState(
    val rows: List<BudgetListRow> = emptyList(),
    val currency: String = "USD",
    /** The whole-month budget (a plain preference); 0 = not set. */
    val overallBudget: Double = 0.0,
    /** Everything spent this month, base currency, by the one Spent definition. */
    val overallSpent: Double = 0.0,
    val loaded: Boolean = false,
)

class BudgetsViewModel(private val container: AppContainer) : ViewModel() {

    private val overall = MutableStateFlow(container.preferences.monthlySpendBudget)

    val uiState: StateFlow<BudgetsUiState> = combine(
        container.categoryRepository.observeAll(),
        container.transactionRepository.observeTransactions(),
        container.ratesRepository.observeRates(),
        overall,
    ) { categories, transactions, rates, overallBudget ->
        val currency = container.preferences.defaultCurrency
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val convert = CategoryBudgetCalculator.converter(
            currency,
            rates.filter { it.baseCode.equals(currency, ignoreCase = true) }.associate { it.quoteCode.uppercase() to it.rate },
        )
        val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthEnd = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val inMonth = transactions.filter { it.timestampMillis in monthStart until monthEnd }
        BudgetsUiState(
            rows = BudgetListBuilder.build(categories, transactions, currency, convert, today, zone, container.preferences.budgetRollover),
            currency = currency,
            overallBudget = overallBudget,
            overallSpent = LedgerMath.netSpend(inMonth) { tx -> convert(tx.currencyCode, tx.amount)?.toDouble() },
            loaded = true,
        )
    }.flowOn(Dispatchers.Default)
        .recoverAndRetry("BudgetsViewModel", container) { BudgetsUiState(loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsUiState())

    /** Sets a category's monthly cap; null removes it. */
    fun setCap(category: Category, cap: BigDecimal?) {
        viewModelScope.launch {
            runCatching {
                container.categoryRepository.save(category.copy(monthlyCap = cap?.takeIf { it.signum() > 0 }))
            }.onFailure { container.recordError("BudgetsViewModel.setCap", it) }
        }
    }

    /** Sets the whole-month budget; 0 or less clears it. */
    fun setOverall(amount: Double) {
        val value = amount.coerceAtLeast(0.0)
        container.preferences.monthlySpendBudget = value
        overall.value = value
    }
}
