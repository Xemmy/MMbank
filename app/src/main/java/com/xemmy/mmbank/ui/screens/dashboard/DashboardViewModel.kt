package com.xemmy.mmbank.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.WalletBalance
import com.xemmy.mmbank.ui.util.CurrencyMath
import com.xemmy.mmbank.ui.util.recoverAndRetry
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountRow(
    val account: Account,
    val balance: Double,
    val balanceInBase: Double?,
)

/** Income vs spend for one month, converted to the base currency. */
data class MonthlyFlow(val label: String, val income: Double, val spend: Double)

/** Current-month spend attributed to one person. */
data class PersonSpend(val person: String, val amount: Double)

/** Current-month spend grouped by spending category. */
data class CategorySpend(val category: SpendingCategory, val amount: Double)

data class DashboardStats(
    /** Oldest -> newest, always six entries. */
    val monthly: List<MonthlyFlow> = emptyList(),
    val personSpends: List<PersonSpend> = emptyList(),
    val categorySpends: List<CategorySpend> = emptyList(),
    /** Average net flow of the last up-to-3 completed months with activity. */
    val avgMonthlyNet: Double = 0.0,
)

data class GoalState(
    val targetAmount: Double = 0.0,
    /** 0 = estimate from the ledger's average monthly net flow. */
    val monthlySaving: Double = 0.0,
) {
    val isSet: Boolean get() = targetAmount > 0.0
}

