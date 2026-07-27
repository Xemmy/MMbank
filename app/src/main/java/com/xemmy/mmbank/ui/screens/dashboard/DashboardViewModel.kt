package com.xemmy.mmbank.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.intelligence.AiWeeklyDigestGenerator
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.WalletBalance
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

/** Selected-period spend attributed to one person. */
data class PersonSpend(val person: String, val amount: Double)

/** Selected-period spend grouped by spending category. */
data class CategorySpend(val category: SpendingCategory, val amount: Double)

/** One donut-chart slice: a category (or the "Other" rollup) with its rank color. */
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
    data class TopCategory(val category: SpendingCategory, val amount: Double, val sharePercent: Double) : SmartInsight {
        override val tone get() = InsightTone.NEUTRAL
    }

    /** A category whose spend jumped sharply vs. the preceding period. */
    data class CategorySpike(val category: SpendingCategory, val amount: Double, val percentChange: Double) : SmartInsight {
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
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private companion object {
        /** Below this, a holding rounds to "$0.00" at 2-decimal money formatting. */
        const val MIN_VISIBLE_USD = 0.005
    }

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val showAllLedgerTransactions = MutableStateFlow(false)
    private val dateRange = MutableStateFlow<DashboardDateRange>(DashboardDateRange.ThisMonth)

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
        val hideCryptoBalance = container.preferences.hideCryptoBalance
        val excludeCryptoFromTotal = container.preferences.excludeCryptoFromTotal
        val hideCreditCardBalance = container.preferences.hideCreditCardBalance
        val excludeCreditCardFromTotal = container.preferences.excludeCreditCardFromTotal
        val ratesFromBase = rates
            .filter { it.baseCode.equals(base, ignoreCase = true) }
            .associate { it.quoteCode.uppercase() to it.rate.toDouble() }

        val allAccountRows = accounts.map { account ->
            val balance = account.openingBalance.toDouble() + (netByAccount[account.id] ?: 0.0)
            AccountRow(
                account = account,
                balance = balance,
                balanceInBase = CurrencyMath.toBase(balance, account.currencyCode, base, ratesFromBase),
            )
        }

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
            cryptoBalances = cryptoResult.visibleAssets,
            cryptoWallets = cryptoResult.visibleWallets,
            cryptoTotalUsd = cryptoResult.cryptoTotalUsd,
            recent = recent,
            netWorthInBase = fiatInBase + (cryptoResult.cryptoInBase ?: 0.0),
            hasUnconvertedAmounts = hasUnconverted,
            cryptoExcludedFromTotal = excludeCryptoFromTotal,
            creditCardExcludedFromTotal = excludeCreditCardFromTotal,
        )
    }.recoverAndRetry("DashboardViewModel", container) { e ->
        // Never let a bad row (or a rates/DB hiccup) take the whole app down —
        // show a dismissible message, then resubscribe so balances stay live.
        DashboardUiState(message = "Dashboard couldn't load: ${e.message}")
    }

    private val senderLabels = container.senderLabelRepository.observeLabels()
        .recoverAndRetry("DashboardViewModel.senderLabels", container) { emptyMap() }

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

    private val statsFlow = combine(
        container.transactionRepository.observeTransactions(),
        container.ratesRepository.observeRates(),
        showAllLedgerTransactions,
        dateRange,
    ) { transactions, rates, showAll, range ->
        computeDashboardStats(transactions, rates, container.preferences.defaultCurrency, showAll, dateRange = range)
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

    val uiState: StateFlow<DashboardUiState> =
        combine(
            ledgerState,
            senderLabels,
            statsFlow,
            uiMeta,
            container.hideBalances,
        ) { state, labels, stats, meta, hidden ->
            state.copy(
                senderLabels = labels,
                stats = stats,
                goal = meta.prefs.goal,
                showAllLedgerTransactions = meta.showAllLedger,
                dateRange = meta.dateRange,
                hideBalances = hidden,
                refreshing = meta.refreshing,
                message = meta.message ?: state.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    /** Flips analytics between "clean" (default) and raw-ledger figures. */
    fun toggleShowAllLedgerTransactions() {
        showAllLedgerTransactions.value = !showAllLedgerTransactions.value
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

/** Rank-ordered donut palette: top 4 categories, then grey for the "Other" rollup. */
private val SLICE_PALETTE = listOf(0xFF4ECDC4L, 0xFFE07A5FL, 0xFFF2CC8FL, 0xFF81B29AL)
private const val OTHER_SLICE_COLOR = 0xFF6C757DL

/**
 * Caps [categorySpends] (already sorted descending) to the top 4 entries and
 * rolls everything past that into one "Other" slice, for the donut chart.
 * Colors are assigned by rank, not by category identity, so the same 5-color
 * palette works regardless of which categories happen to lead this month.
 */
internal fun categorySlicesFrom(categorySpends: List<CategorySpend>): List<CategorySlice> {
    val top = categorySpends.take(4)
    val other = categorySpends.drop(4).sumOf { it.amount }
    val slices = top.mapIndexed { index, spend ->
        CategorySlice(spend.category.label, spend.amount, SLICE_PALETTE[index])
    }
    return if (other > 0.0) slices + CategorySlice("Other", other, OTHER_SLICE_COLOR) else slices
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
    val visibleAccounts = if (hideCreditCardBalance) {
        accountRows.filter { it.account.type != AccountType.CREDIT_CARD }
    } else {
        accountRows
    }
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
    includeExcluded: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    thisMonth: YearMonth = YearMonth.now(zone),
    dateRange: DashboardDateRange = DashboardDateRange.ThisMonth,
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

    fun inWindow(tx: Transaction, window: DateWindow?): Boolean =
        window == null || window.contains(tx.timestampMillis, zone)

    fun categorySpendsOf(txs: List<Transaction>): List<CategorySpend> = txs
        .filter { it.direction == TransactionDirection.DEBIT }
        .groupBy { it.spendingCategory }
        .mapNotNull { (category, catTxs) ->
            val total = catTxs.sumOf { inBase(it) ?: 0.0 }
            if (total <= 0.0) null else CategorySpend(category, total)
        }
        .sortedByDescending { it.amount }

    val window = resolveDashboardWindow(dateRange, thisMonth)
    val periodTx = counted.filter { inWindow(it, window) }

    val periodIncome = periodTx.filter { it.direction == TransactionDirection.CREDIT }.sumOf { inBase(it) ?: 0.0 }
    val periodSpend = periodTx.filter { it.direction == TransactionDirection.DEBIT }.sumOf { inBase(it) ?: 0.0 }

    val personSpends = periodTx
        .filter { it.direction == TransactionDirection.DEBIT }
        .groupBy { it.person?.trim()?.takeIf(String::isNotEmpty) ?: "Unassigned" }
        .mapNotNull { (person, txs) ->
            val total = txs.sumOf { inBase(it) ?: 0.0 }
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
        previousPeriodTx.filter { tx -> tx.direction == TransactionDirection.DEBIT }.sumOf { inBase(it) ?: 0.0 }
    }
    val previousCategorySpends = categorySpendsOf(previousPeriodTx)

    val biggestExpense = periodTx
        .filter { it.direction == TransactionDirection.DEBIT }
        .mapNotNull { tx -> inBase(tx)?.let { tx to it } }
        .maxByOrNull { (_, amount) -> amount }
        ?.let { (tx, amount) ->
            val merchant = tx.merchant?.trim()?.takeIf(String::isNotEmpty)
                ?: tx.sender?.trim()?.takeIf(String::isNotEmpty)
                ?: "Uncategorized"
            merchant to amount
        }

    val insights = computeSmartInsights(
        periodIncome = periodIncome,
        periodSpend = periodSpend,
        categorySpends = categorySpends,
        previousPeriodSpend = previousPeriodSpend,
        previousCategorySpends = previousCategorySpends,
        biggestExpense = biggestExpense,
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
