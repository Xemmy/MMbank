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
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.xemmy.mmbank.MMBankApp
import com.xemmy.mmbank.domain.widget.BalanceWidgetData
import com.xemmy.mmbank.domain.widget.BudgetsWidgetData
import com.xemmy.mmbank.domain.widget.SpendingWidgetData
import com.xemmy.mmbank.domain.widget.WidgetData
import com.xemmy.mmbank.domain.widget.WidgetState
import com.xemmy.mmbank.ui.MainActivity
import com.xemmy.mmbank.ui.theme.mmColors
import com.xemmy.mmbank.ui.util.asMoney
import kotlin.math.abs

/** Token colours for a widget, day and night, from the user's chosen colour theme (never raw hex). */
internal class WidgetColors(
    val background: ColorProvider,
    val text: ColorProvider,
    val muted: ColorProvider,
    val accent: ColorProvider,
    val track: ColorProvider,
    val danger: ColorProvider,
)

internal fun widgetColors(context: Context): WidgetColors {
    val palette = (context.applicationContext as MMBankApp).container.preferences.colorPalette
    val day = mmColors(palette, dark = false)
    val night = mmColors(palette, dark = true)
    fun cp(d: Color, n: Color) = ColorProvider(day = d, night = n)
    return WidgetColors(
        background = cp(day.surface, night.surface),
        text = cp(day.textPrimary, night.textPrimary),
        muted = cp(day.textSecondary, night.textSecondary),
        accent = cp(day.accent, night.accent),
        track = cp(day.borderSubtle, night.borderSubtle),
        danger = cp(day.danger, night.danger),
    )
}

private fun container(context: Context) = (context.applicationContext as MMBankApp).container

/** Net worth and its 30-day change. */
class BalanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadBalanceWidget(container(context))
        provideContent { WidgetFrame(title = "Net worth") { BalanceBody(state) } }
    }
}

/** This month's spending and its biggest categories. */
class SpendingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadSpendingWidget(container(context))
        provideContent { WidgetFrame(title = "Spent this month") { SpendingBody(state) } }
    }
}

/** Up to four capped categories with bars. */
class BudgetsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadBudgetsWidget(container(context))
        provideContent { WidgetFrame(title = "Budgets") { BudgetsBody(state) } }
    }
}

class BalanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BalanceWidget()
}

class SpendingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SpendingWidget()
}

class BudgetsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BudgetsWidget()
}

@Composable
private fun WidgetFrame(title: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val c = widgetColors(context)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(c.background)
            .padding(12.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
    ) {
        Text(title, style = TextStyle(color = c.muted, fontWeight = FontWeight.Medium))
        Spacer(GlanceModifier.height(4.dp))
        content()
    }
}

@Composable
private fun Message(text: String) {
    val c = widgetColors(LocalContext.current)
    Text(text, style = TextStyle(color = c.muted))
}

@Composable
private fun <T> StateBody(state: WidgetState<T>, empty: String, normal: @Composable (T) -> Unit) {
    when (state) {
        WidgetState.Locked -> Message("Locked. Open MMbank to unlock.")
        WidgetState.Hidden -> Message("Balances are hidden.")
        WidgetState.Empty -> Message(empty)
        is WidgetState.Normal -> normal(state.data)
    }
}

@Composable
private fun BalanceBody(state: WidgetState<BalanceWidgetData>) = StateBody(state, "Add an account to see your net worth.") { d ->
    val c = widgetColors(LocalContext.current)
    Text(d.netWorth.asMoney(d.currency), style = TextStyle(color = c.text, fontWeight = FontWeight.Bold))
    d.change30d?.let { change ->
        val sign = when (WidgetData.changeSign(change)) { 1 -> "+"; -1 -> "−"; else -> "" }
        Text("$sign${abs(change).asMoney(d.currency)} in 30 days", style = TextStyle(color = c.muted))
    }
}

@Composable
private fun SpendingBody(state: WidgetState<SpendingWidgetData>) = StateBody(state, "Nothing spent yet this month.") { d ->
    val c = widgetColors(LocalContext.current)
    Text(d.spentThisMonth.asMoney(d.currency), style = TextStyle(color = c.text, fontWeight = FontWeight.Bold))
    d.top.forEach { line ->
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(line.name, style = TextStyle(color = c.muted))
            Text("  " + line.amount.asMoney(d.currency), style = TextStyle(color = c.text))
        }
    }
}

@Composable
private fun BudgetsBody(state: WidgetState<BudgetsWidgetData>) = StateBody(state, "Set a budget in MMbank to see it here.") { d ->
    val c = widgetColors(LocalContext.current)
    d.lines.forEach { line ->
        Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text("${line.icon} ${line.name}", style = TextStyle(color = c.text))
                Text(
                    "  ${line.spent.asMoney(d.currency)} / ${line.cap.asMoney(d.currency)}",
                    style = TextStyle(color = if (line.over) c.danger else c.muted),
                )
            }
            LinearProgressIndicator(
                progress = line.fraction.coerceIn(0.0, 1.0).toFloat(),
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = if (line.over) c.danger else c.accent,
                backgroundColor = c.track,
            )
        }
    }
}
