package com.xemmy.mmbank.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.domain.account.CardPaymentReminders
import com.xemmy.mmbank.domain.account.AccountBalanceCalculator
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.intelligence.AiWeeklyDigestGenerator
import com.xemmy.mmbank.domain.intelligence.SpendAnomalyDetector
import com.xemmy.mmbank.domain.intelligence.CashFlowForecaster
import com.xemmy.mmbank.domain.account.BalanceHistory
import com.xemmy.mmbank.domain.intelligence.CaptureHealthEvaluator
import com.xemmy.mmbank.domain.intelligence.FinancialHealthEngine
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.FinancialHealthMetrics
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.model.WalletBalance
import com.xemmy.mmbank.ui.screens.accounts.UnassignedTotal
import com.xemmy.mmbank.ui.util.CurrencyMath
import com.xemmy.mmbank.ui.util.recoverAndRetry
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountRow(
    val account: Account,
    val balance: Double,
    val balanceInBase: Double?,
)

/** Income vs spend for one month, converted to the base currency. */
data class MonthlyFlow(val label: String, val income: Double, val spend: Double)

/** Selected-period spend attributed to one person. */
data class PersonSpend(val person: String, val amount: Double)

/** Selected-period spend grouped by dynamic category. */
data class CategorySpend(val category: Category, val amount: Double)

/** One donut-chart slice: a category's own color, or the fixed grey for the "Other" rollup. */
data class CategorySlice(val name: String, val amount: Double, val color: Long)

/**
 * The Dashboard's date filter — month-oriented (unlike the Activity tab's
 * day-oriented [com.xemmy.mmbank.domain.filter.DateRangeFilter]) since every
 * stat it drives is aggregated by calendar month or by an arbitrary window.
 */
sealed interface DashboardDateRange {
    data object ThisMonth : DashboardDateRange
    data object Last3Months : DashboardDateRange
    data object Last6Months : DashboardDateRange
    data object Last12Months : DashboardDateRange
    data object YearToDate : DashboardDateRange
    data object AllTime : DashboardDateRange

    /** Arbitrary inclusive range from the twin-calendar picker. */
    data class Custom(val start: LocalDate, val end: LocalDate) : DashboardDateRange
}

/** Tone a [SmartInsight] renders with — drives icon/color in the UI layer. */
enum class InsightTone { POSITIVE, NEGATIVE, NEUTRAL }

/**
 * One line of "smart analysis" about the selected period's money flow.
 * Carries structured data rather than pre-formatted text so the UI layer can
 * apply currency formatting and the hide-balances mask at render time.
 */
sealed interface SmartInsight {
    val tone: InsightTone

    /** Spend this period vs. the immediately preceding period of equal length. */
    data class SpendTrend(val percentChange: Double, override val tone: InsightTone) : SmartInsight

    /** Share of income kept this period; negative when spend exceeded income. */
    data class SavingsRate(val ratePercent: Double, override val tone: InsightTone) : SmartInsight

    /** The single biggest spending category this period, and its share of total spend. */
    data class TopCategory(val category: Category, val amount: Double, val sharePercent: Double) : SmartInsight {
        override val tone get() = InsightTone.NEUTRAL
    }

    /** A category whose spend jumped sharply vs. the preceding period. */
    data class CategorySpike(val category: Category, val amount: Double, val percentChange: Double) : SmartInsight {
        override val tone get() = InsightTone.NEGATIVE
    }

    /** Money went out this period with no income recorded to weigh it against. */
    data class NoIncomeSpend(val spend: Double) : SmartInsight {
        override val tone get() = InsightTone.NEGATIVE
    }

    /** The largest single expense transaction this period. */
    data class BiggestExpense(val merchant: String, val amount: Double) : SmartInsight {
        override val tone get() = InsightTone.NEUTRAL
    }

    /**
     * A charge far above what this merchant usually costs (see
     * [com.xemmy.mmbank.domain.intelligence.SpendAnomalyDetector]). Amounts are
     * in the charge's own [currencyCode], not the base currency.
     */
    data class UnusualCharge(
        val merchant: String,
        val amount: Double,
        val usual: Double,
        val ratio: Double,
        val currencyCode: String,
    ) : SmartInsight {
        override val tone get() = InsightTone.NEGATIVE
    }
}

data class DashboardStats(
    /** Oldest -> newest, always six entries; always the trailing 6 real months, independent of [DashboardDateRange]. */
    val monthly: List<MonthlyFlow> = emptyList(),
    val personSpends: List<PersonSpend> = emptyList(),
    val categorySpends: List<CategorySpend> = emptyList(),
    /** [categorySpends] capped to the top 4 + an "Other" rollup, for the donut chart. */
    val categorySlices: List<CategorySlice> = emptyList(),
    /** Average net flow of the last up-to-3 completed months with activity. */
    val avgMonthlyNet: Double = 0.0,
    /** Human label for the selected [DashboardDateRange], e.g. "This month". */
    val periodLabel: String = "This month",
    val periodIncome: Double = 0.0,
    val periodSpend: Double = 0.0,
    val insights: List<SmartInsight> = emptyList(),
)

data class GoalState(
    val targetAmount: Double = 0.0,
    /** 0 = estimate from the ledger's average monthly net flow. */
    val monthlySaving: Double = 0.0,
) {
    val isSet: Boolean get() = targetAmount > 0.0
}

/** [DashboardViewModel.financialHealth]'s output: the health metrics plus an optional cash-flow forecast. */
data class FinancialHealthState(
    val metrics: FinancialHealthMetrics? = null,
    val cashFlow: CashFlowForecaster.SafeToSpendResult? = null,
)

