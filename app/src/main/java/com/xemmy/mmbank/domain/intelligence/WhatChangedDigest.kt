package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.account.CardPaymentReminders
import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator
import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.Transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * "What changed this week": spend against last week, plus the handful of
 * things worth knowing, ranked and capped at [MAX_ITEMS]. Home's weekly card
 * and the opt-in weekly notification both show it.
 *
 * The items:
 * - capture looks quiet (from [CaptureHealthEvaluator]);
 * - a category over, or nearly at, its monthly cap ([CategoryBudgetCalculator]);
 * - an unusual charge ([SpendAnomalyDetector]);
 * - a bill or card payment due within a week;
 * - a subscription seen for the first time, or one whose price changed;
 * - the category whose spending jumped most against last week.
 *
 * Deterministic and model-free: the "AI" weekly digest it replaces on Home
 * ([AiWeeklyDigestGenerator]) never used a model either. Spending here uses
 * [LedgerMath.netSpend], so refunds count against purchases (the older
 * generator counted purchases only). Pure and JVM-testable.
 */
object WhatChangedDigest {

    const val MAX_ITEMS = 5

    sealed interface Item {
        /** Lower shows first. */
        val rank: Int
    }

    data class CaptureQuiet(val days: Int) : Item {
        override val rank get() = 0
    }

    data class CategoryCap(
        val category: String,
        val spent: BigDecimal,
        val cap: BigDecimal,
        val over: Boolean,
        val currencyCode: String,
    ) : Item {
        override val rank get() = if (over) 1 else 3
    }

    data class UnusualCharge(val merchant: String, val amount: BigDecimal, val ratio: Double, val currencyCode: String) : Item {
        override val rank get() = 2
    }

    data class PaymentDue(
        val name: String,
        val amount: BigDecimal,
        val currencyCode: String,
        /** Negative once overdue. */
        val daysUntil: Long,
        val isCard: Boolean,
    ) : Item {
        override val rank get() = 2
    }

    data class NewSubscription(val merchant: String, val amount: BigDecimal, val currencyCode: String) : Item {
        override val rank get() = 4
    }

    data class PriceChange(val merchant: String, val from: BigDecimal, val to: BigDecimal, val currencyCode: String) : Item {
        override val rank get() = 4
    }

    data class CategorySwing(val category: String, val thisWeek: Double, val lastWeek: Double) : Item {
        override val rank get() = 5
    }

    data class Digest(
        val thisWeekSpend: Double,
        val lastWeekSpend: Double,
        /** Positive = more than last week; null when last week had none. */
        val percentChange: Double?,
        val items: List<Item>,
    )

    /** The minimum detections [SubscriptionRadarEngine] needs: a subscription at exactly this count is new. */
    private const val FIRST_DETECTION_OCCURRENCES = 3

