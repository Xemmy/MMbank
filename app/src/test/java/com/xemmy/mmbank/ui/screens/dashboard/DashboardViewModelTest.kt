package com.xemmy.mmbank.ui.screens.dashboard

import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.AssetBalance
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.model.WalletBalance
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [computeDashboardStats] is the analytics core behind the Dashboard tab.
 * Rows flagged [Transaction.excludeFromStats] (balance corrections, refund
 * adjustments) must vanish from every aggregate by default, and count again
 * when the "raw ledger" toggle asks for unfiltered figures.
 */
class DashboardViewModelTest {

    private val zone = ZoneId.of("UTC")
    private val thisMonth = YearMonth.of(2026, 6)
    private val noRates = emptyList<ExchangeRate>()

    private fun tx(
        amount: String,
        direction: TransactionDirection,
        excluded: Boolean = false,
        category: SpendingCategory = SpendingCategory.UNCATEGORIZED,
        person: String? = null,
        month: YearMonth = thisMonth,
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = "AED",
        direction = direction,
        source = TransactionSource.MANUAL,
        timestampMillis = month.atDay(15).atStartOfDay(zone).toInstant().toEpochMilli(),
        spendingCategory = category,
        person = person,
        excludeFromStats = excluded,
    )

    private fun stats(transactions: List<Transaction>, includeExcluded: Boolean) =
        computeDashboardStats(
            transactions = transactions,
            rates = noRates,
            baseCurrency = "AED",
            includeExcluded = includeExcluded,
            zone = zone,
            thisMonth = thisMonth,
        )

    // A mixed month: real income + spend, plus a flagged balance-correction
    // credit and a flagged refund-adjustment debit that must not skew stats.
    private val mixed = listOf(
        tx("1000.00", TransactionDirection.CREDIT),
        tx("200.00", TransactionDirection.DEBIT, category = SpendingCategory.GROCERIES),
        tx("50.00", TransactionDirection.DEBIT, category = SpendingCategory.DINING),
        tx("500.00", TransactionDirection.CREDIT, excluded = true),
        tx("75.00", TransactionDirection.DEBIT, excluded = true, category = SpendingCategory.TRANSFERS),
    )

    @Test
    fun `toggle off - flagged rows are omitted from income, spend and net`() {
        val month = stats(mixed, includeExcluded = false).monthly.last()
        assertEquals(1000.0, month.income, 1e-9)
        assertEquals(250.0, month.spend, 1e-9)
        assertEquals(750.0, month.income - month.spend, 1e-9)
    }

    @Test
    fun `toggle on - flagged rows are counted in income, spend and net`() {
        val month = stats(mixed, includeExcluded = true).monthly.last()
        assertEquals(1500.0, month.income, 1e-9)
        assertEquals(325.0, month.spend, 1e-9)
        assertEquals(1175.0, month.income - month.spend, 1e-9)
    }

    @Test
    fun `toggle off - a flagged transaction's category contributes nothing`() {
        val spends = stats(mixed, includeExcluded = false).categorySpends
        assertNull(spends.firstOrNull { it.category == SpendingCategory.TRANSFERS })
        assertEquals(
            200.0,
            spends.first { it.category == SpendingCategory.GROCERIES }.amount,
            1e-9,
        )
    }

    @Test
    fun `toggle on - a flagged transaction's category counts`() {
        val spends = stats(mixed, includeExcluded = true).categorySpends
        assertEquals(
            75.0,
            spends.first { it.category == SpendingCategory.TRANSFERS }.amount,
            1e-9,
        )
    }

    @Test
    fun `toggle off - flagged rows drop out of person spend too`() {
        val transactions = listOf(
            tx("100.00", TransactionDirection.DEBIT, person = "Amir"),
            tx("40.00", TransactionDirection.DEBIT, person = "Amir", excluded = true),
        )
        val people = stats(transactions, includeExcluded = false).personSpends
        assertEquals(100.0, people.first { it.person == "Amir" }.amount, 1e-9)
    }

