package com.xemmy.mmbank.domain.intelligence.copilot

import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import java.time.LocalDate
import java.time.ZoneId

/**
 * Question chips for the Copilot, built from the user's own ledger: their
 * biggest category and merchant this month (or, early in a month, over the
 * last 60 days). Every chip is checked against [CopilotQueryParser], so
 * tapping one always gets an on-phone answer.
 */
object CopilotSuggestions {

    fun build(
        transactions: List<Transaction>,
        categories: List<Category>,
        toBase: (Transaction) -> Double?,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        max: Int = 6,
    ): List<String> {
        val merchants = transactions.mapNotNull { it.merchant?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        val thisMonth = DateWindow(today.withDayOfMonth(1), today)
        val recent = DateWindow(today.minusDays(59), today)
        val spends = { w: DateWindow -> transactions.filter { w.contains(it.timestampMillis, zone) && LedgerMath.affectsSpend(it) } }
        val pool = spends(thisMonth).ifEmpty { spends(recent) }
        val period = if (spends(thisMonth).isNotEmpty()) "this month" else "last month"

        val byId = categories.associateBy { it.id }
        val topCategory = pool.filter { it.categoryId in byId }
            .groupBy { it.categoryId!! }
            .mapValues { (_, txs) -> LedgerMath.netSpend(txs) { toBase(it) } }
            .filter { it.value > 0.0 && !byId.getValue(it.key).name.equals("Uncategorized", ignoreCase = true) }
            .maxByOrNull { it.value }
            ?.let { byId.getValue(it.key).name }

        val topMerchant = pool.filter { !it.merchant.isNullOrBlank() }
            .groupBy { it.merchant!!.trim().lowercase() }
            .mapValues { (_, txs) -> txs to LedgerMath.netSpend(txs) { toBase(it) } }
            .filter { it.value.second > 0.0 }
            .maxByOrNull { it.value.second }
            ?.value?.first
            ?.groupingBy { it.merchant!!.trim() }?.eachCount()?.maxByOrNull { it.value }?.key

        val candidates = listOfNotNull(
            "How much did I spend this month?",
            topCategory?.let { "How much on $it $period?" },
            topMerchant?.let { "How much at $it $period?" },
            "What's my biggest expense this month?",
            "Compare this month with last month",
            "Where did my money go last month?",
            "What's my savings rate this month?",
        )
        return candidates
            .filter { q -> CopilotQueryParser.parse(q, categories, merchants, today) != null }
            .filter { q ->
                // A chip that names a category/merchant must actually resolve to it.
                val named = (topCategory != null && q.contains(topCategory)) || (topMerchant != null && q.contains(topMerchant))
                !named || CopilotQueryParser.parse(q, categories, merchants, today)?.subject != null
            }
            .take(max)
    }
}
