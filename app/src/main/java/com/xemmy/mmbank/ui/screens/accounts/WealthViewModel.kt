package com.xemmy.mmbank.ui.screens.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.account.WealthDays
import com.xemmy.mmbank.domain.account.WealthRange
import com.xemmy.mmbank.ui.screens.dashboard.computeWealthSeries
import com.xemmy.mmbank.ui.util.recoverAndRetry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** The wealth line on top of Accounts: net worth per day over the chosen range. */
data class WealthState(
    val range: WealthRange = WealthRange.MONTH,
    val days: List<LocalDate> = emptyList(),
    val values: List<Double> = emptyList(),
    val currency: String = "USD",
    val loaded: Boolean = false,
) {
    val latest: Double? get() = values.lastOrNull()
    val change: Double? get() = if (values.size >= 2) values.last() - values.first() else null
}

class WealthViewModel(private val container: AppContainer) : ViewModel() {

    private val range = MutableStateFlow(WealthRange.MONTH)
    val hideBalances: StateFlow<Boolean> = container.hideBalances

    fun setRange(value: WealthRange) { range.value = value }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<WealthState> = combine(
        container.accountRepository.observeActive(),
        container.transactionRepository.observeTransactions(),
        container.ratesRepository.observeRates(),
        container.ratesRepository.observeHistory(container.preferences.defaultCurrency),
        range,
    ) { accounts, transactions, rates, history, selected ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val base = container.preferences.defaultCurrency
        val earliest = transactions.minOfOrNull { it.timestampMillis }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val days = WealthDays.sample(WealthDays.start(selected, today, earliest), today)
        WealthState(
            range = selected,
            days = days,
            values = computeWealthSeries(
                accounts = accounts, transactions = transactions, rates = rates, history = history,
                baseCurrency = base, excludeCreditCardFromTotal = container.preferences.excludeCreditCardFromTotal,
                days = days, today = today, zone = zone,
            ),
            currency = base,
            loaded = true,
        )
    }.flowOn(Dispatchers.Default)
        .recoverAndRetry("WealthViewModel", container) { WealthState(loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WealthState())
}