    @Test
    fun `excluded rows in prior months do not skew the average monthly net`() {
        val transactions = listOf(
            tx("300.00", TransactionDirection.CREDIT, month = thisMonth.minusMonths(1)),
            tx("9999.00", TransactionDirection.CREDIT, excluded = true, month = thisMonth.minusMonths(1)),
        )
        assertEquals(300.0, stats(transactions, includeExcluded = false).avgMonthlyNet, 1e-9)
    }

    @Test
    fun `all-excluded month reads as zero activity by default`() {
        val transactions = listOf(
            tx("500.00", TransactionDirection.CREDIT, excluded = true),
            tx("75.00", TransactionDirection.DEBIT, excluded = true),
        )
        val result = stats(transactions, includeExcluded = false)
        val month = result.monthly.last()
        assertEquals(0.0, month.income, 1e-9)
        assertEquals(0.0, month.spend, 1e-9)
        assertTrue(result.categorySpends.isEmpty())
        assertTrue(result.personSpends.isEmpty())
    }

    @Test
    fun `categorySlicesFrom caps at top 4 and rolls the rest into Other`() {
        val spends = listOf(
            CategorySpend(SpendingCategory.GROCERIES, 600.0),
            CategorySpend(SpendingCategory.DINING, 400.0),
            CategorySpend(SpendingCategory.TRANSPORT, 300.0),
            CategorySpend(SpendingCategory.SHOPPING, 200.0),
            CategorySpend(SpendingCategory.FUEL, 80.0),
            CategorySpend(SpendingCategory.ENTERTAINMENT, 20.0),
        )
        val slices = categorySlicesFrom(spends)

        assertEquals(5, slices.size)
        assertEquals(listOf("Groceries", "Dining", "Transport", "Shopping", "Other"), slices.map { it.name })
        assertEquals(100.0, slices.last().amount, 1e-9)
        assertEquals(
            listOf(0xFF4ECDC4L, 0xFFE07A5FL, 0xFFF2CC8FL, 0xFF81B29AL, 0xFF6C757DL),
            slices.map { it.color },
        )
    }

    @Test
    fun `categorySlicesFrom leaves no Other slice when there are 4 or fewer categories`() {
        val spends = listOf(
            CategorySpend(SpendingCategory.GROCERIES, 600.0),
            CategorySpend(SpendingCategory.DINING, 400.0),
            CategorySpend(SpendingCategory.TRANSPORT, 300.0),
        )
        val slices = categorySlicesFrom(spends)

        assertEquals(3, slices.size)
        assertEquals(listOf("Groceries", "Dining", "Transport"), slices.map { it.name })
        assertTrue(slices.none { it.name == "Other" })
    }

    @Test
    fun `toggle off - categorySlices mirrors categorySpends for the mixed fixture`() {
        val result = stats(mixed, includeExcluded = false)
        assertEquals(2, result.categorySlices.size)
        assertEquals(
            result.categorySpends.map { it.category.label to it.amount },
            result.categorySlices.map { it.name to it.amount },
        )
        assertTrue(result.categorySlices.none { it.name == "Other" })
    }

    @Test
    fun `default date range scopes period figures to this month, same as the old this-month behavior`() {
        val result = stats(mixed, includeExcluded = false)
        assertEquals("This month", result.periodLabel)
        assertEquals(1000.0, result.periodIncome, 1e-9)
        assertEquals(250.0, result.periodSpend, 1e-9)
    }

    @Test
    fun `Last3Months pulls in spend from two months back that ThisMonth would miss`() {
        val transactions = listOf(
            tx("100.00", TransactionDirection.DEBIT, category = SpendingCategory.GROCERIES),
            tx("60.00", TransactionDirection.DEBIT, category = SpendingCategory.DINING, month = thisMonth.minusMonths(2)),
            // Outside the 3-month window entirely.
            tx("500.00", TransactionDirection.DEBIT, month = thisMonth.minusMonths(3)),
        )
        val result = computeDashboardStats(
            transactions = transactions,
            rates = noRates,
            baseCurrency = "AED",
            zone = zone,
            thisMonth = thisMonth,
            dateRange = DashboardDateRange.Last3Months,
        )
        assertEquals("Last 3 months", result.periodLabel)
        assertEquals(160.0, result.periodSpend, 1e-9)
    }

