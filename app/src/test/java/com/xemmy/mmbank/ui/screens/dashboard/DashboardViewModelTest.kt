package com.xemmy.mmbank.ui.screens.dashboard

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
 * [computeAccountsPresentation] covers manually-added accounts of type
 * [AccountType.CRYPTO_SPOT] (e.g. a hand-entered "Binance" account) — a
 * different data path from the Binance-API-synced holdings in
 * [computeCryptoPresentation], but the same two independent Settings toggles
 * apply: hiding is a display concern, excluding is an accounting concern.
 */
class DashboardAccountsPresentationTest {

    private fun row(type: AccountType, balanceInBase: Double?, name: String = "Account") = AccountRow(
        account = Account(name = name, type = type, currencyCode = "USD"),
        balance = balanceInBase ?: 0.0,
        balanceInBase = balanceInBase,
    )

    @Test
    fun `regression - both toggles off counts and shows every row`() {
        val rows = listOf(row(AccountType.CHECKING, 100.0), row(AccountType.CRYPTO_SPOT, 50.0))
        val result = computeAccountsPresentation(rows, hideCryptoBalance = false, excludeCryptoFromTotal = false)
        assertEquals(2, result.visibleRows.size)
        assertEquals(150.0, result.fiatInBase, 1e-9)
    }

    @Test
    fun `hideCryptoBalance drops crypto-type accounts from the visible list only`() {
        val rows = listOf(row(AccountType.CHECKING, 100.0), row(AccountType.CRYPTO_SPOT, 50.0))
        val result = computeAccountsPresentation(rows, hideCryptoBalance = true, excludeCryptoFromTotal = false)
        assertEquals(1, result.visibleRows.size)
        assertEquals(AccountType.CHECKING, result.visibleRows.single().account.type)
        // Hidden, but still counted — hiding and excluding are independent.
        assertEquals(150.0, result.fiatInBase, 1e-9)
    }

    @Test
    fun `excludeCryptoFromTotal drops crypto-type accounts from the total only`() {
        val rows = listOf(row(AccountType.CHECKING, 100.0), row(AccountType.CRYPTO_SPOT, 50.0))
        val result = computeAccountsPresentation(rows, hideCryptoBalance = false, excludeCryptoFromTotal = true)
        assertEquals(2, result.visibleRows.size)
        assertEquals(100.0, result.fiatInBase, 1e-9)
    }

    @Test
    fun `both toggles on hides and excludes the crypto-type account`() {
        val rows = listOf(row(AccountType.CHECKING, 100.0), row(AccountType.CRYPTO_SPOT, 50.0))
        val result = computeAccountsPresentation(rows, hideCryptoBalance = true, excludeCryptoFromTotal = true)
        assertEquals(1, result.visibleRows.size)
        assertEquals(100.0, result.fiatInBase, 1e-9)
    }

    @Test
    fun `an unconvertible crypto-type balance stops flagging approximate once excluded`() {
        val rows = listOf(row(AccountType.CHECKING, 100.0), row(AccountType.CRYPTO_SPOT, null))
        val included = computeAccountsPresentation(rows, hideCryptoBalance = false, excludeCryptoFromTotal = false)
        assertTrue(included.hasUnconvertedFiat)

        val excluded = computeAccountsPresentation(rows, hideCryptoBalance = false, excludeCryptoFromTotal = true)
        assertFalse(excluded.hasUnconvertedFiat)
    }
}
