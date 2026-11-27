package com.xemmy.mmbank.domain.intelligence.copilot

import com.xemmy.mmbank.domain.filter.ActivityDrillDown
import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.parser.MerchantNormalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A Copilot reply computed on the phone, with an optional "See transactions" target. */
data class CopilotAnswer(val text: String, val drillDown: ActivityDrillDown? = null)

/**
 * Answers a [CopilotQuery] from the ledger, with the same definitions every
 * other screen uses: spending and income come from [LedgerMath] (so
 * transfers, card payments and excluded rows never count, and refunds net
 * off purchases), and amounts are converted to the display currency through
 * the caller's [toBase] hook.
 *
 * A row [toBase] can't convert is not silently counted as zero: the answer
 * says how many rows, in which currency, were left out.
 */
object CopilotAnswerEngine {

    fun answer(
        query: CopilotQuery,
        transactions: List<Transaction>,
        categories: List<Category>,
        toBase: (Transaction) -> Double?,
        currencyCode: String,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        formatMoney: (Double) -> String = { defaultMoney(currencyCode, it) },
    ): CopilotAnswer {
        val ctx = Ctx(query, transactions, categories, toBase, today, zone, formatMoney)
        val answer = when (query.intent) {
            CopilotIntent.SPEND -> ctx.spend()
            CopilotIntent.INCOME -> ctx.income()
            CopilotIntent.TOP_CATEGORIES -> ctx.topCategories()
            CopilotIntent.TOP_MERCHANTS -> ctx.topMerchants()
            CopilotIntent.BIGGEST_EXPENSE -> ctx.biggestExpense()
            CopilotIntent.COMPARE -> ctx.compare()
            CopilotIntent.SAVINGS_RATE -> ctx.savingsRate()
            CopilotIntent.TRANSACTION_COUNT -> ctx.count()
            CopilotIntent.AVERAGE_PER_DAY -> ctx.averagePerDay()
        }
        val note = ctx.unconvertedNote()
        return if (note == null) answer else answer.copy(text = answer.text + " " + note)
    }

    /**
     * The window just before [window], and how to name it in a comparison.
     * A month-to-date window compares with the same days of the previous
     * month (a partial month against a full one would always read as "less");
     * a full month with the full previous month; likewise weeks and years.
     */
    fun previousWindow(
        window: DateWindow,
        today: LocalDate,
        rangeLabel: String? = null,
    ): Pair<DateWindow, String> {
        val start = window.start
        val end = window.end
        val sameMonth = start.year == end.year && start.month == end.month
        // A one-day window ("today" on the 1st or on a Monday) is a day, not a
        // month or week to date -- unless the question said "this month/year".
        val asDay = start == end && rangeLabel != "this month" && rangeLabel != "this year"
        val yearToDate = rangeLabel == "this year"
        if (!asDay && !yearToDate && start.dayOfMonth == 1 && sameMonth) {
            val prevStart = start.minusMonths(1)
            val full = end == end.withDayOfMonth(end.lengthOfMonth())
            val prevEnd = if (full) prevStart.withDayOfMonth(prevStart.lengthOfMonth()) else end.minusMonths(1)
            val name = monthName(prevStart, today)
            return DateWindow(prevStart, prevEnd) to if (full) "in $name" else "in the same days of $name"
        }
        if (!asDay && start.dayOfYear == 1 && start.year == end.year) {
            val prevStart = start.minusYears(1)
            val full = end.dayOfYear == end.lengthOfYear()
            val prevEnd = if (full) LocalDate.of(prevStart.year, 12, 31) else end.minusYears(1)
            return DateWindow(prevStart, prevEnd) to
                if (full) "in ${prevStart.year}" else "in the same part of ${prevStart.year}"
        }
        val days = ChronoUnit.DAYS.between(start, end) + 1
        if (!asDay && start.dayOfWeek == DayOfWeek.MONDAY && days <= 7) {
            return DateWindow(start.minusDays(7), end.minusDays(7)) to
                if (days == 7L) "the week before" else "in the same days of the week before"
        }
        return DateWindow(start.minusDays(days), start.minusDays(1)) to
            if (days == 1L) "the day before" else "in the $days days before"
    }

    // --------------------------------------------------------------- context