    @Test
    fun `AllTime has no previous period, so no spend-trend insight fires`() {
        val transactions = listOf(
            tx("100.00", TransactionDirection.DEBIT, month = thisMonth.minusMonths(20)),
            tx("200.00", TransactionDirection.CREDIT, month = thisMonth.minusMonths(20)),
        )
        val result = computeDashboardStats(
            transactions = transactions,
            rates = noRates,
            baseCurrency = "AED",
            zone = zone,
            thisMonth = thisMonth,
            dateRange = DashboardDateRange.AllTime,
        )
        assertEquals("All time", result.periodLabel)
        assertEquals(100.0, result.periodSpend, 1e-9)
        assertTrue(result.insights.none { it is SmartInsight.SpendTrend })
    }

    @Test
    fun `a period with spend but no income yields a NoIncomeSpend insight, not a savings rate`() {
        val transactions = listOf(tx("50.00", TransactionDirection.DEBIT))
        val result = stats(transactions, includeExcluded = false)
        assertTrue(result.insights.any { it is SmartInsight.NoIncomeSpend })
        assertTrue(result.insights.none { it is SmartInsight.SavingsRate })
    }

    @Test
    fun `the biggest debit in the period surfaces as a BiggestExpense insight`() {
        val big = Transaction(
            amount = BigDecimal("300.00"),
            currencyCode = "AED",
            direction = TransactionDirection.DEBIT,
            source = TransactionSource.MANUAL,
            timestampMillis = thisMonth.atDay(10).atStartOfDay(zone).toInstant().toEpochMilli(),
            merchant = "Big Mart",
            spendingCategory = SpendingCategory.SHOPPING,
        )
        val result = stats(mixed + big, includeExcluded = false)
        val biggest = result.insights.filterIsInstance<SmartInsight.BiggestExpense>().single()
        assertEquals("Big Mart", biggest.merchant)
        assertEquals(300.0, biggest.amount, 1e-9)
    }
}

/**
 * [computeCryptoPresentation] folds crypto holdings into what the Dashboard
 * shows and how much (if any) counts toward net worth. hideCryptoBalance
 * (visibility) and excludeCryptoFromTotal (accounting) are independent
 * Settings toggles — the key thing these tests pin is that neither implies
 * the other.
 */
class DashboardCryptoPresentationTest {

    private fun asset(usd: Double?, free: String = "1") = AssetBalance(
        asset = "BTC",
        free = BigDecimal(free),
        locked = BigDecimal.ZERO,
        priceUsd = usd?.let { BigDecimal(it) },
        valueUsd = usd?.let { BigDecimal(it) },
        updatedAt = 0,
    )

    private fun wallet(usd: Double?) = WalletBalance(
        walletName = "Spot",
        balanceBtc = BigDecimal.ONE,
        valueUsd = usd?.let { BigDecimal(it) },
        updatedAt = 0,
    )

    private fun presentation(
        assets: List<AssetBalance> = listOf(asset(100.0)),
        wallets: List<WalletBalance> = emptyList(),
        hideZeroBalances: Boolean = false,
        hideCryptoBalance: Boolean = false,
        excludeCryptoFromTotal: Boolean = false,
    ) = computeCryptoPresentation(
        assets = assets,
        wallets = wallets,
        hideZeroBalances = hideZeroBalances,
        hideCryptoBalance = hideCryptoBalance,
        excludeCryptoFromTotal = excludeCryptoFromTotal,
        baseCurrency = "USD",
        ratesFromBase = emptyMap(),
        minVisibleUsd = 0.005,
    )

    @Test
    fun `hideCryptoBalance empties both visible lists regardless of holdings`() {
        val result = presentation(
            assets = listOf(asset(500.0)),
            wallets = listOf(wallet(500.0)),
            hideCryptoBalance = true,
        )
        assertTrue(result.visibleAssets.isEmpty())
        assertTrue(result.visibleWallets.isEmpty())
    }

