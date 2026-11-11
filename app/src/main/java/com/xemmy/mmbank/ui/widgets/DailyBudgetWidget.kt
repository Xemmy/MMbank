package com.xemmy.mmbank.ui.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.budget.DailyBudgetPacing
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.MainActivity
import com.xemmy.mmbank.ui.util.asMoney
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/** Today's safe-to-spend figure, computed standalone for the widget's own refresh cycle. */
private data class WidgetPacing(
    val hasBudget: Boolean,
    val currencyCode: String,
    val allowance: Double = 0.0,
    val spentToday: Double = 0.0,
) {
    val remaining: Double get() = (allowance - spentToday).coerceAtLeast(0.0)
}

/**
 * Reuses [DailyBudgetPacing] (the same math behind the in-app overspend
 * notification) rather than a widget-specific approximation. Only
 * same-currency debits count, mirroring [com.xemmy.mmbank.di.AppContainer.checkDailyBudgetPacing].
 */
private suspend fun loadWidgetPacing(container: AppContainer): WidgetPacing {
    val base = container.preferences.defaultCurrency
    val monthlyBudget = container.preferences.monthlySpendBudget
    if (monthlyBudget <= 0.0) return WidgetPacing(hasBudget = false, currencyCode = base)

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val monthStartMillis = YearMonth.from(today).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val todayStartMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()

    val transactions = container.transactionRepository.observeTransactions().first()
    val monthDebits = transactions.filter { tx ->
        LedgerMath.affectsSpend(tx) &&
            tx.currencyCode.equals(base, ignoreCase = true) && tx.timestampMillis >= monthStartMillis
    }
    val spentBeforeToday = LedgerMath.netSpendExact(monthDebits.filter { it.timestampMillis < todayStartMillis }).toDouble()
    val spentToday = LedgerMath.netSpendExact(monthDebits.filter { it.timestampMillis >= todayStartMillis }).toDouble()

    val allowance = DailyBudgetPacing.dailyAllowance(
        monthlyBudget = BigDecimal.valueOf(monthlyBudget),
        fixedExpenses = BigDecimal.valueOf(container.preferences.fixedMonthlyExpenses),
        spentSoFar = BigDecimal.valueOf(spentBeforeToday),
        remainingDaysInMonth = DailyBudgetPacing.remainingDaysInMonth(today),
    ).toDouble()

    return WidgetPacing(hasBudget = true, currencyCode = base, allowance = allowance, spentToday = spentToday)
}

/**
 * Home screen widget: today's remaining safe-to-spend allowance plus a
 * shortcut into [com.xemmy.mmbank.ui.screens.transactions.UncategorizedQueueScreen].
 * Recomputed from the live ledger every time the OS refreshes the widget
 * (see `updatePeriodMillis` in daily_budget_widget_info.xml) — there is no
 * separate widget-only cache to go stale.
 */
class DailyBudgetWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as MMBankApp).container
        val pacing = loadWidgetPacing(container)
        provideContent {
            DailyBudgetWidgetContent(pacing)
        }
    }
}

class DailyBudgetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DailyBudgetWidget()
}

private val WIDGET_BACKGROUND = ColorProvider(Color(0xFF14161B))
private val WIDGET_TEXT = ColorProvider(Color.White)
private val WIDGET_TEXT_MUTED = ColorProvider(Color(0xFFB0B4BD))
private val WIDGET_ACCENT = ColorProvider(Color(0xFF86E3B8))

@Composable
private fun DailyBudgetWidgetContent(pacing: WidgetPacing) {
    val context = LocalContext.current

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WIDGET_BACKGROUND)
            .padding(12.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
    ) {
        Text("Daily budget", style = TextStyle(color = WIDGET_TEXT, fontWeight = FontWeight.Medium))
        if (!pacing.hasBudget) {
            Text(
                "Set a monthly budget in Settings to see today's allowance.",
                style = TextStyle(color = WIDGET_TEXT_MUTED),
            )
        } else {
            Text(
                pacing.remaining.asMoney(pacing.currencyCode) + " left today",
                style = TextStyle(color = WIDGET_TEXT, fontWeight = FontWeight.Bold),
            )
            Text(
                pacing.spentToday.asMoney(pacing.currencyCode) + " of " +
                    pacing.allowance.asMoney(pacing.currencyCode) + " spent",
                style = TextStyle(color = WIDGET_TEXT_MUTED),
            )
        }
        Text(
            "Review uncategorized ›",
            style = TextStyle(color = WIDGET_ACCENT, fontWeight = FontWeight.Medium),
            modifier = GlanceModifier.clickable(
                actionStartActivity(
                    Intent(context, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_UNCATEGORIZED, true),
                ),
            ),
        )
    }
}
