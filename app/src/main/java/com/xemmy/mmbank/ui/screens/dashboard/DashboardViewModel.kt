package com.xemmy.mmbank.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.ui.util.CurrencyMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountRow(
    val account: Account,
    val balance: Double,
    val balanceInBase: Double?,
)

data class DashboardUiState(
    val baseCurrency: String = "USD",
    val accounts: List<AccountRow> = emptyList(),
    val cryptoBalances: List<AssetBalance> = emptyList(),
    val cryptoTotalUsd: Double = 0.0,
    val recent: List<Transaction> = emptyList(),
    /** sender -> user-defined friendly name, applied before the built-in bank map. */
    val senderLabels: Map<String, String> = emptyMap(),
    /** Sum of everything convertible into the base currency; liabilities negative. */
    val netWorthInBase: Double = 0.0,
    val hasUnconvertedAmounts: Boolean = false,
    val refreshing: Boolean = false,
    val message: String? = null,
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val ledgerState = combine(
        container.accountRepository.observeActive(),
        container.transactionRepository.observeNetByAccount(),
        container.portfolioRepository.observeBalances(),
        container.ratesRepository.observeRates(),
        container.transactionRepository.observeRecent(10),
    ) { accounts, netByAccount, crypto, rates, recent ->
        val base = container.preferences.defaultCurrency
        val ratesFromBase = rates
            .filter { it.baseCode.equals(base, ignoreCase = true) }
            .associate { it.quoteCode.uppercase() to it.rate.toDouble() }

        val accountRows = accounts.map { account ->
            val balance = account.openingBalance.toDouble() + (netByAccount[account.id] ?: 0.0)
            AccountRow(
                account = account,
                balance = balance,
                balanceInBase = CurrencyMath.toBase(balance, account.currencyCode, base, ratesFromBase),
            )
        }

        // Dust/zero balances are noise on the dashboard; show holdings only,
        // biggest first (unpriced assets sink to the bottom).
        val visibleCrypto = crypto
            .filter { it.total.signum() > 0 }
            .sortedByDescending { it.valueUsd?.toDouble() ?: 0.0 }

        val cryptoTotalUsd = crypto.sumOf { it.valueUsd?.toDouble() ?: 0.0 }
        val cryptoInBase = CurrencyMath.toBase(cryptoTotalUsd, "USD", base, ratesFromBase)

        val fiatInBase = accountRows.mapNotNull { it.balanceInBase }.sum()
        val hasUnconverted = accountRows.any { it.balanceInBase == null } ||
            (cryptoTotalUsd != 0.0 && cryptoInBase == null) ||
            crypto.any { it.valueUsd == null && it.total.signum() > 0 }

        DashboardUiState(
            baseCurrency = base,
            accounts = accountRows,
            cryptoBalances = visibleCrypto,
            cryptoTotalUsd = cryptoTotalUsd,
            recent = recent,
            netWorthInBase = fiatInBase + (cryptoInBase ?: 0.0),
            hasUnconvertedAmounts = hasUnconverted,
        )
    }.catch { e ->
        // Never let a bad row (or a rates/DB hiccup) take the whole app down —
        // surface it as a dismissible message instead of crashing on launch.
        container.recordError("DashboardViewModel", e)
        emit(DashboardUiState(message = "Dashboard couldn't load: ${e.message}"))
    }

    private val senderLabels = container.senderLabelRepository.observeLabels()
        .catch { e ->
            container.recordError("DashboardViewModel.senderLabels", e)
            emit(emptyMap())
        }

    val uiState: StateFlow<DashboardUiState> =
        combine(ledgerState, senderLabels, refreshing, message) { state, labels, busy, msg ->
            state.copy(senderLabels = labels, refreshing = busy, message = msg ?: state.message)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    /** Pulls fresh Forex rates and, when keys are configured, Binance spot balances. */
    fun refreshRemote() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            message.value = null
            val errors = mutableListOf<String>()

            container.ratesRepository.refresh(container.preferences.defaultCurrency)
                .onFailure { errors += "Rates: ${it.message}" }

            if (container.credentialStore.hasBinanceCredentials) {
                container.portfolioRepository.syncSpotBalances()
                    .onFailure { errors += "Binance: ${it.message}" }
            }

            message.value = if (errors.isEmpty()) "Refreshed" else errors.joinToString("\n")
            refreshing.value = false
        }
    }

    fun dismissMessage() {
        message.value = null
    }
}