    @Test
    fun `hideZeroBalances dust filtering still applies when crypto is not hidden`() {
        val result = presentation(
            assets = listOf(asset(500.0), asset(0.001)),
            hideZeroBalances = true,
            hideCryptoBalance = false,
        )
        assertEquals(1, result.visibleAssets.size)
        assertEquals(500.0, result.visibleAssets.first().valueUsd?.toDouble() ?: 0.0, 1e-9)
    }

    @Test
    fun `excludeCryptoFromTotal nulls cryptoInBase and never flags unconverted`() {
        val result = presentation(
            assets = listOf(asset(null)), // unpriced — would normally flag "approximate"
            excludeCryptoFromTotal = true,
        )
        assertNull(result.cryptoInBase)
        assertFalse(result.hasUnconvertedCrypto)
    }

    @Test
    fun `excludeCryptoFromTotal = false computes cryptoInBase normally (regression)`() {
        val result = presentation(assets = listOf(asset(250.0)), excludeCryptoFromTotal = false)
        assertEquals(250.0, result.cryptoInBase ?: 0.0, 1e-9)
    }

    @Test
    fun `an unpriced asset flags hasUnconvertedCrypto only when not excluded`() {
        val included = presentation(assets = listOf(asset(null)), excludeCryptoFromTotal = false)
        assertTrue(included.hasUnconvertedCrypto)

        val excluded = presentation(assets = listOf(asset(null)), excludeCryptoFromTotal = true)
        assertFalse(excluded.hasUnconvertedCrypto)
    }

    @Test
    fun `hiding the section does not by itself exclude crypto from the total`() {
        val result = presentation(
            assets = listOf(asset(300.0)),
            hideCryptoBalance = true,
            excludeCryptoFromTotal = false,
        )
        assertTrue(result.visibleAssets.isEmpty())
        assertEquals(300.0, result.cryptoInBase ?: 0.0, 1e-9)
    }

    @Test
    fun `excluding the total does not by itself hide the section`() {
        val result = presentation(
            assets = listOf(asset(300.0)),
            hideCryptoBalance = false,
            excludeCryptoFromTotal = true,
        )
        assertEquals(1, result.visibleAssets.size)
        assertNull(result.cryptoInBase)
    }

    @Test
    fun `wallet total wins over spot total when wallets are present`() {
        val result = presentation(assets = listOf(asset(100.0)), wallets = listOf(wallet(400.0)))
        assertEquals(400.0, result.cryptoTotalUsd, 1e-9)
        assertEquals(400.0, result.cryptoInBase ?: 0.0, 1e-9)
    }
}

/**
 * [computeAccountsPresentation] folds fiat accounts into what the Dashboard
 * shows and how much (if any) counts toward net worth. hideCreditCardBalance
 * (visibility) and excludeCreditCardFromTotal (accounting) are independent
 * Settings toggles, mirroring [computeCryptoPresentation] — the key thing
 * these tests pin is that neither implies the other.
 */
class DashboardAccountsPresentationTest {

    private fun row(
        type: AccountType,
        balanceInBase: Double?,
        name: String = "Account",
        hiddenFromDashboard: Boolean = false,
    ) = AccountRow(
        account = Account(id = 1, name = name, type = type, currencyCode = "AED", hiddenFromDashboard = hiddenFromDashboard),
        balance = balanceInBase ?: 0.0,
        balanceInBase = balanceInBase,
    )

    @Test
    fun `hideCreditCardBalance drops credit card rows from the visible list`() {
        val result = computeAccountsPresentation(
            accountRows = listOf(row(AccountType.CHECKING, 100.0), row(AccountType.CREDIT_CARD, -50.0)),
            hideCreditCardBalance = true,
            excludeCreditCardFromTotal = false,
        )
        assertEquals(1, result.visibleAccounts.size)
        assertEquals(AccountType.CHECKING, result.visibleAccounts.first().account.type)
    }

    @Test
    fun `hiding credit cards does not by itself exclude them from the total`() {
        val result = computeAccountsPresentation(
            accountRows = listOf(row(AccountType.CHECKING, 100.0), row(AccountType.CREDIT_CARD, -50.0)),
            hideCreditCardBalance = true,
            excludeCreditCardFromTotal = false,
        )
        assertEquals(50.0, result.fiatTotalInBase, 1e-9)
    }