data class DashboardUiState(
    val baseCurrency: String = "USD",
    val accounts: List<AccountRow> = emptyList(),
    val cryptoBalances: List<AssetBalance> = emptyList(),
    /** Per-wallet totals (Earn, Funding, Trading Bots, …), biggest first. */
    val cryptoWallets: List<WalletBalance> = emptyList(),
    val cryptoTotalUsd: Double = 0.0,
    val recent: List<Transaction> = emptyList(),
    /** sender -> user-defined friendly name, applied before the built-in bank map. */
    val senderLabels: Map<String, String> = emptyMap(),
    /** Sum of everything convertible into the base currency; liabilities negative. */
    val netWorthInBase: Double = 0.0,
    val hasUnconvertedAmounts: Boolean = false,
    val stats: DashboardStats = DashboardStats(),
    val goal: GoalState = GoalState(),
    /**
     * When true, stats are computed over the raw ledger — transactions flagged
     * [Transaction.excludeFromStats] (balance corrections, refund adjustments)
     * are counted instead of filtered out. Off by default.
     */
    val showAllLedgerTransactions: Boolean = false,
    val hideBalances: Boolean = false,
    val refreshing: Boolean = false,
    val message: String? = null,
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private companion object {
        /** Below this, a holding rounds to "$0.00" at 2-decimal money formatting. */
        const val MIN_VISIBLE_USD = 0.005
    }

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val showAllLedgerTransactions = MutableStateFlow(false)

    private data class PrefsSnapshot(val hideBalances: Boolean, val goal: GoalState)

    private fun readPrefs() = PrefsSnapshot(
        hideBalances = container.preferences.hideBalances,
        goal = GoalState(
            targetAmount = container.preferences.goalTargetAmount,
            monthlySaving = container.preferences.goalMonthlySaving,
        ),
    )

    private val prefsState = MutableStateFlow(readPrefs())

    private data class CryptoData(
        val assets: List<AssetBalance>,
        val wallets: List<WalletBalance>,
    )

    // Merged up-front so the ledger combine below stays within the five-flow
    // overload of combine().
    private val cryptoFlow = combine(
        container.portfolioRepository.observeBalances(),
        container.portfolioRepository.observeWallets(),
    ) { assets, wallets -> CryptoData(assets, wallets) }

    private val ledgerState = combine(
        container.accountRepository.observeActive(),
        container.transactionRepository.observeNetByAccount(),
        cryptoFlow,
        container.ratesRepository.observeRates(),
        container.transactionRepository.observeRecent(10),
    ) { accounts, netByAccount, crypto, rates, recent ->
        val base = container.preferences.defaultCurrency
        val hideZeroBalances = container.preferences.hideZeroBalances
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

        // Dust and zero-USD holdings are noise on the dashboard: a coin worth
        // $0.002 still displays as "$0.00" at 2-decimal money formatting, and
        // an unpriced token contributes nothing visible either. Requiring a
        // real (roundable) USD value hides both, not just a zero token amount.
        // Users can turn this off in Settings to see every holding, dust included.
        val visibleCrypto = crypto.assets
            .filter { !hideZeroBalances || (it.valueUsd?.toDouble() ?: 0.0) >= MIN_VISIBLE_USD }
            .sortedByDescending { it.valueUsd?.toDouble() ?: 0.0 }
        val visibleWallets = crypto.wallets
            .filter { !hideZeroBalances || (it.valueUsd?.toDouble() ?: 0.0) >= MIN_VISIBLE_USD }
            .sortedByDescending { it.valueUsd?.toDouble() ?: 0.0 }

        // The wallet overview covers Earn/Funding/Bots on top of Spot, so it
        // is the truer total when available; spot-only is the fallback for
        // API keys that can't read it.
        val walletTotalUsd = crypto.wallets.sumOf { it.valueUsd?.toDouble() ?: 0.0 }
        val spotTotalUsd = crypto.assets.sumOf { it.valueUsd?.toDouble() ?: 0.0 }
        val cryptoTotalUsd = if (walletTotalUsd > 0.0) walletTotalUsd else spotTotalUsd
        val cryptoInBase = CurrencyMath.toBase(cryptoTotalUsd, "USD", base, ratesFromBase)

        val fiatInBase = accountRows.mapNotNull { it.balanceInBase }.sum()
        val hasUnconverted = accountRows.any { it.balanceInBase == null } ||
            (cryptoTotalUsd != 0.0 && cryptoInBase == null) ||
            crypto.assets.any { it.valueUsd == null && it.total.signum() > 0 }

        DashboardUiState(
            baseCurrency = base,
            accounts = accountRows,
            cryptoBalances = visibleCrypto,
            cryptoWallets = visibleWallets,
            cryptoTotalUsd = cryptoTotalUsd,
            recent = recent,
            netWorthInBase = fiatInBase + (cryptoInBase ?: 0.0),
            hasUnconvertedAmounts = hasUnconverted,
        )
    }.recoverAndRetry("DashboardViewModel", container) { e ->
        // Never let a bad row (or a rates/DB hiccup) take the whole app down —
        // show a dismissible message, then resubscribe so balances stay live.
        DashboardUiState(message = "Dashboard couldn't load: ${e.message}")
    }

    private val senderLabels = container.senderLabelRepository.observeLabels()
        .recoverAndRetry("DashboardViewModel.senderLabels", container) { emptyMap() }

    private val statsFlow = combine(
        container.transactionRepository.observeTransactions(),
        container.ratesRepository.observeRates(),
        showAllLedgerTransactions,
    ) { transactions, rates, showAll ->
        computeDashboardStats(transactions, rates, container.preferences.defaultCurrency, showAll)
    }.recoverAndRetry("DashboardViewModel.stats", container) { DashboardStats() }

    private data class UiMeta(
        val refreshing: Boolean,
        val message: String?,
        val prefs: PrefsSnapshot,
        val showAllLedger: Boolean,
    )

    // Folded into one flow so the final combine stays within five slots.
    private val uiMeta = combine(refreshing, message, prefsState, showAllLedgerTransactions) { busy, msg, prefs, showAll ->
        UiMeta(busy, msg, prefs, showAll)
    }

    val uiState: StateFlow<DashboardUiState> =
        combine(ledgerState, senderLabels, statsFlow, uiMeta) { state, labels, stats, meta ->
            state.copy(
                senderLabels = labels,
                stats = stats,
                goal = meta.prefs.goal,
                showAllLedgerTransactions = meta.showAllLedger,
                hideBalances = meta.prefs.hideBalances,
                refreshing = meta.refreshing,
                message = meta.message ?: state.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    /** Flips analytics between "clean" (default) and raw-ledger figures. */
    fun toggleShowAllLedgerTransactions() {
        showAllLedgerTransactions.value = !showAllLedgerTransactions.value
    }

    fun toggleHideBalances() {
        container.preferences.hideBalances = !container.preferences.hideBalances
        prefsState.value = readPrefs()
    }

    fun saveGoal(targetAmount: Double, monthlySaving: Double) {
        container.preferences.goalTargetAmount = targetAmount.coerceAtLeast(0.0)
        container.preferences.goalMonthlySaving = monthlySaving.coerceAtLeast(0.0)
        prefsState.value = readPrefs()
    }

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
                // A wallet the key can't read (e.g. Earn) is a partial success —
                // tell the user which, so it's not silently missing.
                container.binanceWalletAssets.lastFailedWallets
                    .takeIf { it.isNotEmpty() }
                    ?.let { errors += "Couldn't read: ${it.joinToString(", ")} (check API key permissions)" }
            }

            message.value = if (errors.isEmpty()) "Refreshed" else errors.joinToString("\n")
            refreshing.value = false
        }
    }

    fun dismissMessage() {
        message.value = null
    }
}

