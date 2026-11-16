package com.xemmy.mmbank.domain.report

import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.filter.PeriodWindow
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Money in and out inside one chart bucket (a day or a month). */
data class BucketFlow(val window: DateWindow, val income: Double, val spend: Double) {
    val net: Double get() = income - spend
}

/** One top-level category's part of a window. Sub-categories are rolled into their parent. */
data class CategoryShare(
    val category: Category,
    /** The parent's own id plus every sub-category id, for an Activity drill-down. */
    val categoryIds: Set<Long>,
    val amount: Double,
    val count: Int,
    /** Percent of the section total, 0..100. */
    val share: Double,
)

/** Rows that move money without being spending or income, kept out of every total. */
data class NotCounted(val transfers: Int, val cardPayments: Int, val adjustments: Int, val cashback: Int) {
    val total: Int get() = transfers + cardPayments + adjustments + cashback
}

/** What one label took in a window: its net spending and how many counted rows carry it. */
data class LabelShare(val label: com.xemmy.mmbank.domain.model.Label, val amount: Double, val count: Int)

data class PeriodBreakdown(
    val window: DateWindow?,
    val buckets: List<BucketFlow>,
    val spendTotal: Double,
    val incomeTotal: Double,
    val spending: List<CategoryShare>,
    val income: List<CategoryShare>,
    val notCounted: NotCounted,
    /** Net spending per label, biggest first; empty when no label was used. */
    val labels: List<LabelShare> = emptyList(),
) {
    val net: Double get() = incomeTotal - spendTotal
}

/**
 * Builds the numbers behind the Spending and Cash-flow pages for one [PeriodWindow]. "Spent" and
 * "Received" come from [LedgerMath], so totals equal the Insights tiles; [value] converts a row to
 * the base currency (null = unconvertible, skipped like everywhere else).
 */
object PeriodBreakdownBuilder {

    fun build(
        transactions: List<Transaction>,
        categories: List<Category>,
        period: PeriodWindow,
        today: LocalDate,
        zone: ZoneId,
        raw: Boolean = false,
        labels: List<com.xemmy.mmbank.domain.model.Label> = emptyList(),
        labelsOf: (Long) -> Set<Long> = { emptySet() },
        value: (Transaction) -> Double?,
    ): PeriodBreakdown {
        val window = period.range()
        fun day(tx: Transaction): LocalDate = Instant.ofEpochMilli(tx.timestampMillis).atZone(zone).toLocalDate()
        val inWindow = transactions.filter { tx -> window == null || day(tx).let { it >= window.start && it <= window.end } }

        val earliest = inWindow.minOfOrNull { it.timestampMillis }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val buckets = period.buckets(today, earliest).map { b ->
            var income = 0.0
            var spend = 0.0
            inWindow.forEach { tx ->
                val d = day(tx)
                if (d < b.start || d > b.end) return@forEach
                val v = value(tx) ?: return@forEach
                when (LedgerMath.effect(tx, raw)) {
                    LedgerMath.Effect.INCOME -> income += v
                    LedgerMath.Effect.SPEND -> spend += v
                    LedgerMath.Effect.REFUND -> spend -= v
                    LedgerMath.Effect.NONE -> Unit
                }
            }
            BucketFlow(b, income, spend)
        }

        val spendTotal = LedgerMath.netSpend(inWindow, raw, value)
        val incomeTotal = LedgerMath.income(inWindow, raw, value)

        return PeriodBreakdown(
            window = window,
            buckets = buckets,
            spendTotal = spendTotal,
            incomeTotal = incomeTotal,
            spending = shares(inWindow.filter { LedgerMath.affectsSpend(it, raw) }, categories, raw, value, spendSide = true),
            income = shares(inWindow.filter { LedgerMath.isIncome(it, raw) }, categories, raw, value, spendSide = false),
            notCounted = notCounted(inWindow, raw),
            labels = labelShares(inWindow.filter { LedgerMath.affectsSpend(it, raw) }, labels, labelsOf, raw, value),
        )
    }

    private fun labelShares(
        spendRows: List<Transaction>,
        labels: List<com.xemmy.mmbank.domain.model.Label>,
        labelsOf: (Long) -> Set<Long>,
        raw: Boolean,
        value: (Transaction) -> Double?,
    ): List<LabelShare> {
        if (labels.isEmpty()) return emptyList()
        return labels.mapNotNull { label ->
            val rows = spendRows.filter { label.id in labelsOf(it.id) }
            val total = LedgerMath.netSpend(rows, raw, value)
            if (rows.isEmpty() || total <= 0.0) null else LabelShare(label, total, rows.size)
        }.sortedByDescending { it.amount }
    }

    private fun notCounted(txs: List<Transaction>, raw: Boolean): NotCounted {
        if (raw) return NotCounted(0, 0, 0, 0)
        val none = txs.filter { LedgerMath.effect(it) == LedgerMath.Effect.NONE }
        return NotCounted(
            transfers = none.count { it.kind == TransactionKind.TRANSFER },
            cardPayments = none.count { it.kind == TransactionKind.CARD_PAYMENT },
            adjustments = none.count { it.kind == TransactionKind.ADJUSTMENT },
            cashback = none.count { it.kind == TransactionKind.CASHBACK },
        )
    }

    private fun shares(
        txs: List<Transaction>,
        categories: List<Category>,
        raw: Boolean,
        value: (Transaction) -> Double?,
        spendSide: Boolean,
    ): List<CategoryShare> {
        val byId = categories.associateBy { it.id }
        val fallback = categories.firstOrNull { it.name.equals("Uncategorized", ignoreCase = true) }
            ?: Category(name = "Uncategorized", icon = "🏷️", colorHex = "#6C757D")

        fun topLevel(id: Long?): Category {
            val own = id?.let(byId::get) ?: return fallback
            return own.parentCategoryId?.let(byId::get) ?: own
        }

        val grouped = txs.groupBy { topLevel(it.categoryId) }
        val rows = grouped.mapNotNull { (category, rowsOf) ->
            val total = if (spendSide) LedgerMath.netSpend(rowsOf, raw, value) else LedgerMath.income(rowsOf, raw, value)
            if (total <= 0.0) null else Triple(category, total, rowsOf.size)
        }
        val sum = rows.sumOf { it.second }
        return rows
            .sortedByDescending { it.second }
            .map { (category, total, count) ->
                val ids = if (category.id == 0L) emptySet()
                else setOf(category.id) + categories.filter { it.parentCategoryId == category.id }.map { it.id }
                CategoryShare(category, ids, total, count, if (sum > 0.0) total / sum * 100.0 else 0.0)
            }
    }
}