    @Test
    fun `excluding the total does not by itself hide credit cards`() {
        val result = computeAccountsPresentation(
            accountRows = listOf(row(AccountType.CHECKING, 100.0), row(AccountType.CREDIT_CARD, -50.0)),
            hideCreditCardBalance = false,
            excludeCreditCardFromTotal = true,
        )
        assertEquals(2, result.visibleAccounts.size)
        assertEquals(100.0, result.fiatTotalInBase, 1e-9)
    }

    @Test
    fun `neither toggle set keeps everything visible and summed`() {
        val result = computeAccountsPresentation(
            accountRows = listOf(row(AccountType.CHECKING, 100.0), row(AccountType.CREDIT_CARD, -50.0)),
            hideCreditCardBalance = false,
            excludeCreditCardFromTotal = false,
        )
        assertEquals(2, result.visibleAccounts.size)
        assertEquals(50.0, result.fiatTotalInBase, 1e-9)
    }

    @Test
    fun `hiddenFromDashboard drops a row from the carousel but not from the total`() {
        val result = computeAccountsPresentation(
            accountRows = listOf(row(AccountType.CHECKING, 100.0), row(AccountType.SAVINGS, 50.0, hiddenFromDashboard = true)),
            hideCreditCardBalance = false,
            excludeCreditCardFromTotal = false,
        )
        assertEquals(1, result.visibleAccounts.size)
        assertEquals(AccountType.CHECKING, result.visibleAccounts.first().account.type)
        assertEquals(150.0, result.fiatTotalInBase, 1e-9)
    }

    @Test
    fun `an unconverted credit card row only flags hasUnconvertedFiat when not excluded`() {
        val included = computeAccountsPresentation(
            accountRows = listOf(row(AccountType.CREDIT_CARD, null)),
            hideCreditCardBalance = false,
            excludeCreditCardFromTotal = false,
        )
        assertTrue(included.hasUnconvertedFiat)

        val excluded = computeAccountsPresentation(
            accountRows = listOf(row(AccountType.CREDIT_CARD, null)),
            hideCreditCardBalance = false,
            excludeCreditCardFromTotal = true,
        )
        assertFalse(excluded.hasUnconvertedFiat)
    }
}

/**
 * [filterAccountFeed] is the pure logic behind the "Recent activity" feed's
 * account-carousel wiring: selecting a card in [com.xemmy.mmbank.ui.components.AccountCarousel]
 * scopes the feed to just that account; "All Accounts" (null) shows everything.
 */
class DashboardFeedFilterTest {

    private fun tx(id: Long, accountId: Long?, timestampMillis: Long) = Transaction(
        id = id,
        accountId = accountId,
        amount = BigDecimal("10.00"),
        currencyCode = "AED",
        direction = TransactionDirection.DEBIT,
        source = TransactionSource.MANUAL,
        timestampMillis = timestampMillis,
    )

    @Test
    fun `null accountId returns every transaction, newest first`() {
        val transactions = listOf(
            tx(1, accountId = 1, timestampMillis = 100),
            tx(2, accountId = 2, timestampMillis = 300),
            tx(3, accountId = null, timestampMillis = 200),
        )
        val feed = filterAccountFeed(transactions, accountId = null, limit = 10)
        assertEquals(listOf(2L, 3L, 1L), feed.map { it.id })
    }

    @Test
    fun `a specific accountId only returns that account's rows`() {
        val transactions = listOf(
            tx(1, accountId = 1, timestampMillis = 100),
            tx(2, accountId = 2, timestampMillis = 300),
            tx(3, accountId = 1, timestampMillis = 200),
        )
        val feed = filterAccountFeed(transactions, accountId = 1, limit = 10)
        assertEquals(listOf(3L, 1L), feed.map { it.id })
    }

    @Test
    fun `limit caps the result even when more rows match`() {
        val transactions = (1..5).map { tx(it.toLong(), accountId = 1, timestampMillis = it.toLong()) }
        val feed = filterAccountFeed(transactions, accountId = 1, limit = 2)
        assertEquals(listOf(5L, 4L), feed.map { it.id })
    }

