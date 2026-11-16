package com.xemmy.mmbank.ui.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.budget.BudgetListBuilder
import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator
import com.xemmy.mmbank.domain.filter.PeriodUnit
import com.xemmy.mmbank.domain.filter.PeriodWindow
import com.xemmy.mmbank.domain.report.PeriodBreakdownBuilder
import com.xemmy.mmbank.domain.widget.BalanceWidgetData
import com.xemmy.mmbank.domain.widget.BudgetsWidgetData
import com.xemmy.mmbank.domain.widget.SpendingWidgetData
import com.xemmy.mmbank.domain.widget.WidgetData
import com.xemmy.mmbank.domain.widget.WidgetGate
import com.xemmy.mmbank.domain.widget.WidgetState
import com.xemmy.mmbank.ui.screens.dashboard.fiatNetWorthAt
import com.xemmy.mmbank.ui.util.CurrencyMath
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first

private fun isLocked(container: AppContainer) = container.appLock.locked.value

/** Net worth now and 30 days ago, fiat only, by the same rules as Home. */
internal suspend fun loadBalanceWidget(container: AppContainer): WidgetState<BalanceWidgetData> {
    val locked = isLocked(container)
    val hidden = container.hideBalances.value
    // Never read the ledger for a widget that will not show it.
    if (locked || hidden) return WidgetGate.decide<BalanceWidgetData>(locked, hidden, null)
    val accounts = container.accountRepository.observeActive().first()
    if (accounts.isEmpty()) return WidgetGate.decide<BalanceWidgetData>(false, false, null)
    val transactions = container.transactionRepository.observeTransactions().first()
    val rates = container.ratesRepository.observeRates().first()
    val base = container.preferences.defaultCurrency
    val zone = ZoneId.systemDefault()
    val todayRates = rates.filter { it.baseCode.equals(base, ignoreCase = true) }.associate { it.quoteCode.uppercase() to it.rate }
    val excludeCards = container.preferences.excludeCreditCardFromTotal
    val now = fiatNetWorthAt(accounts, transactions, Long.MAX_VALUE, todayRates, base, excludeCards).first
    val thirtyAgo = LocalDate.now(zone).minusDays(30).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val before = fiatNetWorthAt(accounts, transactions, thirtyAgo, todayRates, base, excludeCards).first
    return WidgetGate.decide(false, false, BalanceWidgetData(now, now - before, base))
}

internal suspend fun loadSpendingWidget(container: AppContainer): WidgetState<SpendingWidgetData> {
    val locked = isLocked(container)
    val hidden = container.hideBalances.value
    if (locked || hidden) return WidgetGate.decide<SpendingWidgetData>(locked, hidden, null)
    val base = container.preferences.defaultCurrency
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val rates = container.ratesRepository.observeRates().first()
        .filter { it.baseCode.equals(base, ignoreCase = true) }.associate { it.quoteCode.uppercase() to it.rate.toDouble() }
    val breakdown = PeriodBreakdownBuilder.build(
        transactions = container.transactionRepository.observeTransactions().first(),
        categories = container.categoryRepository.observeAll().first(),
        period = PeriodWindow.of(PeriodUnit.MONTH, today),
        today = today,
        zone = zone,
    ) { tx -> CurrencyMath.toBase(tx.amount.toDouble(), tx.currencyCode, base, rates) }
    val data = SpendingWidgetData(breakdown.spendTotal, WidgetData.topCategories(breakdown.spending), base)
    return WidgetGate.decide(false, false, data) { it.isEmpty }
}

internal suspend fun loadBudgetsWidget(container: AppContainer): WidgetState<BudgetsWidgetData> {
    val locked = isLocked(container)
    val hidden = container.hideBalances.value
    if (locked || hidden) return WidgetGate.decide<BudgetsWidgetData>(locked, hidden, null)
    val base = container.preferences.defaultCurrency
    val zone = ZoneId.systemDefault()
    val rates = container.ratesRepository.observeRates().first()
        .filter { it.baseCode.equals(base, ignoreCase = true) }.associate { it.quoteCode.uppercase() to it.rate }
    val rows = BudgetListBuilder.build(
        categories = container.categoryRepository.observeAll().first(),
        transactions = container.transactionRepository.observeTransactions().first(),
        baseCurrency = base,
        convert = CategoryBudgetCalculator.converter(base, rates),
        today = LocalDate.now(zone),
        zone = zone,
        rollover = container.preferences.budgetRollover,
    )
    val data = BudgetsWidgetData(WidgetData.budgetLines(rows), base)
    return WidgetGate.decide(false, false, data) { it.lines.isEmpty() }
}

/** Redraws every MMbank widget; called when the ledger or a display setting changes. */
object WidgetRefresher {
    suspend fun refreshAll(context: Context) {
        runCatching { DailyBudgetWidget().updateAll(context) }
        runCatching { BalanceWidget().updateAll(context) }
        runCatching { SpendingWidget().updateAll(context) }
        runCatching { BudgetsWidget().updateAll(context) }
    }

    /** The widgets the user can pin, in the order Settings lists them. */
    enum class Pinnable(val title: String, val receiver: Class<*>) {
        DAILY("Daily budget", DailyBudgetWidgetReceiver::class.java),
        BALANCE("Net worth", BalanceWidgetReceiver::class.java),
        SPENDING("Spent this month", SpendingWidgetReceiver::class.java),
        BUDGETS("Budgets", BudgetsWidgetReceiver::class.java),
    }

    /** Asks the launcher to pin [widget]; false when the launcher cannot (the user adds it by hand). */
    fun requestPin(context: Context, widget: Pinnable): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return manager.requestPinAppWidget(ComponentName(context, widget.receiver), null, null)
    }
}
