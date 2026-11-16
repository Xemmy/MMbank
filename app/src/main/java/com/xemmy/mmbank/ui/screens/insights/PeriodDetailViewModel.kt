package com.xemmy.mmbank.ui.screens.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.filter.PeriodUnit
import com.xemmy.mmbank.domain.filter.PeriodWindow
import com.xemmy.mmbank.domain.report.PeriodBreakdown
import com.xemmy.mmbank.domain.report.PeriodBreakdownBuilder
import com.xemmy.mmbank.ui.util.CurrencyMath
import com.xemmy.mmbank.ui.util.recoverAndRetry
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** Shared by the Spending and Cash-flow pages: one [PeriodWindow] and the [PeriodBreakdown] for it. */
class PeriodDetailViewModel(private val container: AppContainer) : ViewModel() {

    private val weekStart: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val today: LocalDate = LocalDate.now()
    val baseCurrency: String = container.preferences.defaultCurrency
    val hideBalances: StateFlow<Boolean> = container.hideBalances

    private val _period = MutableStateFlow(PeriodWindow.of(container.preferences.periodUnit, today, weekStart))
    val period: StateFlow<PeriodWindow> = _period.asStateFlow()

    fun setPeriod(window: PeriodWindow) {
        _period.value = window
        if (window.unit != PeriodUnit.CUSTOM && window.unit != PeriodUnit.ALL) {
            container.preferences.periodUnit = window.unit
        }
    }

    fun setUnit(unit: PeriodUnit) = setPeriod(PeriodWindow.of(unit, today, weekStart))

    val weekStartDay: DayOfWeek get() = weekStart

    val breakdown: StateFlow<PeriodBreakdown?> = combine(
        container.transactionRepository.observeTransactions(),
        container.ratesRepository.observeRates(),
        container.categoryRepository.observeAll(),
        _period,
    ) { transactions, rates, categories, period ->
        val ratesFromBase = rates
            .filter { it.baseCode.equals(baseCurrency, ignoreCase = true) }
            .associate { it.quoteCode.uppercase() to it.rate.toDouble() }
        PeriodBreakdownBuilder.build(
            transactions = transactions,
            categories = categories,
            period = period,
            today = today,
            zone = ZoneId.systemDefault(),
        ) { tx -> CurrencyMath.toBase(tx.amount.toDouble(), tx.currencyCode, baseCurrency, ratesFromBase) }
    }.flowOn(Dispatchers.Default)
        .recoverAndRetry("PeriodDetailViewModel.breakdown", container) { null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