    private class Ctx(
        val query: CopilotQuery,
        val all: List<Transaction>,
        val categories: List<Category>,
        val toBase: (Transaction) -> Double?,
        val today: LocalDate,
        val zone: ZoneId,
        val money: (Double) -> String,
    ) {
        private val missing = sortedMapOf<String, Int>()
        private val counted: MutableSet<Transaction> =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

        /** Converts [tx], remembering (once per row) any that can't be. */
        fun value(tx: Transaction): Double? {
            val v = toBase(tx)
            if (v == null && counted.add(tx)) {
                missing.merge(tx.currencyCode.uppercase(), 1, Int::plus)
            }
            return v
        }

        fun scope(window: DateWindow = query.window): List<Transaction> =
            all.filter { window.contains(it.timestampMillis, zone) && matchesSubject(it) }

        private fun matchesSubject(tx: Transaction): Boolean = when (val s = query.subject) {
            null -> true
            is CopilotSubject.CategorySubject ->
                tx.categoryId in s.categoryIds ||
                    (tx.categoryId == null && s.label.equals(UNCATEGORIZED, ignoreCase = true))
            is CopilotSubject.MerchantSubject -> {
                val m = tx.merchant?.trim().orEmpty()
                m.isNotEmpty() && (m.lowercase().contains(s.phrase) || MerchantNormalizer.key(m) == s.key)
            }
        }

        val range: String get() = query.rangeLabel

        /** " on Dining" / " at Careem" / "" -- slotted after the verb. */
        val about: String
            get() = when (val s = query.subject) {
                null -> ""
                is CopilotSubject.CategorySubject -> " on ${s.label}"
                is CopilotSubject.MerchantSubject -> " at ${s.label}"
            }

        fun drill(window: DateWindow = query.window, extraQuery: String? = null): ActivityDrillDown {
            val s = query.subject
            return ActivityDrillDown(
                categoryIds = (s as? CopilotSubject.CategorySubject)?.categoryIds.orEmpty(),
                from = window.start,
                to = window.end,
                query = extraQuery ?: (s as? CopilotSubject.MerchantSubject)?.phrase.orEmpty(),
            )
        }

        fun spendOf(rows: List<Transaction>): Double = LedgerMath.netSpend(rows) { value(it) }

        // ------------------------------------------------------------ intents

        fun spend(): CopilotAnswer {
            val rows = scope()
            val purchases = rows.count { LedgerMath.isSpend(it) }
            val total = spendOf(rows)
            val text = if (purchases == 0 && total == 0.0) {
                when (query.subject) {
                    null -> "You haven't spent anything $range."
                    is CopilotSubject.CategorySubject -> "Nothing spent$about $range."
                    is CopilotSubject.MerchantSubject -> "No purchases$about $range."
                }
            } else {
                "You spent ${money(total)}$about $range (${plural(purchases, "purchase")})."
            }
            return CopilotAnswer(text, drill())
        }

        fun income(): CopilotAnswer {
            val rows = scope().filter { LedgerMath.isIncome(it) }
            val total = LedgerMath.income(rows) { value(it) }
            val from = (query.subject as? CopilotSubject.MerchantSubject)?.let { " from ${it.label}" }.orEmpty()
            val text = if (rows.isEmpty()) {
                "No money received$from $range."
            } else {
                "You received ${money(total)}$from $range (${plural(rows.size, "payment")} in)."
            }
            return CopilotAnswer(text, drill())
        }

        fun topCategories(): CopilotAnswer {
            val rows = scope().filter { LedgerMath.affectsSpend(it) }
            val byId = categories.associateBy { it.id }
            val totals = rows.groupBy { it.categoryId?.takeIf(byId::containsKey) }
                .mapValues { (_, txs) -> spendOf(txs) }
                .filterValues { it > 0.0 }
                .toList()
                .sortedByDescending { it.second }
            if (totals.isEmpty()) return CopilotAnswer("No spending $range yet.")
            val grand = totals.sumOf { it.second }
            val lines = totals.take(TOP_N).joinToString(", ") { (id, amount) ->
                "${id?.let { byId.getValue(it).name } ?: UNCATEGORIZED} ${money(amount)} (${percent(amount / grand * 100)})"
            }
            val topId = totals.first().first
            val drill = ActivityDrillDown(
                categoryIds = topId?.let { setOf(it) }.orEmpty(),
                from = query.window.start,
                to = query.window.end,
                query = (query.subject as? CopilotSubject.MerchantSubject)?.phrase.orEmpty(),
            )
            return CopilotAnswer("Your top categories$about ${range}: $lines.", drill)
        }

        fun topMerchants(): CopilotAnswer {
            val rows = scope().filter { LedgerMath.affectsSpend(it) && !it.merchant.isNullOrBlank() }
            val groups = rows.groupBy { it.merchant!!.trim().lowercase() }
            val totals = groups.mapValues { (_, txs) -> spendOf(txs) }
                .filterValues { it > 0.0 }
                .toList()
                .sortedByDescending { it.second }
            if (totals.isEmpty()) return CopilotAnswer("No purchases with a merchant $range yet.")
            val lines = totals.take(TOP_N).joinToString(", ") { (key, amount) ->
                val txs = groups.getValue(key)
                val name = txs.groupingBy { it.merchant!!.trim() }.eachCount().maxBy { it.value }.key
                "$name ${money(amount)} (${txs.count { LedgerMath.isSpend(it) }}×)"
            }
            val top = totals.first().first
            val where = if (query.subject is CopilotSubject.CategorySubject) about else ""
            return CopilotAnswer("Where your money went$where ${range}: $lines.", drill(extraQuery = top))
        }

        fun biggestExpense(): CopilotAnswer {
            val best = scope().filter { LedgerMath.isSpend(it) }
                .mapNotNull { tx -> value(tx)?.let { tx to it } }
                .maxByOrNull { it.second }
                ?: return CopilotAnswer("No purchases$about $range.")
            val (tx, amount) = best
            val day = Instant.ofEpochMilli(tx.timestampMillis).atZone(zone).toLocalDate()
            val where = tx.merchant?.trim()?.takeIf { it.isNotEmpty() }?.let { " at $it" }.orEmpty()
            val text = "Your biggest expense$about $range was ${money(amount)}$where on ${dayLabel(day, today)}."
            return CopilotAnswer(
                text,
                ActivityDrillDown(from = day, to = day, query = tx.merchant?.trim().orEmpty()),
            )
        }

        fun compare(): CopilotAnswer {
            val (prevWindow, prevLabel) = previousWindow(query.window, today, query.rangeLabel)
            val current = spendOf(scope())
            val previous = spendOf(scope(prevWindow))
            val text = when {
                current <= 0.0 && previous <= 0.0 -> "No spending$about $range or $prevLabel."
                previous <= 0.0 -> "You spent ${money(current)}$about $range, and nothing $prevLabel."
                else -> {
                    val change = (current - previous) / previous * 100
                    val how = when {
                        kotlin.math.abs(change) < 1.0 -> "about the same as"
                        change > 0 -> "${percent(change)} more than"
                        else -> "${percent(-change)} less than"
                    }
                    "You spent ${money(current)}$about $range, $how the ${money(previous)} $prevLabel."
                }
            }
            return CopilotAnswer(text, drill())
        }

        fun savingsRate(): CopilotAnswer {
            val rows = scope()
            val income = LedgerMath.income(rows) { value(it) }
            val spent = spendOf(rows)
            val lead = range.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
            val text = when {
                income <= 0.0 -> "No money received $range, so there's no savings rate yet; you spent ${money(spent)}."
                spent <= income -> {
                    val kept = income - spent
                    "$lead, you received ${money(income)} and spent ${money(spent)}: " +
                        "you kept ${percent(kept / income * 100)} (${money(kept)})."
                }
                else -> "$lead, you spent ${money(spent)}, ${money(spent - income)} more than the ${money(income)} you received."
            }
            return CopilotAnswer(text, drill())
        }

        fun count(): CopilotAnswer {
            val n = scope().count { LedgerMath.isSpend(it) }
            val text = if (n == 0) "No purchases$about $range." else "You made ${plural(n, "purchase")}$about $range."
            return CopilotAnswer(text, drill())
        }

        fun averagePerDay(): CopilotAnswer {
            val end = minOf(query.window.end, today)
            // "All time" starts in 1970; dividing by those days gives a
            // meaningless average. Count from the first day the ledger has.
            val firstDay = all.minOfOrNull { it.timestampMillis }
                ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            val start = if (firstDay != null && firstDay.isAfter(query.window.start)) firstDay else query.window.start
            val days = if (end.isBefore(start)) 0L else ChronoUnit.DAYS.between(start, end) + 1
            val total = spendOf(scope())
            if (days <= 0L || total <= 0.0) return CopilotAnswer("Nothing spent$about $range yet.", drill())
            val avg = total / days
            return CopilotAnswer(
                "You spent ${money(avg)} a day on average$about $range (${money(total)} over ${plural(days.toInt(), "day")}).",
                drill(),
            )
        }

        fun unconvertedNote(): String? {
            if (missing.isEmpty()) return null
            val parts = missing.entries.joinToString(" and ") { (code, n) -> plural(n, "$code transaction") }
            return "Not counted: $parts (no exchange rate yet)."
        }
    }

    // --------------------------------------------------------------- helpers

    private const val TOP_N = 3
    private const val UNCATEGORIZED = "Uncategorized"

    fun defaultMoney(currencyCode: String, amount: Double): String =
        "${currencyCode.uppercase()} " + String.format(Locale.US, "%,.2f", amount)

    private fun percent(value: Double): String = String.format(Locale.US, "%.0f%%", value)

    private fun plural(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

    private fun monthName(day: LocalDate, today: LocalDate): String {
        val name = day.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return if (day.year == today.year) name else "$name ${day.year}"
    }

    private val DAY_SAME_YEAR = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val DAY_OTHER_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    private fun dayLabel(day: LocalDate, today: LocalDate): String =
        (if (day.year == today.year) DAY_SAME_YEAR else DAY_OTHER_YEAR).format(day)
}
