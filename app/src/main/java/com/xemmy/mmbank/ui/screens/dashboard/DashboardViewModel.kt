package com.xemmy.mmbank.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.di.AppContainer
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

/** One donut-chart slice: a category (or the "Other" rollup) with its rank color. */
data class CategorySlice(val name: String, val amount: Double, val color: Long)

data class DashboardStats(
    /** Oldest -> newest, always six entries. */
    val monthly: List<MonthlyFlow> = emptyList(),
    val personSpends: List<PersonSpend> = emptyList(),
    val categorySpends: List<CategorySpend> = emptyList(),
    /** [categorySpends] capped to the top 4 + an "Other" rollup, for the donut chart. */
    val categorySlices: List<CategorySlice> = emptyList(),
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
    /** True when crypto's value is deliberately left out of [netWorthInBase] (Settings toggle). */
    val cryptoExcludedFromTotal: Boolean = false,
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
        val hideCryptoBalance = container.preferences.hideCryptoBalance
        val excludeCryptoFromTotal = container.preferences.excludeCryptoFromTotal
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

        val accountsResult = computeAccountsPresentation(
            rows = accountRows,
            hideCryptoBalance = hideCryptoBalance,
            excludeCryptoFromTotal = excludeCryptoFromTotal,
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

        val hasUnconverted = accountsResult.hasUnconvertedFiat || cryptoResult.hasUnconvertedCrypto

        DashboardUiState(
            baseCurrency = base,
            accounts = accountsResult.visibleRows,
            cryptoBalances = cryptoResult.visibleAssets,
            cryptoWallets = cryptoResult.visibleWallets,
            cryptoTotalUsd = cryptoResult.cryptoTotalUsd,
            recent = recent,
            netWorthInBase = accountsResult.fiatInBase + (cryptoResult.cryptoInBase ?: 0.0),
            hasUnconvertedAmounts = hasUnconverted,
            cryptoExcludedFromTotal = excludeCryptoFromTotal,
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
                hideBalances = hidden,
                refreshing = meta.refreshing,
                message = meta.message ?: state.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    /** Flips analytics between "clean" (default) and raw-ledger figures. */
    fun toggleShowAllLedgerTransactions() {
        showAllLedgerTransactions.value = !showAllLedgerTransactions.value
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
    val visibleRows: List<AccountRow>,
    val fiatInBase: Double,
    val hasUnconvertedFiat: Boolean,
)

/**
 * A manually-added account of type [AccountType.CRYPTO_SPOT] (e.g. a
 * "Binance" account entered by hand, distinct from the Binance-API-synced
 * holdings in [computeCryptoPresentation]) is also "crypto" as far as the
 * hide/exclude Settings toggles are concerned. [hideCryptoBalance] drops it
 * from the Dashboard's Accounts list; [excludeCryptoFromTotal] drops its
 * balance from the net-worth total — independently, same as for synced
 * crypto. Scoped to the Dashboard only: the Accounts tab is the
 * manage-everything screen and stays unfiltered (same precedent as
 * hideZeroBalances never touching it either).
 */
internal fun computeAccountsPresentation(
    rows: List<AccountRow>,
    hideCryptoBalance: Boolean,
    excludeCryptoFromTotal: Boolean,
): AccountsPresentation {
    fun isCrypto(row: AccountRow) = row.account.type == AccountType.CRYPTO_SPOT
    val visibleRows = if (hideCryptoBalance) rows.filterNot(::isCrypto) else rows
    val countedRows = if (excludeCryptoFromTotal) rows.filterNot(::isCrypto) else rows
    return AccountsPresentation(
        visibleRows = visibleRows,
        fiatInBase = countedRows.mapNotNull { it.balanceInBase }.sum(),
        hasUnconvertedFiat = countedRows.any { it.balanceInBase == null },
    )
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
        categorySlices = categorySlicesFrom(categorySpends),
        avgMonthlyNet = avgNet,
    )
}