/**
 * Six months of income/spend plus this month's per-person and per-category
 * spend, all in the base currency (unconvertible amounts are skipped, not
 * guessed).
 *
 * Transactions flagged [Transaction.excludeFromStats] (balance corrections,
 * refund adjustments, internal transfers) are dropped before any aggregation
 * unless [includeExcluded] is set, in which case the raw ledger is computed.
 *
 * Pure and top-level so it runs under plain JVM unit tests; [zone] and
 * [thisMonth] are injectable for determinism and default to the device values.
 */
internal fun computeDashboardStats(
    transactions: List<Transaction>,
    rates: List<ExchangeRate>,
    baseCurrency: String,
    includeExcluded: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    thisMonth: YearMonth = YearMonth.now(zone),
): DashboardStats {
    val counted = if (includeExcluded) transactions else transactions.filter { !it.excludeFromStats }
    val ratesFromBase = rates
        .filter { it.baseCode.equals(baseCurrency, ignoreCase = true) }
        .associate { it.quoteCode.uppercase() to it.rate.toDouble() }

    fun inBase(tx: Transaction): Double? =
        CurrencyMath.toBase(tx.amount.toDouble(), tx.currencyCode, baseCurrency, ratesFromBase)

    val byMonth = counted.groupBy {
        YearMonth.from(Instant.ofEpochMilli(it.timestampMillis).atZone(zone))
    }

    val monthly = (5 downTo 0).map { back ->
        val month = thisMonth.minusMonths(back.toLong())
        var income = 0.0
        var spend = 0.0
        byMonth[month].orEmpty().forEach { tx ->
            val value = inBase(tx) ?: return@forEach
            if (tx.direction == TransactionDirection.CREDIT) income += value else spend += value
        }
        MonthlyFlow(
            label = month.month.getDisplayName(TextStyle.SHORT, Locale.US),
            income = income,
            spend = spend,
        )
    }

    val personSpends = byMonth[thisMonth].orEmpty()
        .filter { it.direction == TransactionDirection.DEBIT }
        .groupBy { it.person?.trim()?.takeIf(String::isNotEmpty) ?: "Unassigned" }
        .mapNotNull { (person, txs) ->
            val total = txs.sumOf { inBase(it) ?: 0.0 }
            if (total <= 0.0) null else PersonSpend(person, total)
        }
        .sortedByDescending { it.amount }

    val categorySpends = byMonth[thisMonth].orEmpty()
        .filter { it.direction == TransactionDirection.DEBIT }
        .groupBy { it.spendingCategory }
        .mapNotNull { (category, txs) ->
            val total = txs.sumOf { inBase(it) ?: 0.0 }
            if (total <= 0.0) null else CategorySpend(category, total)
        }
        .sortedByDescending { it.amount }

    val priorMonths = monthly.dropLast(1).filter { it.income != 0.0 || it.spend != 0.0 }.takeLast(3)
    val avgNet = if (priorMonths.isEmpty()) 0.0 else priorMonths.sumOf { it.income - it.spend } / priorMonths.size

    return DashboardStats(
        monthly = monthly,
        personSpends = personSpends,
        categorySpends = categorySpends,
        avgMonthlyNet = avgNet,
    )
}