    fun build(
        transactions: List<Transaction>,
        categories: List<Category>,
        subscriptions: List<Subscription>,
        bills: List<Bill>,
        cards: List<CardPaymentReminders.CardReminder>,
        toBase: (Transaction) -> Double?,
        baseCurrency: String,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
        captureQuietDays: Int? = null,
        /** Converts other-currency spend toward category caps; default counts base-currency spend only. */
        capConvert: ((String, BigDecimal) -> BigDecimal?)? = null,
    ): Digest? {
        val thisWeek = DateWindow(today.minusDays(6), today)
        val lastWeek = DateWindow(today.minusDays(13), today.minusDays(7))
        fun inWeek(w: DateWindow) = transactions.filter { w.contains(it.timestampMillis, zone) }
        val thisWeekRows = inWeek(thisWeek)
        val lastWeekRows = inWeek(lastWeek)
        val thisSpend = LedgerMath.netSpend(thisWeekRows, value = toBase)
        val lastSpend = LedgerMath.netSpend(lastWeekRows, value = toBase)

        val items = mutableListOf<Item>()
        captureQuietDays?.let { items += CaptureQuiet(it) }

        // Category caps, month to date, in the base currency (as the budgets screen does).
        val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthEnd = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthSpend = CategoryBudgetCalculator.monthSpend(
            transactions, baseCurrency, monthStart, monthEnd,
            capConvert ?: CategoryBudgetCalculator.converter(baseCurrency, emptyMap()),
        )
        CategoryBudgetCalculator.statuses(categories, CategoryBudgetCalculator.rollUpSpend(categories, monthSpend.byCategory))
            .filter { it.state != CategoryBudgetCalculator.BudgetState.UNDER }
            .forEach { s ->
                items += CategoryCap(
                    category = s.category.name,
                    spent = s.spent,
                    cap = s.cap,
                    over = s.state == CategoryBudgetCalculator.BudgetState.OVER,
                    currencyCode = baseCurrency.uppercase(),
                )
            }

        SpendAnomalyDetector.scan(transactions, thisWeek, zone, max = 2).forEach { a ->
            items += UnusualCharge(a.merchant, a.amount, a.ratio, a.currencyCode)
        }

        BillReminders.pending(bills, nowMillis, emptySet(), windowDays = 7).forEach { bill ->
            items += PaymentDue(bill.name, bill.amount, bill.currencyCode.uppercase(), BillReminders.daysUntil(bill, nowMillis), isCard = false)
        }
        CardPaymentReminders.dueSoon(cards)
            .filter { (it.status.daysUntilDue ?: Long.MAX_VALUE) <= 7 && it.status.amountDue.signum() > 0 }
            .forEach { card ->
                items += PaymentDue(card.accountName, card.status.amountDue, card.currencyCode.uppercase(), card.status.daysUntilDue ?: 0, isCard = true)
            }

        val weekStartMillis = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        subscriptions.filter { !it.dismissed && it.lastSeenMillis >= weekStartMillis }.forEach { sub ->
            when {
                sub.previousAmount != null && sub.previousAmount.compareTo(sub.averageAmount) != 0 ->
                    items += PriceChange(sub.merchant, sub.previousAmount, sub.averageAmount, sub.currencyCode.uppercase())
                sub.occurrenceCount == FIRST_DETECTION_OCCURRENCES ->
                    items += NewSubscription(sub.merchant, sub.averageAmount, sub.currencyCode.uppercase())
            }
        }

        categorySwing(thisWeekRows, lastWeekRows, categories, toBase)?.let { items += it }

        if (thisSpend <= 0.0 && lastSpend <= 0.0 && items.isEmpty()) return null
        return Digest(
            thisWeekSpend = thisSpend,
            lastWeekSpend = lastSpend,
            percentChange = if (lastSpend > 0.0) (thisSpend - lastSpend) / lastSpend * 100.0 else null,
            items = items.withIndex().sortedWith(compareBy({ it.value.rank }, { it.index })).map { it.value }.take(MAX_ITEMS),
        )
    }

    /** The category with the biggest rise on last week -- at least 25% and 1 unit more, and not "Uncategorized". */
    private fun categorySwing(
        thisWeek: List<Transaction>,
        lastWeek: List<Transaction>,
        categories: List<Category>,
        toBase: (Transaction) -> Double?,
    ): CategorySwing? {
        val names = categories.associate { it.id to it.name }
        fun byCategory(rows: List<Transaction>): Map<Long, Double> = rows
            .filter { LedgerMath.affectsSpend(it) && it.categoryId != null && it.categoryId in names }
            .groupBy { it.categoryId!! }
            .mapValues { (_, txs) -> LedgerMath.netSpend(txs, value = toBase) }
        val now = byCategory(thisWeek)
        val before = byCategory(lastWeek)
        return now.mapNotNull { (id, amount) ->
            val prior = before[id] ?: 0.0
            val name = names.getValue(id)
            if (name.equals("Uncategorized", ignoreCase = true)) return@mapNotNull null
            if (amount - prior >= 1.0 && amount >= prior * 1.25) CategorySwing(name, amount, prior) else null
        }.maxByOrNull { it.thisWeek - it.lastWeek }
    }
}
