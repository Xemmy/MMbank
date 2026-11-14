package com.xemmy.mmbank.domain.intelligence

import java.math.BigDecimal
import java.util.Locale

/**
 * Sentences for [WhatChangedDigest], shared by Home's weekly card and the
 * weekly notification. Money formatting is injected (base-currency doubles,
 * and amounts in their own currency), so the wording is tested here rather
 * than living in a composable -- the same split as
 * [com.xemmy.mmbank.domain.ledger.IntegrityWording].
 */
class WhatChangedWording(
    private val money: (Double) -> String,
    private val moneyIn: (BigDecimal, String) -> String,
) {

    fun headline(d: WhatChangedDigest.Digest): String = when {
        d.thisWeekSpend <= 0.0 && d.lastWeekSpend <= 0.0 -> "No spending this week."
        d.percentChange == null -> "Spent ${money(d.thisWeekSpend)} this week."
        kotlin.math.abs(d.percentChange) < 1.0 ->
            "Spent ${money(d.thisWeekSpend)} this week, about the same as last week."
        else -> {
            val how = if (d.percentChange > 0) "more" else "less"
            "Spent ${money(d.thisWeekSpend)} this week, ${pct(d.percentChange)} $how than last week (${money(d.lastWeekSpend)})."
        }
    }

    fun item(item: WhatChangedDigest.Item): String = when (item) {
        is WhatChangedDigest.CaptureQuiet ->
            "No bank messages captured for ${item.days} days. Capture may have stopped."
        is WhatChangedDigest.CategoryCap -> if (item.over) {
            "${item.category} is over its cap: ${moneyIn(item.spent, item.currencyCode)} of ${moneyIn(item.cap, item.currencyCode)}."
        } else {
            val share = item.spent.toDouble() / item.cap.toDouble() * 100
            "${item.category} is at ${pct(share)} of its ${moneyIn(item.cap, item.currencyCode)} cap."
        }
        is WhatChangedDigest.UnusualCharge ->
            "Unusual: ${moneyIn(item.amount, item.currencyCode)} at ${item.merchant}, " +
                "${String.format(Locale.US, "%.1f", item.ratio)}× your usual."
        is WhatChangedDigest.PaymentDue -> {
            val what = if (item.isCard) "${item.name} payment of ${moneyIn(item.amount, item.currencyCode)}" else "${item.name} (${moneyIn(item.amount, item.currencyCode)})"
            "$what ${dueText(item.daysUntil)}."
        }
        is WhatChangedDigest.NewSubscription ->
            "New subscription: ${item.merchant}, ${moneyIn(item.amount, item.currencyCode)}."
        is WhatChangedDigest.PriceChange -> {
            val how = if (item.to > item.from) "went up" else "went down"
            "${item.merchant} $how from ${moneyIn(item.from, item.currencyCode)} to ${moneyIn(item.to, item.currencyCode)}."
        }
        is WhatChangedDigest.CategorySwing ->
            if (item.lastWeek > 0.0) {
                "${item.category}: ${money(item.thisWeek)} this week, up from ${money(item.lastWeek)}."
            } else {
                "${item.category}: ${money(item.thisWeek)} this week, nothing last week."
            }
    }

    private fun dueText(days: Long): String = when {
        days < -1L -> "was due ${-days} days ago"
        days == -1L -> "was due yesterday"
        days == 0L -> "is due today"
        days == 1L -> "is due tomorrow"
        else -> "is due in $days days"
    }

    private fun pct(value: Double): String = "${kotlin.math.abs(value).let { Math.round(it) }}%"
}