    @Test
    fun `an account with no transactions yields an empty feed, not a crash`() {
        val transactions = listOf(tx(1, accountId = 1, timestampMillis = 100))
        assertTrue(filterAccountFeed(transactions, accountId = 99, limit = 10).isEmpty())
    }
}

/**
 * [resolveDashboardWindow] and [dashboardPeriodLabel] are the Dashboard's own
 * date-filter presets, anchored on a [YearMonth] (not a live "today") so
 * every preset's window is deterministic under test.
 */
class DashboardDateRangeTest {

    private val thisMonth = YearMonth.of(2026, 6)

    @Test
    fun `ThisMonth resolves to the calendar month`() {
        val window = resolveDashboardWindow(DashboardDateRange.ThisMonth, thisMonth)
        assertEquals(DateWindow(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)), window)
    }

    @Test
    fun `Last3Months spans this month and the two before it`() {
        val window = resolveDashboardWindow(DashboardDateRange.Last3Months, thisMonth)
        assertEquals(DateWindow(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30)), window)
    }

    @Test
    fun `Last6Months spans this month and the five before it`() {
        val window = resolveDashboardWindow(DashboardDateRange.Last6Months, thisMonth)
        assertEquals(DateWindow(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)), window)
    }

    @Test
    fun `YearToDate starts on January 1st of thisMonth's year`() {
        val window = resolveDashboardWindow(DashboardDateRange.YearToDate, thisMonth)
        assertEquals(DateWindow(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)), window)
    }

    @Test
    fun `AllTime is unbounded`() {
        assertNull(resolveDashboardWindow(DashboardDateRange.AllTime, thisMonth))
    }

    @Test
    fun `Custom normalizes a reversed start-end pair`() {
        val reversed = DashboardDateRange.Custom(LocalDate.of(2026, 6, 20), LocalDate.of(2026, 6, 10))
        val window = resolveDashboardWindow(reversed, thisMonth)
        assertEquals(DateWindow(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 20)), window)
    }

    @Test
    fun `labels are human-readable for every preset, and Custom formats its span`() {
        assertEquals("This month", dashboardPeriodLabel(DashboardDateRange.ThisMonth))
        assertEquals("Last 3 months", dashboardPeriodLabel(DashboardDateRange.Last3Months))
        assertEquals("Last 6 months", dashboardPeriodLabel(DashboardDateRange.Last6Months))
        assertEquals("Year to date", dashboardPeriodLabel(DashboardDateRange.YearToDate))
        assertEquals("All time", dashboardPeriodLabel(DashboardDateRange.AllTime))
        assertEquals(
            "Jun 1, 2026 – Jun 10, 2026",
            dashboardPeriodLabel(DashboardDateRange.Custom(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 10))),
        )
    }
}

/**
 * [computeSmartInsights] turns a period's raw totals into plain-English
 * observations. Each insight kind is independent and only fires when it has
 * enough signal — these tests pin both the firing conditions and the silence
 * when a condition isn't met.
 */
class DashboardSmartInsightsTest {

    private fun insights(
        periodIncome: Double = 0.0,
        periodSpend: Double = 0.0,
        categorySpends: List<CategorySpend> = emptyList(),
        previousPeriodSpend: Double? = null,
        previousCategorySpends: List<CategorySpend> = emptyList(),
        biggestExpense: Pair<String, Double>? = null,
    ) = computeSmartInsights(
        periodIncome, periodSpend, categorySpends, previousPeriodSpend, previousCategorySpends, biggestExpense,
    )

    @Test
    fun `no signal yields no insights`() {
        assertTrue(insights().isEmpty())
    }

    @Test
    fun `spend trend fires negative when spend rose vs the previous period`() {
        val result = insights(periodSpend = 150.0, previousPeriodSpend = 100.0)
        val trend = result.filterIsInstance<SmartInsight.SpendTrend>().single()
        assertEquals(50.0, trend.percentChange, 1e-9)
        assertEquals(InsightTone.NEGATIVE, trend.tone)
    }

