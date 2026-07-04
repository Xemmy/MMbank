package com.xemmy.mmbank.domain.cashback

import java.math.BigDecimal
import java.math.RoundingMode

/** A spending bucket the user enters a monthly amount for. */
data class CashbackCategory(
    val key: String,
    val label: String,
    val examples: String,
)

/**
 * A rewards card: its per-category rates (falling back to [baseRate]), an
 * optional monthly cashback cap, a minimum monthly spend to earn at all, and
 * its annual fee. Rates are fractions, e.g. 0.05 = 5%.
 */
data class CashbackCard(
    val id: String,
    val name: String,
    val ratesByCategory: Map<String, Double>,
    val baseRate: Double,
    val monthlyCap: BigDecimal?,
    val minMonthlySpend: BigDecimal,
    val annualFee: BigDecimal,
    val notes: String,
) {
    fun rateFor(categoryKey: String): Double = ratesByCategory[categoryKey] ?: baseRate
}

/** Where the engine decided to route one category's spend. */
data class CategoryRouting(
    val category: CashbackCategory,
    val spend: BigDecimal,
    val card: CashbackCard?,
    val rate: Double,
    val cashback: BigDecimal,
)

/** Per-card roll-up after routing. */
data class CardSummary(
    val card: CashbackCard,
    val routedSpend: BigDecimal,
    val rawCashback: BigDecimal,
    val cappedCashback: BigDecimal,
    val monthlyFee: BigDecimal,
    val belowMinSpend: Boolean,
    val capReached: Boolean,
)

data class CashbackAlert(val message: String, val ok: Boolean)

data class CashbackPlan(
    val routings: List<CategoryRouting>,
    val cardSummaries: List<CardSummary>,
    val grossMonthly: BigDecimal,
    val monthlyFees: BigDecimal,
    val netMonthly: BigDecimal,
    val netAnnual: BigDecimal,
    val alerts: List<CashbackAlert>,
)

/**
 * A transparent best-rate cashback router: each category's spend goes to the
 * card with the highest rate for it; per-card monthly caps limit the earn;
 * a card below its minimum monthly spend is flagged (not silently zeroed);
 * annual fees are spread across the month and netted off.
 */
object CashbackEngine {

    private val TWELVE = BigDecimal(12)

    fun compute(
        cards: List<CashbackCard>,
        categories: List<CashbackCategory>,
        spendByCategory: Map<String, BigDecimal>,
    ): CashbackPlan {
        val routings = categories.map { cat ->
            val spend = spendByCategory[cat.key] ?: BigDecimal.ZERO
            if (spend.signum() <= 0) {
                CategoryRouting(cat, BigDecimal.ZERO, null, 0.0, BigDecimal.ZERO)
            } else {
                val best = cards.maxByOrNull { it.rateFor(cat.key) }
                val rate = best?.rateFor(cat.key) ?: 0.0
                val cashback = spend.multiply(BigDecimal.valueOf(rate)).setScale(2, RoundingMode.HALF_UP)
                CategoryRouting(cat, spend, best, rate, cashback)
            }
        }

        val summaries = routings
            .filter { it.card != null }
            .groupBy { it.card!! }
            .map { (card, rs) ->
                val routedSpend = rs.fold(BigDecimal.ZERO) { a, b -> a + b.spend }
                val raw = rs.fold(BigDecimal.ZERO) { a, b -> a + b.cashback }
                val capped = card.monthlyCap?.let { raw.min(it) } ?: raw
                val fee = card.annualFee.divide(TWELVE, 2, RoundingMode.HALF_UP)
                CardSummary(
                    card = card,
                    routedSpend = routedSpend,
                    rawCashback = raw,
                    cappedCashback = capped,
                    monthlyFee = fee,
                    belowMinSpend = card.minMonthlySpend.signum() > 0 && routedSpend < card.minMonthlySpend,
                    capReached = card.monthlyCap != null && raw >= card.monthlyCap,
                )
            }
            .sortedByDescending { it.cappedCashback }

        val gross = summaries.fold(BigDecimal.ZERO) { a, b -> a + b.cappedCashback }
        val fees = summaries.fold(BigDecimal.ZERO) { a, b -> a + b.monthlyFee }
        val net = gross - fees

        val alerts = buildList {
            summaries.forEach { s ->
                if (s.belowMinSpend) {
                    add(CashbackAlert("${s.card.name}: route ≥ ${s.card.minMonthlySpend} AED/mo to earn (now ${s.routedSpend}).", false))
                }
                if (s.capReached) {
                    add(CashbackAlert("${s.card.name}: monthly cap ${s.card.monthlyCap} reached — extra spend earns nothing here.", false))
                }
                if (s.card.annualFee.signum() > 0 && s.cappedCashback.multiply(TWELVE) < s.card.annualFee) {
                    add(CashbackAlert("${s.card.name}: annual fee ${s.card.annualFee} may exceed its yearly cashback.", false))
                }
            }
            add(CashbackAlert("Golden rule: pay every card in full monthly — interest wipes out all cashback.", true))
            add(CashbackAlert("Foreign currency: use a 0% FX card, never a normal bank card.", true))
        }

        return CashbackPlan(
            routings = routings,
            cardSummaries = summaries,
            grossMonthly = gross,
            monthlyFees = fees,
            netMonthly = net,
            netAnnual = net.multiply(TWELVE),
            alerts = alerts,
        )
    }
}
