package com.xemmy.mmbank.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.budget.DailyBudgetPacing
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.screens.dashboard.CategorySlice
import com.xemmy.mmbank.ui.screens.dashboard.CategorySpend
import com.xemmy.mmbank.ui.screens.dashboard.categorySlicesFrom
import com.xemmy.mmbank.ui.util.CurrencyMath
import com.xemmy.mmbank.ui.util.recoverAndRetry
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The Analytics screen's own date filter — distinct from the Dashboard's and the Activity tab's. */
sealed interface AnalyticsDateRange {
    data object Daily : AnalyticsDateRange
    data object Weekly : AnalyticsDateRange
    data object Monthly : AnalyticsDateRange
    data class Custom(val start: LocalDate, val end: LocalDate) : AnalyticsDateRange
}

/** Today's spend vs the current safe-to-spend daily allowance (see [DailyBudgetPacing]), for [com.xemmy.mmbank.ui.components.PacingArcGauge]. */
data class DailyPacingState(
    val spentToday: Double = 0.0,
    val allowance: Double = 0.0,
    val currencyCode: String = "USD",
    val hasBudget: Boolean = false,
)

data class AnalyticsUiState(
    val dateRange: AnalyticsDateRange = AnalyticsDateRange.Monthly,
    val baseCurrency: String = "USD",
    val dailySpend: List<DailySpend> = emptyList(),
    val selectedDate: LocalDate? = null,
    /** Transactions in the selected window, narrowed to [selectedDate] when a heatmap tile is tapped. */
    val transactions: List<Transaction> = emptyList(),
    val categorySlices: List<CategorySlice> = emptyList(),
    val pacing: DailyPacingState = DailyPacingState(),
    val hideBalances: Boolean = false,
)

class AnalyticsViewModel(private val container: AppContainer) : ViewModel() {

    private val dateRange = MutableStateFlow<AnalyticsDateRange>(AnalyticsDateRange.Monthly)
    private val selectedDate = MutableStateFlow<LocalDate?>(null)

    val uiState: StateFlow<AnalyticsUiState> = combine(
        container.transactionRepository.observeTransactions(),
        container.ratesRepository.observeRates(),
        dateRange,
        selectedDate,
        container.hideBalances,
    ) { transactions, rates, range, selected, hidden ->
        computeAnalyticsState(container, transactions, rates, range, selected, hidden)
    }.recoverAndRetry("AnalyticsViewModel", container) { AnalyticsUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    fun setDateRange(range: AnalyticsDateRange) {
        dateRange.value = range
        selectedDate.value = null
    }

    /** Taps the same tile again to clear the filter; a different tile replaces it. */
    fun selectDate(date: LocalDate) {
        selectedDate.value = if (selectedDate.value == date) null else date
    }
}

private fun windowFor(range: AnalyticsDateRange, today: LocalDate): Pair<LocalDate, LocalDate> = when (range) {
    AnalyticsDateRange.Daily -> today to today
    AnalyticsDateRange.Weekly -> today.minusDays(6) to today
    AnalyticsDateRange.Monthly -> {
        val month = YearMonth.from(today)
        month.atDay(1) to month.atEndOfMonth()
    }
    is AnalyticsDateRange.Custom -> if (range.start <= range.end) range.start to range.end else range.end to range.start
}

private fun computeAnalyticsState(
    container: AppContainer,
    transactions: List<Transaction>,
    rates: List<ExchangeRate>,
    range: AnalyticsDateRange,
    selected: LocalDate?,
    hidden: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): AnalyticsUiState {
    val base = container.preferences.defaultCurrency
    val ratesFromBase = rates
        .filter { it.baseCode.equals(base, ignoreCase = true) }
        .associate { it.quoteCode.uppercase() to it.rate.toDouble() }

    fun inBase(tx: Transaction): Double? =
        CurrencyMath.toBase(tx.amount.toDouble(), tx.currencyCode, base, ratesFromBase)

    fun Transaction.localDate(): LocalDate = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()

    val (windowStart, windowEnd) = windowFor(range, today)
    val debitsInWindow = transactions.filter { tx ->
        tx.direction == TransactionDirection.DEBIT && !tx.excludeFromStats &&
            tx.localDate() >= windowStart && tx.localDate() <= windowEnd
    }

    val spendByDay = debitsInWindow.groupBy { it.localDate() }
        .mapValues { (_, txs) -> txs.sumOf { inBase(it) ?: 0.0 } }
    val dailySpend = generateSequence(windowStart) { it.plusDays(1) }
        .takeWhile { it <= windowEnd }
        .map { day -> DailySpend(day, spendByDay[day] ?: 0.0) }
        .toList()

    val visibleTransactions = if (selected != null) {
        debitsInWindow.filter { it.localDate() == selected }
    } else {
        debitsInWindow
    }

    val categorySpends = debitsInWindow
        .groupBy { it.spendingCategory }
        .mapNotNull { (category, txs) ->
            val total = txs.sumOf { inBase(it) ?: 0.0 }
            if (total <= 0.0) null else CategorySpend(category, total)
        }
        .sortedByDescending { it.amount }

    // Daily pacing gauge: today's spend vs the current safe-to-spend daily
    // allowance, independent of the selected heatmap range — mirrors
    // AppContainer.checkDailyBudgetPacing's same-currency-only summing so it
    // reads consistently with the overspend notification.
    val monthlyBudget = container.preferences.monthlySpendBudget
    val pacing = if (monthlyBudget > 0.0) {
        val monthStart = YearMonth.from(today).atDay(1)
        val monthTx = transactions.filter { tx ->
            tx.direction == TransactionDirection.DEBIT && !tx.excludeFromStats &&
                tx.currencyCode.equals(base, ignoreCase = true) &&
                tx.localDate() >= monthStart && tx.localDate() <= today
        }
        val spentToday = monthTx.filter { it.localDate() == today }.sumOf { it.amount.toDouble() }
        val spentBeforeToday = monthTx.filter { it.localDate() < today }.sumOf { it.amount.toDouble() }
        val allowance = DailyBudgetPacing.dailyAllowance(
            monthlyBudget = BigDecimal.valueOf(monthlyBudget),
            fixedExpenses = BigDecimal.valueOf(container.preferences.fixedMonthlyExpenses),
            spentSoFar = BigDecimal.valueOf(spentBeforeToday),
            remainingDaysInMonth = DailyBudgetPacing.remainingDaysInMonth(today),
        ).toDouble()
        DailyPacingState(spentToday = spentToday, allowance = allowance, currencyCode = base, hasBudget = true)
    } else {
        DailyPacingState(currencyCode = base, hasBudget = false)
    }

    return AnalyticsUiState(
        dateRange = range,
        baseCurrency = base,
        dailySpend = dailySpend,
        selectedDate = selected,
        transactions = visibleTransactions,
        categorySlices = categorySlicesFrom(categorySpends),
        pacing = pacing,
        hideBalances = hidden,
    )
}