    @Test
    fun `spend trend fires positive when spend fell vs the previous period`() {
        val result = insights(periodSpend = 50.0, previousPeriodSpend = 100.0)
        val trend = result.filterIsInstance<SmartInsight.SpendTrend>().single()
        assertEquals(-50.0, trend.percentChange, 1e-9)
        assertEquals(InsightTone.POSITIVE, trend.tone)
    }

    @Test
    fun `spend trend is silent below a 1 percent change`() {
        val result = insights(periodSpend = 100.5, previousPeriodSpend = 100.0)
        assertTrue(result.none { it is SmartInsight.SpendTrend })
    }

    @Test
    fun `spend trend is silent when there is no previous period`() {
        val result = insights(periodSpend = 500.0, previousPeriodSpend = null)
        assertTrue(result.none { it is SmartInsight.SpendTrend })
    }

    @Test
    fun `savings rate is positive when income exceeds spend`() {
        val result = insights(periodIncome = 1000.0, periodSpend = 400.0)
        val rate = result.filterIsInstance<SmartInsight.SavingsRate>().single()
        assertEquals(60.0, rate.ratePercent, 1e-9)
        assertEquals(InsightTone.POSITIVE, rate.tone)
    }

    @Test
    fun `savings rate goes negative when spend exceeds income`() {
        val result = insights(periodIncome = 400.0, periodSpend = 1000.0)
        val rate = result.filterIsInstance<SmartInsight.SavingsRate>().single()
        assertEquals(-150.0, rate.ratePercent, 1e-9)
        assertEquals(InsightTone.NEGATIVE, rate.tone)
    }

    @Test
    fun `zero income with spend yields NoIncomeSpend instead of a savings rate`() {
        val result = insights(periodIncome = 0.0, periodSpend = 250.0)
        assertEquals(250.0, result.filterIsInstance<SmartInsight.NoIncomeSpend>().single().spend, 1e-9)
        assertTrue(result.none { it is SmartInsight.SavingsRate })
    }

    @Test
    fun `top category reports its share of total spend`() {
        val result = insights(
            categorySpends = listOf(
                CategorySpend(SpendingCategory.GROCERIES, 300.0),
                CategorySpend(SpendingCategory.DINING, 100.0),
            ),
        )
        val top = result.filterIsInstance<SmartInsight.TopCategory>().single()
        assertEquals(SpendingCategory.GROCERIES, top.category)
        assertEquals(75.0, top.sharePercent, 1e-9)
    }

    @Test
    fun `category spike fires for a category up at least 25 percent, picking the biggest mover`() {
        val result = insights(
            categorySpends = listOf(
                CategorySpend(SpendingCategory.DINING, 130.0), // +30%
                CategorySpend(SpendingCategory.TRANSPORT, 200.0), // +100%
            ),
            previousCategorySpends = listOf(
                CategorySpend(SpendingCategory.DINING, 100.0),
                CategorySpend(SpendingCategory.TRANSPORT, 100.0),
            ),
        )
        val spike = result.filterIsInstance<SmartInsight.CategorySpike>().single()
        assertEquals(SpendingCategory.TRANSPORT, spike.category)
        assertEquals(100.0, spike.percentChange, 1e-9)
        assertEquals(InsightTone.NEGATIVE, spike.tone)
    }

    @Test
    fun `category spike is silent below the 25 percent threshold`() {
        val result = insights(
            categorySpends = listOf(CategorySpend(SpendingCategory.DINING, 110.0)), // +10%
            previousCategorySpends = listOf(CategorySpend(SpendingCategory.DINING, 100.0)),
        )
        assertTrue(result.none { it is SmartInsight.CategorySpike })
    }

    @Test
    fun `biggest expense passes the merchant and amount through unchanged`() {
        val result = insights(biggestExpense = "Corner Store" to 42.0)
        val biggest = result.filterIsInstance<SmartInsight.BiggestExpense>().single()
        assertEquals("Corner Store", biggest.merchant)
        assertEquals(42.0, biggest.amount, 1e-9)
    }
}
