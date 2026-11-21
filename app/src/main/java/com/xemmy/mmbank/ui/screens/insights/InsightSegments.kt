package com.xemmy.mmbank.ui.screens.insights

import com.xemmy.mmbank.domain.parser.MerchantNormalizer
import com.xemmy.mmbank.ui.designsystem.MoneySegment
import com.xemmy.mmbank.ui.designsystem.MoneyTone
import com.xemmy.mmbank.ui.designsystem.money
import com.xemmy.mmbank.ui.designsystem.text
import com.xemmy.mmbank.ui.screens.dashboard.SmartInsight
import kotlin.math.abs
import kotlin.math.roundToInt

/** A bank-shouted merchant ("AL FUTTAIM TRADING LLC, ABU DHABI-AE") as a readable name. */
internal fun cleanMerchant(raw: String): String = MerchantNormalizer.displayName(raw) ?: raw

/** One [SmartInsight] as words and amounts, so amounts can draw the Dirham symbol and mask when hidden. */
internal fun insightSegments(insight: SmartInsight, currency: String): List<MoneySegment> = when (insight) {
    is SmartInsight.SpendTrend -> {
        val direction = if (insight.percentChange > 0) "up" else "down"
        listOf(text("Spending is ${abs(insight.percentChange).roundToInt()}% $direction vs. the previous period"))
    }
    is SmartInsight.SavingsRate -> listOf(
        text(
            if (insight.ratePercent >= 0) {
                "You saved ${insight.ratePercent.roundToInt()}% of your income this period"
            } else {
                "You spent ${(-insight.ratePercent).roundToInt()}% more than you earned this period"
            },
        ),
    )
    is SmartInsight.TopCategory -> listOf(
        text("${insight.category.name} is your biggest expense at ${insight.sharePercent.roundToInt()}% ("),
        money(insight.amount, currency),
        text(")"),
    )
    is SmartInsight.CategorySpike -> listOf(
        text("${insight.category.name} spending jumped ${insight.percentChange.roundToInt()}% to "),
        money(insight.amount, currency),
    )
    is SmartInsight.NoIncomeSpend -> listOf(
        text("No income recorded this period — "),
        money(insight.spend, currency),
        text(" went out"),
    )
    is SmartInsight.BiggestExpense -> listOf(
        text("Your biggest expense was "),
        money(insight.amount, currency),
        text(" at ${cleanMerchant(insight.merchant)}"),
    )
    is SmartInsight.UnusualCharge -> listOf(
        text("Unusual: "),
        money(insight.amount, insight.currencyCode),
        text(
            " at ${cleanMerchant(insight.merchant)} is " +
                "${String.format(java.util.Locale.US, "%.1f", insight.ratio)}× your usual (about ",
        ),
        money(insight.usual, insight.currencyCode),
        text(")"),
    )
}

/** "▲ Đ1,200.00 vs a year ago": arrow by direction, magnitude as money. */
internal fun deltaSegments(value: Double, currency: String, suffix: String): List<MoneySegment> = listOf(
    text(if (value >= 0) "▲ " else "▼ "),
    money(abs(value), currency),
    text(" $suffix"),
)

/** Segments of several lines joined with a middle-dot separator. */
internal fun joinSegments(lines: List<List<MoneySegment>>): List<MoneySegment> =
    lines.flatMapIndexed { i, l -> if (i == 0) l else listOf(text("  ·  ")) + l }

/** Colour of the spend-change line under the period tiles; spending down is good. */
internal fun spendChangeLine(percent: Double?, previousLabel: String?): Pair<String, MoneyTone>? {
    if (percent == null || previousLabel == null) return null
    val rounded = abs(percent).roundToInt()
    return when {
        rounded == 0 -> "≈ About the same spending as $previousLabel" to MoneyTone.NEUTRAL
        percent < 0 -> "↓ $rounded% less spending than $previousLabel" to MoneyTone.IN
        else -> "↑ $rounded% more spending than $previousLabel" to MoneyTone.OUT
    }
}