data class DashboardUiState(
    val baseCurrency: String = "USD",
    val accounts: List<AccountRow> = emptyList(),
    /**
     * Every active account, hidden ones included -- what "Manage accounts"
     * lists. Passing [accounts] there meant an account switched off the
     * Dashboard vanished from the very sheet that could switch it back on.
     */
    val manageableAccounts: List<AccountRow> = emptyList(),
    val cryptoBalances: List<AssetBalance> = emptyList(),
    /** Per-wallet totals (Earn, Funding, Trading Bots, …), biggest first. */
    val cryptoWallets: List<WalletBalance> = emptyList(),
    val cryptoTotalUsd: Double = 0.0,
    val recent: List<Transaction> = emptyList(),
    /** sender -> user-defined friendly name, applied before the built-in bank map. */
    val senderLabels: Map<String, String> = emptyMap(),
    /** Sum of everything convertible into the base currency; liabilities negative. */
    val netWorthInBase: Double = 0.0,
    /** Sum of visible fiat accounts only (no crypto) — the "All Accounts" carousel card's total. */
    val fiatTotalInBase: Double = 0.0,
    val hasUnconvertedAmounts: Boolean = false,
    /** True when crypto's value is deliberately left out of [netWorthInBase] (Settings toggle). */
    val cryptoExcludedFromTotal: Boolean = false,
    /** True when credit card balances are deliberately left out of [netWorthInBase] (Settings toggle). */
    val creditCardExcludedFromTotal: Boolean = false,
    val stats: DashboardStats = DashboardStats(),
    val goal: GoalState = GoalState(),
    /**
     * When true, stats are computed over the raw ledger — transactions flagged
     * [Transaction.excludeFromStats] (balance corrections, refund adjustments)
     * are counted instead of filtered out. Off by default.
     */
    val showAllLedgerTransactions: Boolean = false,
    /** Date filter driving [stats]'s period-scoped fields (income/spend/category/person/insights). */
    val dateRange: DashboardDateRange = DashboardDateRange.ThisMonth,
    val hideBalances: Boolean = false,
    val refreshing: Boolean = false,
    val message: String? = null,
    /** Account the carousel/transaction feed are filtered to; null = "All Accounts". */
    val selectedAccountId: Long? = null,
    /**
     * Money in the ledger that belongs to no account, and so is in no balance.
     * Surfaced rather than left invisible -- see the Accounts screen's card.
     */
    val unassigned: UnassignedTotal = UnassignedTotal(),
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private companion object {
        /** Below this, a holding rounds to "$0.00" at 2-decimal money formatting. */
        const val MIN_VISIBLE_USD = 0.005

        /** Recent-activity feed cap — was a fixed observeRecent(10); now needs headroom to filter by account after the fact. */
        const val FEED_LIMIT = 30
    }

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val showAllLedgerTransactions = MutableStateFlow(false)
    private val dateRange = MutableStateFlow<DashboardDateRange>(DashboardDateRange.ThisMonth)
    /** Carousel selection driving the transaction feed below it; null = "All Accounts". */
    private val selectedAccountId = MutableStateFlow<Long?>(null)

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
        container.transactionRepository.observeLedgerEntries(),
        cryptoFlow,
        container.ratesRepository.observeRates(),
    ) { accounts, ledgerEntries, crypto, rates ->
        val base = container.preferences.defaultCurrency
        val hideZeroBalances = container.preferences.hideZeroBalances
        val hideCryptoBalance = container.preferences.hideCryptoBalance
        val excludeCryptoFromTotal = container.preferences.excludeCryptoFromTotal
        val hideCreditCardBalance = container.preferences.hideCreditCardBalance
        val excludeCreditCardFromTotal = container.preferences.excludeCreditCardFromTotal
        val ratesFromBase = rates
            .filter { it.baseCode.equals(base, ignoreCase = true) }
            .associate { it.quoteCode.uppercase() to it.rate.toDouble() }

        // Exact in BigDecimal (AccountBalanceCalculator), then to Double only at
        // this boundary: everything downstream -- CurrencyMath.toBase, the
        // presentation split, the carousel -- is display math and stays as it was.
        val netByAccount = AccountBalanceCalculator.netByAccount(ledgerEntries)
        val allAccountRows = accounts.map { account ->
            val balance = AccountBalanceCalculator.balanceOf(account, netByAccount).toDouble()
            AccountRow(
                account = account,
                balance = balance,
                balanceInBase = CurrencyMath.toBase(balance, account.currencyCode, base, ratesFromBase),
            )
        }
        val unassigned = UnassignedTotal(
            totalsByCurrency = AccountBalanceCalculator.unassignedTotals(ledgerEntries),
            entryCount = AccountBalanceCalculator.unassignedCount(ledgerEntries),
        )

        val accountsResult = computeAccountsPresentation(
            accountRows = allAccountRows,
            hideCreditCardBalance = hideCreditCardBalance,
            excludeCreditCardFromTotal = excludeCreditCardFromTotal,
        )

        val cryptoResult = computeCryptoPresentation(
            assets = crypto.assets,
            wallets = crypto.wallets,
            hideZeroBalances = hideZeroBalances,
            hideCryptoBalance = hideCryptoBalance,
            excludeCryptoFromTotal = excludeCryptoFromTotal,
            baseCurrency = base,
            ratesFromBase = ratesFromBase,
            minVisibleUsd = MIN_VISIBLE_USD,
        )

        val fiatInBase = accountsResult.fiatTotalInBase
        val hasUnconverted = accountsResult.hasUnconvertedFiat || cryptoResult.hasUnconvertedCrypto

        DashboardUiState(
            baseCurrency = base,
            accounts = accountsResult.visibleAccounts,
            manageableAccounts = allAccountRows,
            cryptoBalances = cryptoResult.visibleAssets,
            cryptoWallets = cryptoResult.visibleWallets,
            cryptoTotalUsd = cryptoResult.cryptoTotalUsd,
            netWorthInBase = fiatInBase + (cryptoResult.cryptoInBase ?: 0.0),
            fiatTotalInBase = fiatInBase,
            hasUnconvertedAmounts = hasUnconverted,
            cryptoExcludedFromTotal = excludeCryptoFromTotal,
            creditCardExcludedFromTotal = excludeCreditCardFromTotal,
            unassigned = unassigned,
        )
    }.recoverAndRetry("DashboardViewModel", container) { e ->
        // Never let a bad row (or a rates/DB hiccup) take the whole app down —
        // show a dismissible message, then resubscribe so balances stay live.
        DashboardUiState(message = "Dashboard couldn't load: ${e.message}")
    }

    private val senderLabels = container.senderLabelRepository.observeLabels()
        .recoverAndRetry("DashboardViewModel.senderLabels", container) { emptyMap() }

    /**
     * "Recent activity" feed, scoped to [selectedAccountId] when the carousel
     * has a specific account selected (null = every account, the old
     * behavior). See [filterAccountFeed] for the pure filter/sort/cap logic.
     */
    private val feedState = combine(
        container.transactionRepository.observeTransactions(),
        selectedAccountId,
    ) { transactions, accountId -> filterAccountFeed(transactions, accountId, FEED_LIMIT) }
        .recoverAndRetry("DashboardViewModel.feed", container) { emptyList() }

    /** Capture permissions as last checked on resume; null until Home has checked once. */
    private val captureAccess = MutableStateFlow<CaptureAccessSnapshot?>(null)
    private val captureSnoozedUntil = MutableStateFlow(container.preferences.captureHealthSnoozedUntil)

    /**
     * Whether capture is still working (see [CaptureHealthEvaluator]) --
     * Home's banner. Null until permissions are known, and in demo mode.
     * Re-evaluated on every resume ([refreshCaptureAccess]) because the
     * listener heartbeat in preferences changes without notifying anyone.
     */
    val captureHealth: StateFlow<CaptureHealthEvaluator.Health?> = combine(
        container.transactionRepository.observeTransactions(),
        captureAccess,
        captureSnoozedUntil,
    ) { transactions, access, snoozedUntil ->
        if (access == null || container.preferences.demoModeEnabled) {
            null
        } else {
            val prefs = container.preferences
            computeCaptureHealth(
                transactions = transactions,
                access = access,
                lastListenerEventAt = prefs.captureLastEventAt,
                listenerConnectedAt = prefs.captureListenerConnectedAt,
                listenerDisconnectedAt = prefs.captureListenerDisconnectedAt,
                snoozedUntil = snoozedUntil,
                now = System.currentTimeMillis(),
            )
        }
    }.flowOn(Dispatchers.Default)
        .recoverAndRetry("DashboardViewModel.captureHealth", container) { null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Month-end net worth for the last 12 months, rebuilt from the ledger
     * (see [computeNetWorthTrend]). Only the Dashboard tab collects it, so
     * Home never pays for the replay; it runs off the main thread.
     */
    val netWorthTrend: StateFlow<NetWorthTrend?> = combine(
        container.accountRepository.observeActive(),
        container.transactionRepository.observeTransactions(),
        container.ratesRepository.observeRates(),
    ) { accounts, transactions, rates ->
        computeNetWorthTrend(
            accounts = accounts,
            transactions = transactions,
            rates = rates,
            baseCurrency = container.preferences.defaultCurrency,
            excludeCreditCardFromTotal = container.preferences.excludeCreditCardFromTotal,
        )
    }.flowOn(Dispatchers.Default)
        .recoverAndRetry("DashboardViewModel.netWorthTrend", container) { null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Called on every resume with the current permission state. */
    fun refreshCaptureAccess(notificationAccess: Boolean, sms: Boolean, batteryExempt: Boolean) {
        captureAccess.value = CaptureAccessSnapshot(notificationAccess, sms, batteryExempt, System.currentTimeMillis())
    }

    /** "Snooze 24h" on the capture-health banner. */
    fun snoozeCaptureHealth() {
        val until = System.currentTimeMillis() + 24L * 60 * 60 * 1000
        container.preferences.captureHealthSnoozedUntil = until
        captureSnoozedUntil.value = until
    }

    /**
     * "AI Weekly Digest" hero card: this week's spend vs last week's and the
     * single biggest expense, via [AiWeeklyDigestGenerator]. Kept as its own
     * flow (not folded into [statsFlow]) since it reads the whole ledger on a
     * rolling 14-day window, independent of the Dashboard's own date filter.
     */
    val weeklyDigest: StateFlow<AiWeeklyDigestGenerator.WeeklyDigest?> = combine(
        container.transactionRepository.observeTransactions(),
        container.ratesRepository.observeRates(),
    ) { transactions, rates ->
        val base = container.preferences.defaultCurrency
        val ratesFromBase = rates
            .filter { it.baseCode.equals(base, ignoreCase = true) }
            .associate { it.quoteCode.uppercase() to it.rate.toDouble() }
        AiWeeklyDigestGenerator.generate(
            transactions = transactions,
            toBase = { tx -> CurrencyMath.toBase(tx.amount.toDouble(), tx.currencyCode, base, ratesFromBase) },
        )
    }.recoverAndRetry("DashboardViewModel.weeklyDigest", container) { null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Badge count for the Home tab's "Review Transactions" entry point -- still-uncategorized real transactions. */
    val pendingReviewCount: StateFlow<Int> = container.transactionRepository.observeUncategorized()
        .map { it.size }
        .recoverAndRetry("DashboardViewModel.pendingReviewCount", container) { 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Credit cards with a payment due within a week, or overdue -- the Dashboard's "Cards due" strip. */
    val cardsDue: StateFlow<List<CardPaymentReminders.CardReminder>> = combine(
        container.accountRepository.observeActive(),
        container.transactionRepository.observeTransactions(),
        container.transactionRepository.observeLedgerEntries(),
    ) { accounts, transactions, entries ->
        CardPaymentReminders.dueSoon(
            CardPaymentReminders.build(accounts, transactions, entries, java.time.LocalDate.now()),
        )
    }.recoverAndRetry("DashboardViewModel.cardsDue", container) { emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Financial Health Summary Card: this month's savings rate / cash runway
     * / net cash flow (see [FinancialHealthEngine]), plus a "safe to spend
     * until payday" cash-flow forecast (see [CashFlowForecaster]). Kept as
     * its own flow (not folded into [statsFlow]) since it's always scoped to
     * the current calendar month, independent of the Dashboard tab's own
     * date filter -- same reasoning as [weeklyDigest]'s rolling window.
     */
    val financialHealth: StateFlow<FinancialHealthState> = combine(
        ledgerState,
        container.transactionRepository.observeTransactions(),
        container.subscriptionRepository.observeActive(),
        container.ratesRepository.observeRates(),
        container.billRepository.observeAll(),
    ) { ledger, transactions, subscriptions, rates, bills ->
        computeFinancialHealth(
            accounts = ledger.accounts,
            transactions = transactions,
            subscriptions = subscriptions,
            bills = bills,
            rates = rates,
            baseCurrency = container.preferences.defaultCurrency,
            fixedMonthlyExpenses = container.preferences.fixedMonthlyExpenses,
            manualPaydayOfMonth = container.preferences.paycheckDayOfMonth.takeIf { it in 1..31 },
        )
    }.recoverAndRetry("DashboardViewModel.financialHealth", container) { FinancialHealthState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinancialHealthState())

    private val statsFlow = combine(
        container.transactionRepository.observeTransactions(),
        container.ratesRepository.observeRates(),
        showAllLedgerTransactions,
        dateRange,
        container.categoryRepository.observeAll(),
    ) { transactions, rates, showAll, range, categories ->
        computeDashboardStats(
            transactions,
            rates,
            container.preferences.defaultCurrency,
            categories,
            showAll,
            dateRange = range,
        )
    }.recoverAndRetry("DashboardViewModel.stats", container) { DashboardStats() }

    private data class UiMeta(
        val refreshing: Boolean,
        val message: String?,
        val prefs: PrefsSnapshot,
        val showAllLedger: Boolean,
        val dateRange: DashboardDateRange,
    )

    // Folded into one flow so the final combine stays within five slots.
    private val uiMeta = combine(
        refreshing,
        message,
        prefsState,
        showAllLedgerTransactions,
        dateRange,
    ) { busy, msg, prefs, showAll, range ->
        UiMeta(busy, msg, prefs, showAll, range)
    }

    /** [uiMeta] plus the carousel selection + its filtered feed, folded together so the final combine stays within five slots. */
    private data class MetaBundle(val meta: UiMeta, val selectedAccountId: Long?, val feed: List<Transaction>)

    private val metaBundle = combine(uiMeta, selectedAccountId, feedState) { meta, accountId, feed ->
        MetaBundle(meta, accountId, feed)
    }

    val uiState: StateFlow<DashboardUiState> =
        combine(
            ledgerState,
            senderLabels,
            statsFlow,
            metaBundle,
            container.hideBalances,
        ) { state, labels, stats, bundle, hidden ->
            state.copy(
                senderLabels = labels,
                stats = stats,
                goal = bundle.meta.prefs.goal,
                showAllLedgerTransactions = bundle.meta.showAllLedger,
                dateRange = bundle.meta.dateRange,
                hideBalances = hidden,
                refreshing = bundle.meta.refreshing,
                message = bundle.meta.message ?: state.message,
                selectedAccountId = bundle.selectedAccountId,
                recent = bundle.feed,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    /** Flips analytics between "clean" (default) and raw-ledger figures. */
    fun toggleShowAllLedgerTransactions() {
        showAllLedgerTransactions.value = !showAllLedgerTransactions.value
    }

    /** Selects an [com.xemmy.mmbank.ui.components.AccountCarousel] card; null selects "All Accounts" and clears the feed filter. */
    fun selectAccount(accountId: Long?) {
        selectedAccountId.value = accountId
    }

    /** Renames an account from [com.xemmy.mmbank.ui.components.ManageAccountsBottomSheet] -- purely cosmetic, doesn't touch its ledger rows. */
    fun renameAccount(account: Account, newName: String) {
        viewModelScope.launch {
            runCatching { container.accountRepository.save(account.copy(name = newName)) }
                .onFailure { container.recordError("DashboardViewModel.renameAccount", it) }
        }
    }

    /** Flips whether [account]'s card shows in the carousel; independent of net worth (see [computeAccountsPresentation]). */
    fun toggleAccountVisibility(account: Account) {
        viewModelScope.launch {
            runCatching { container.accountRepository.save(account.copy(hiddenFromDashboard = !account.hiddenFromDashboard)) }
                .onFailure { container.recordError("DashboardViewModel.toggleAccountVisibility", it) }
        }
    }

    /** Changes the period stats/insights are computed over; the 6-month trend chart is unaffected. */
    fun setDateRange(range: DashboardDateRange) {
        dateRange.value = range
    }

    fun toggleHideBalances() {
        // Route through the container's shared flow so every screen updates live.
        container.toggleHideBalances()
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
 * This-month net flow: income minus spend. Extracted as a pure top-level
 * function so the Net stat tile's math is unit-testable off the main thread
 * (the tile itself only formats the result). A negative result keeps its sign
 * and digits through [com.xemmy.mmbank.ui.util.asMoney].
 */
internal fun netFlow(income: Double, spend: Double): Double = income - spend

/**
 * The Dashboard's "Recent activity" feed: every transaction when [accountId]
 * is null (the "All Accounts" carousel card), otherwise only that account's
 * rows -- newest first, capped at [limit]. Pure and top-level so the
 * account-carousel <-> feed wiring is JVM-testable without a running
 * ViewModel/Flow pipeline.
 */
internal fun filterAccountFeed(transactions: List<Transaction>, accountId: Long?, limit: Int): List<Transaction> =
    transactions
        .filter { accountId == null || it.accountId == accountId }
        .sortedByDescending { it.timestampMillis }
        .take(limit)

/** Grey for the "Other" rollup slice -- not a real category, so it has no [Category.colorHex] of its own. */
private const val OTHER_SLICE_COLOR = 0xFF6C757DL

/**
 * "#RRGGBB" -> a full-alpha ARGB [Long] as [DonutChart]/[CategorySlice] expect.
 * Malformed input falls back to [OTHER_SLICE_COLOR] -- pure-JVM equivalent of
 * [com.xemmy.mmbank.ui.components.categoryColor], which relies on an Android
 * API this file's plain-JUnit tests can't reach.
 */
internal fun colorLongFromHex(hex: String): Long =
    hex.removePrefix("#").toLongOrNull(16)?.let { 0xFF000000L or it } ?: OTHER_SLICE_COLOR

/**
 * Caps [categorySpends] (already sorted descending) to the top 4 entries and
 * rolls everything past that into one "Other" slice, for the donut chart.
 * Each real category's own [Category.colorHex] colors its slice; only the
 * "Other" rollup gets the fixed [OTHER_SLICE_COLOR].
 */
internal fun categorySlicesFrom(categorySpends: List<CategorySpend>): List<CategorySlice> {
    val top = categorySpends.take(4)
    val other = categorySpends.drop(4).sumOf { it.amount }
    val slices = top.map { spend ->
        CategorySlice(spend.category.name, spend.amount, colorLongFromHex(spend.category.colorHex))
    }
    return if (other > 0.0) slices + CategorySlice("Other", other, OTHER_SLICE_COLOR) else slices
}

/**
 * Resolves a transaction's [Transaction.categoryId] against the known
 * [categories] list. A null id, or one that doesn't resolve (category
 * deleted since capture), falls back to the seeded "Uncategorized" category
 * so every debit still lands in a real, colorable bucket rather than being
 * silently dropped -- mirrors [com.xemmy.mmbank.domain.model.CategoryMigrationMapping]'s
 * OTHER-folds-into-Uncategorized philosophy. The final fallback (a synthetic
 * literal) only matters if [categories] itself is empty, e.g. before the
 * v21->v22 seed has run; it keeps this function total rather than throwing.
 */
internal fun resolveCategory(categoryId: Long?, categories: List<Category>): Category {
    val byId = categoryId?.let { id -> categories.firstOrNull { it.id == id } }
    return byId
        ?: categories.firstOrNull { it.name.equals("Uncategorized", ignoreCase = true) }
        ?: Category(name = "Uncategorized", icon = "🏷️", colorHex = "#6C757D")
}

/** Everything the Dashboard needs to show/fold in fiat accounts — see [computeAccountsPresentation]. */
internal data class AccountsPresentation(
    val visibleAccounts: List<AccountRow>,
    val fiatTotalInBase: Double,
    /** True only for a genuine conversion gap among rows counted toward the total. */
    val hasUnconvertedFiat: Boolean,
)

/**
 * Folds every fiat [AccountRow] into what the Dashboard displays and how much
 * counts toward the net-worth total. [hideCreditCardBalance] and
 * [excludeCreditCardFromTotal] are independent Settings toggles: hiding
 * credit card accounts from the list does not by itself change the total,
 * and excluding the total does not by itself hide the accounts.
 *
 * Pure and top-level so it runs under plain JVM unit tests, mirroring
 * [computeCryptoPresentation].
 */
internal fun computeAccountsPresentation(
    accountRows: List<AccountRow>,
    hideCreditCardBalance: Boolean,
    excludeCreditCardFromTotal: Boolean,
): AccountsPresentation {
    val visibleAccounts = (
        if (hideCreditCardBalance) {
            accountRows.filter { it.account.type != AccountType.CREDIT_CARD }
        } else {
            accountRows
        }
        )
        // Per-account Dashboard visibility (ManageAccountsBottomSheet) never
        // affects the net-worth total -- only whether its card shows in the
        // carousel, same independence as hideCreditCardBalance/excludeCreditCardFromTotal.
        .filter { !it.account.hiddenFromDashboard }
    val totalRows = if (excludeCreditCardFromTotal) {
        accountRows.filter { it.account.type != AccountType.CREDIT_CARD }
    } else {
        accountRows
    }
    val fiatTotalInBase = totalRows.mapNotNull { it.balanceInBase }.sum()
    val hasUnconvertedFiat = totalRows.any { it.balanceInBase == null }
    return AccountsPresentation(visibleAccounts, fiatTotalInBase, hasUnconvertedFiat)
}

/** Everything the Dashboard needs to show/fold in crypto — see [computeCryptoPresentation]. */
internal data class CryptoPresentation(
    val visibleAssets: List<AssetBalance>,
    val visibleWallets: List<WalletBalance>,
    val cryptoTotalUsd: Double,
    /** Null when excluded from totals by preference, or when genuinely unconvertible. */
    val cryptoInBase: Double?,
    /** True only for a genuine conversion gap — never true just because crypto was excluded. */
    val hasUnconvertedCrypto: Boolean,
)

/**
 * Folds raw crypto holdings into what the Dashboard displays and how much (if
 * any) counts toward the net-worth total. [hideCryptoBalance] and
 * [excludeCryptoFromTotal] are independent Settings toggles: hiding the
 * section from view does not by itself change the total, and excluding the
 * total does not by itself hide the section.
 *
 * Pure and top-level so it runs under plain JVM unit tests, mirroring
 * [computeDashboardStats].
 */
internal fun computeCryptoPresentation(
    assets: List<AssetBalance>,
    wallets: List<WalletBalance>,
    hideZeroBalances: Boolean,
    hideCryptoBalance: Boolean,
    excludeCryptoFromTotal: Boolean,
    baseCurrency: String,
    ratesFromBase: Map<String, Double>,
    minVisibleUsd: Double,
): CryptoPresentation {
    // Dust and zero-USD holdings are noise on the dashboard: a coin worth
    // $0.002 still displays as "$0.00" at 2-decimal money formatting, and
    // an unpriced token contributes nothing visible either. Requiring a
    // real (roundable) USD value hides both, not just a zero token amount.
    // Users can turn this off in Settings to see every holding, dust included.
    val visibleAssets = if (hideCryptoBalance) {
        emptyList()
    } else {
        assets
            .filter { !hideZeroBalances || (it.valueUsd?.toDouble() ?: 0.0) >= minVisibleUsd }
            .sortedByDescending { it.valueUsd?.toDouble() ?: 0.0 }
    }
    val visibleWallets = if (hideCryptoBalance) {
        emptyList()
    } else {
        wallets
            .filter { !hideZeroBalances || (it.valueUsd?.toDouble() ?: 0.0) >= minVisibleUsd }
            .sortedByDescending { it.valueUsd?.toDouble() ?: 0.0 }
    }

    // The wallet overview covers Earn/Funding/Bots on top of Spot, so it
    // is the truer total when available; spot-only is the fallback for
    // API keys that can't read it.
    val walletTotalUsd = wallets.sumOf { it.valueUsd?.toDouble() ?: 0.0 }
    val spotTotalUsd = assets.sumOf { it.valueUsd?.toDouble() ?: 0.0 }
    val cryptoTotalUsd = if (walletTotalUsd > 0.0) walletTotalUsd else spotTotalUsd

    val cryptoInBase = if (excludeCryptoFromTotal) {
        null
    } else {
        CurrencyMath.toBase(cryptoTotalUsd, "USD", baseCurrency, ratesFromBase)
    }
    val hasUnconvertedCrypto = !excludeCryptoFromTotal &&
        ((cryptoTotalUsd != 0.0 && cryptoInBase == null) ||
            assets.any { it.valueUsd == null && it.total.signum() > 0 })

    return CryptoPresentation(visibleAssets, visibleWallets, cryptoTotalUsd, cryptoInBase, hasUnconvertedCrypto)
}

/**
 * Resolves a [DashboardDateRange] to a concrete calendar-day window, anchored
 * on [thisMonth] rather than a live "today" so callers stay deterministic.
 * Null means unbounded (all time).
 */
internal fun resolveDashboardWindow(range: DashboardDateRange, thisMonth: YearMonth): DateWindow? = when (range) {
    DashboardDateRange.ThisMonth -> DateWindow(thisMonth.atDay(1), thisMonth.atEndOfMonth())
    DashboardDateRange.Last3Months -> DateWindow(thisMonth.minusMonths(2).atDay(1), thisMonth.atEndOfMonth())
    DashboardDateRange.Last6Months -> DateWindow(thisMonth.minusMonths(5).atDay(1), thisMonth.atEndOfMonth())
    DashboardDateRange.Last12Months -> DateWindow(thisMonth.minusMonths(11).atDay(1), thisMonth.atEndOfMonth())
    DashboardDateRange.YearToDate -> DateWindow(LocalDate.of(thisMonth.year, 1, 1), thisMonth.atEndOfMonth())
    DashboardDateRange.AllTime -> null
    is DashboardDateRange.Custom ->
        if (range.start <= range.end) DateWindow(range.start, range.end) else DateWindow(range.end, range.start)
}

private val PERIOD_LABEL_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/** Human label for a [DashboardDateRange], e.g. "Last 3 months" or a formatted custom span. */
internal fun dashboardPeriodLabel(range: DashboardDateRange): String = when (range) {
    DashboardDateRange.ThisMonth -> "This month"
    DashboardDateRange.Last3Months -> "Last 3 months"
    DashboardDateRange.Last6Months -> "Last 6 months"
    DashboardDateRange.Last12Months -> "Last 12 months"
    DashboardDateRange.YearToDate -> "Year to date"
    DashboardDateRange.AllTime -> "All time"
    is DashboardDateRange.Custom ->
        "${range.start.format(PERIOD_LABEL_DATE_FORMAT)} – ${range.end.format(PERIOD_LABEL_DATE_FORMAT)}"
}

/**
 * Reads the selected period's money flow and turns it into plain-English
 * observations: trend vs. the prior period, savings rate, the leading
 * spending category, a category that spiked, and the single biggest expense.
 * Each check is independent and only fires when it has enough signal (e.g. no
 * [SmartInsight.SpendTrend] without a non-zero prior period to compare to).
 *
 * Pure and top-level so it runs under plain JVM unit tests, mirroring
 * [computeDashboardStats].
 */
internal fun computeSmartInsights(
    periodIncome: Double,
    periodSpend: Double,
    categorySpends: List<CategorySpend>,
    previousPeriodSpend: Double?,
    previousCategorySpends: List<CategorySpend>,
    biggestExpense: Pair<String, Double>?,
    unusualCharges: List<SpendAnomalyDetector.Anomaly> = emptyList(),
): List<SmartInsight> {
    val insights = mutableListOf<SmartInsight>()
    val net = periodIncome - periodSpend

    if (previousPeriodSpend != null && previousPeriodSpend > 0.0) {
        val change = (periodSpend - previousPeriodSpend) / previousPeriodSpend * 100.0
        if (kotlin.math.abs(change) >= 1.0) {
            insights += SmartInsight.SpendTrend(
                percentChange = change,
                tone = if (change > 0) InsightTone.NEGATIVE else InsightTone.POSITIVE,
            )
        }
    }

    if (periodIncome > 0.0) {
        val rate = net / periodIncome * 100.0
        insights += SmartInsight.SavingsRate(rate, if (rate >= 0.0) InsightTone.POSITIVE else InsightTone.NEGATIVE)
    } else if (periodSpend > 0.0) {
        insights += SmartInsight.NoIncomeSpend(periodSpend)
    }

    categorySpends.firstOrNull()?.let { top ->
        val total = categorySpends.sumOf { it.amount }
        if (total > 0.0) {
            insights += SmartInsight.TopCategory(top.category, top.amount, top.amount / total * 100.0)
        }
    }

    if (previousCategorySpends.isNotEmpty()) {
        val previousByCategory = previousCategorySpends.associateBy { it.category }
        categorySpends
            .mapNotNull { current ->
                val previous = previousByCategory[current.category]?.amount ?: 0.0
                val change = current.amount - previous
                if (previous > 0.0 && current.amount >= previous * 1.25 && change >= 1.0) {
                    current to (change / previous * 100.0)
                } else {
                    null
                }
            }
            .maxByOrNull { (_, percentChange) -> percentChange }
            ?.let { (spend, percentChange) ->
                insights += SmartInsight.CategorySpike(spend.category, spend.amount, percentChange)
            }
    }

    biggestExpense?.let { (merchant, amount) ->
        if (amount > 0.0) insights += SmartInsight.BiggestExpense(merchant, amount)
    }

    unusualCharges.forEach { a ->
        insights += SmartInsight.UnusualCharge(
            merchant = a.merchant,
            amount = a.amount.toDouble(),
            usual = a.usual.toDouble(),
            ratio = a.ratio,
            currencyCode = a.currencyCode,
        )
    }

    return insights
}

/**
 * Six months of income/spend plus the selected period's per-person and
 * per-category spend and smart-analysis insights, all in the base currency
 * (unconvertible amounts are skipped, not guessed). The 6-month trend chart
 * always covers the trailing 6 real months ending at [thisMonth], independent
 * of [dateRange] — only the period-scoped fields (income/spend/category/
 * person/insights) follow the selected filter.
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
    categories: List<Category>,
    includeExcluded: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    thisMonth: YearMonth = YearMonth.now(zone),
    dateRange: DashboardDateRange = DashboardDateRange.ThisMonth,
): DashboardStats {
    // includeExcluded is the "show the whole ledger" view: plain debits vs
    // credits, kinds and the exclusion flag ignored (see LedgerMath.raw).
    val raw = includeExcluded
    val counted = transactions.filter { LedgerMath.effect(it, raw) != LedgerMath.Effect.NONE }
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
            when (LedgerMath.effect(tx, raw)) {
                LedgerMath.Effect.INCOME -> income += value
                LedgerMath.Effect.SPEND -> spend += value
                LedgerMath.Effect.REFUND -> spend -= value
                LedgerMath.Effect.NONE -> Unit
            }
        }
        MonthlyFlow(
            label = month.month.getDisplayName(TextStyle.SHORT, Locale.US),
            income = income,
            spend = spend,
        )
    }

    fun inWindow(tx: Transaction, window: DateWindow?): Boolean =
        window == null || window.contains(tx.timestampMillis, zone)

    // Refunds net against their category; a category refunded down to zero
    // or below drops out rather than showing a negative slice.
    fun categorySpendsOf(txs: List<Transaction>): List<CategorySpend> = txs
        .filter { LedgerMath.affectsSpend(it, raw) }
        .groupBy { resolveCategory(it.categoryId, categories) }
        .mapNotNull { (category, catTxs) ->
            val total = LedgerMath.netSpend(catTxs, raw, ::inBase)
            if (total <= 0.0) null else CategorySpend(category, total)
        }
        .sortedByDescending { it.amount }

    val window = resolveDashboardWindow(dateRange, thisMonth)
    val periodTx = counted.filter { inWindow(it, window) }

    val periodIncome = LedgerMath.income(periodTx, raw, ::inBase)
    val periodSpend = LedgerMath.netSpend(periodTx, raw, ::inBase)

    val personSpends = periodTx
        .filter { LedgerMath.affectsSpend(it, raw) }
        .groupBy { it.person?.trim()?.takeIf(String::isNotEmpty) ?: "Unassigned" }
        .mapNotNull { (person, txs) ->
            val total = LedgerMath.netSpend(txs, raw, ::inBase)
            if (total <= 0.0) null else PersonSpend(person, total)
        }
        .sortedByDescending { it.amount }

    val categorySpends = categorySpendsOf(periodTx)

    val priorMonths = monthly.dropLast(1).filter { it.income != 0.0 || it.spend != 0.0 }.takeLast(3)
    val avgNet = if (priorMonths.isEmpty()) 0.0 else priorMonths.sumOf { it.income - it.spend } / priorMonths.size

    // "Previous period": the same number of days immediately before the
    // selected window, for period-over-period trend insights. Undefined
    // (null) when the selected range is unbounded (All time).
    val previousWindow = window?.let { w ->
        val days = ChronoUnit.DAYS.between(w.start, w.end) + 1
        DateWindow(w.start.minusDays(days), w.start.minusDays(1))
    }
    val previousPeriodTx = previousWindow?.let { pw -> counted.filter { inWindow(it, pw) } }.orEmpty()
    val previousPeriodSpend = previousWindow?.let {
        LedgerMath.netSpend(previousPeriodTx, raw, ::inBase)
    }
    val previousCategorySpends = categorySpendsOf(previousPeriodTx)

    val biggestExpense = periodTx
        .filter { LedgerMath.isSpend(it, raw) }
        .mapNotNull { tx -> inBase(tx)?.let { tx to it } }
        .maxByOrNull { (_, amount) -> amount }
        ?.let { (tx, amount) ->
            val merchant = tx.merchant?.trim()?.takeIf(String::isNotEmpty)
                ?: tx.sender?.trim()?.takeIf(String::isNotEmpty)
                ?: "Uncategorized"
            merchant to amount
        }

    // Judged against full history, but only within a bounded period, and
    // never in the raw-ledger view (which ignores kinds and exclusions).
    val unusualCharges = if (raw || window == null) {
        emptyList()
    } else {
        SpendAnomalyDetector.scan(transactions, window, zone, max = 2)
    }

    val insights = computeSmartInsights(
        periodIncome = periodIncome,
        periodSpend = periodSpend,
        categorySpends = categorySpends,
        previousPeriodSpend = previousPeriodSpend,
        previousCategorySpends = previousCategorySpends,
        biggestExpense = biggestExpense,
        unusualCharges = unusualCharges,
    )

    return DashboardStats(
        monthly = monthly,
        personSpends = personSpends,
        categorySpends = categorySpends,
        categorySlices = categorySlicesFrom(categorySpends),
        avgMonthlyNet = avgNet,
        periodLabel = dashboardPeriodLabel(dateRange),
        periodIncome = periodIncome,
        periodSpend = periodSpend,
        insights = insights,
    )
}

/** Month-end fiat net worth, oldest first; see [computeNetWorthTrend]. */
data class NetWorthTrend(
    val months: List<YearMonth>,
    /** Base-currency net worth at each month's end; null before the ledger's history starts. The last is now. */
    val values: List<Double?>,
    /** Some account had no exchange rate at some point, so totals read low. */
    val approximate: Boolean,
) {
    val current: Double? get() = values.lastOrNull()

    /** Change from [monthsBack] month-ends ago to now; null when either end is unknown. */
    fun changeSince(monthsBack: Int): Double? {
        val now = current ?: return null
        val past = values.getOrNull(values.size - 1 - monthsBack) ?: return null
        return now - past
    }

    /** At least two known points -- anything less isn't a trend. */
    val hasTrend: Boolean get() = values.count { it != null } >= 2
}

/**
 * Net worth at each of the last [months] month-ends, rebuilt by replaying the
 * ledger ([BalanceHistory]) -- no snapshots needed. Mirrors Home's figure
 * exactly: the same balances, the same [computeAccountsPresentation] total
 * (honouring "exclude credit cards from total"), so the last point is the
 * number on Home. Two deliberate simplifications, labelled on the card:
 * every month is converted at today's rates, and live Binance crypto (which
 * has no history) is left out. Months before the oldest kept transaction are
 * null: with retention pruning on, earlier rows live only in the opening
 * balance and can't be replayed.
 *
 * Pure and top-level so it's JVM-testable.
 */
internal fun computeNetWorthTrend(
    accounts: List<Account>,
    transactions: List<Transaction>,
    rates: List<ExchangeRate>,
    baseCurrency: String,
    excludeCreditCardFromTotal: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
    thisMonth: YearMonth = YearMonth.now(zone),
    months: Int = 12,
): NetWorthTrend {
    val ratesFromBase = rates
        .filter { it.baseCode.equals(baseCurrency, ignoreCase = true) }
        .associate { it.quoteCode.uppercase() to it.rate.toDouble() }
    val start = BalanceHistory.historyStart(transactions, zone)
    var approximate = false
    val ends = BalanceHistory.monthEnds(thisMonth, months, zone)
    val values = ends.map { (month, endMillis) ->
        if (start == null || month < start) {
            if (month != thisMonth) return@map null
        }
        val balances = BalanceHistory.balancesAt(accounts, transactions, endMillis)
        val rows = accounts.map { account ->
            val balance = balances.getValue(account.id).toDouble()
            AccountRow(account, balance, CurrencyMath.toBase(balance, account.currencyCode, baseCurrency, ratesFromBase))
        }
        val presentation = computeAccountsPresentation(
            accountRows = rows,
            hideCreditCardBalance = false,
            excludeCreditCardFromTotal = excludeCreditCardFromTotal,
        )
        if (presentation.hasUnconvertedFiat) approximate = true
        presentation.fiatTotalInBase
    }
    return NetWorthTrend(ends.map { it.first }, values, approximate)
}

/**
 * The permissions the capture channels hold, as checked at [checkedAt].
 * [checkedAt] makes every resume a new value, so the health re-evaluates.
 */
internal data class CaptureAccessSnapshot(
    val notificationAccess: Boolean,
    val sms: Boolean,
    val batteryExempt: Boolean,
    val checkedAt: Long,
)

/**
 * Feeds [CaptureHealthEvaluator] from the ledger: only live captures (SMS and
 * push) say anything about whether capture works -- a pasted backlog, a manual
 * entry or a demo row never does.
 */
internal fun computeCaptureHealth(
    transactions: List<Transaction>,
    access: CaptureAccessSnapshot,
    lastListenerEventAt: Long,
    listenerConnectedAt: Long,
    listenerDisconnectedAt: Long,
    snoozedUntil: Long,
    now: Long,
): CaptureHealthEvaluator.Health = CaptureHealthEvaluator.evaluate(
    CaptureHealthEvaluator.Inputs(
        notificationAccess = access.notificationAccess,
        smsPermission = access.sms,
        batteryExempt = access.batteryExempt,
        lastListenerEventAt = lastListenerEventAt,
        listenerConnectedAt = listenerConnectedAt,
        listenerDisconnectedAt = listenerDisconnectedAt,
        capturedAt = transactions
            .filter { !it.isDemo && (it.source == TransactionSource.SMS || it.source == TransactionSource.PUSH_NOTIFICATION) }
            .map { it.createdAt },
        snoozedUntil = snoozedUntil,
        now = now,
    ),
)

/** Account types counted as "liquid" for [computeFinancialHealth]'s cash-runway and safe-to-spend math. */
private val LIQUID_ACCOUNT_TYPES = setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH)

/**
 * This calendar month's Financial Health Summary Card: savings rate, cash
 * runway and net cash flow (see [FinancialHealthEngine]), plus a "safe to
 * spend until payday" forecast (see [CashFlowForecaster]) once a payday can
 * be determined (auto-detected recurring income, or [manualPaydayOfMonth]).
 * Mandatory bills reserved ahead of payday combine the active, non-dismissed
 * subscriptions and the manually tracked [com.xemmy.mmbank.domain.model.Bill]s
 * due in that window -- MMbank's two notions of a recurring committed charge,
 * one auto-detected and one hand-entered.
 *
 * Pure and top-level so it's JVM-testable; [zone] and [today] are injectable
 * for determinism and default to the device values.
 */
internal fun computeFinancialHealth(
    accounts: List<AccountRow>,
    transactions: List<Transaction>,
    subscriptions: List<com.xemmy.mmbank.domain.model.Subscription>,
    bills: List<com.xemmy.mmbank.domain.model.Bill>,
    rates: List<ExchangeRate>,
    baseCurrency: String,
    fixedMonthlyExpenses: Double,
    manualPaydayOfMonth: Int?,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): FinancialHealthState {
    val ratesFromBase = rates
        .filter { it.baseCode.equals(baseCurrency, ignoreCase = true) }
        .associate { it.quoteCode.uppercase() to it.rate.toDouble() }

    fun inBase(tx: Transaction): Double? =
        CurrencyMath.toBase(tx.amount.toDouble(), tx.currencyCode, baseCurrency, ratesFromBase)

    val monthStart = today.withDayOfMonth(1)
    val monthStartMillis = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
    val nextMonthStartMillis = monthStart.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthTx = transactions.filter {
        it.timestampMillis >= monthStartMillis && it.timestampMillis < nextMonthStartMillis
    }
    val income = LedgerMath.income(monthTx, value = ::inBase)
    val expenses = LedgerMath.netSpend(monthTx, value = ::inBase)

    val liquidBalance = accounts
        .filter { it.account.type in LIQUID_ACCOUNT_TYPES }
        .sumOf { it.balanceInBase ?: 0.0 }

    val metrics = FinancialHealthEngine.calculate(
        FinancialHealthEngine.HealthInputs(
            periodIncome = income,
            periodExpenses = expenses,
            liquidBalance = liquidBalance,
            averageMonthlyFixedExpenses = fixedMonthlyExpenses,
        )
    )

    val pattern = CashFlowForecaster.detectIncomePattern(transactions, baseCurrency)
    val nextPayday = CashFlowForecaster.projectNextPayday(pattern, manualPaydayOfMonth, today, zone)
    val cashFlow = nextPayday?.let { payday ->
        fun isDueBeforePayday(dueDateMillis: Long): Boolean {
            val dueDate = Instant.ofEpochMilli(dueDateMillis).atZone(zone).toLocalDate()
            return !dueDate.isBefore(today) && !dueDate.isAfter(payday)
        }

        val subscriptionsDue = subscriptions
            .filter { !it.dismissed }
            .filter { isDueBeforePayday(it.nextDueDateMillis) }
            .sumOf { sub ->
                CurrencyMath.toBase(sub.averageAmount.toDouble(), sub.currencyCode, baseCurrency, ratesFromBase) ?: 0.0
            }
        val billsDueManual = bills
            .filter { isDueBeforePayday(it.nextDueDateMillis) }
            .sumOf { bill ->
                CurrencyMath.toBase(bill.amount.toDouble(), bill.currencyCode, baseCurrency, ratesFromBase) ?: 0.0
            }
        val billsDue = subscriptionsDue + billsDueManual
        CashFlowForecaster.computeSafeToSpend(
            currentLiquidBalance = java.math.BigDecimal.valueOf(liquidBalance),
            mandatoryBillsDueBeforePayday = java.math.BigDecimal.valueOf(billsDue),
            nextPayday = payday,
            today = today,
            zone = zone,
        )
    }

    return FinancialHealthState(metrics = metrics, cashFlow = cashFlow)
}
