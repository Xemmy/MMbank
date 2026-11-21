package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Transaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Search used by the charge pickers: free text over merchant, SMS text and note,
 * a numeric query over the amount, and an inclusive date range.
 */
object ChargeFilter {

    /** A query that is only a number ("39.99", "40", "1,200") searches amounts, not text. */
    fun numericQuery(query: String): String? {
        val q = query.trim().replace(",", "")
        return q.takeIf { it.isNotEmpty() && it.matches(Regex("\\d+(\\.\\d*)?")) }
    }

    fun matches(
        tx: Transaction,
        query: String = "",
        from: LocalDate? = null,
        to: LocalDate? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (from != null || to != null) {
            val day = Instant.ofEpochMilli(tx.timestampMillis).atZone(zone).toLocalDate()
            if (from != null && day.isBefore(from)) return false
            if (to != null && day.isAfter(to)) return false
        }
        val q = query.trim()
        if (q.isEmpty()) return true
        val number = numericQuery(q)
        if (number != null) return amountMatches(tx.amount, number)
        val needle = q.lowercase()
        return listOfNotNull(tx.merchant, tx.rawMessage, tx.note).any { it.lowercase().contains(needle) }
    }

    /** "39.99" matches 39.99 exactly (any scale); "40" or "40." is a prefix of the plain amount, so 40.00 and 40.50 match. */
    private fun amountMatches(amount: BigDecimal, number: String): Boolean {
        val plain = amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
        if (plain.startsWith(number)) return true
        val typed = number.toBigDecimalOrNull() ?: return false
        return typed.compareTo(amount) == 0
    }

    /** A short single-line excerpt of the SMS for list rows, or null with none. */
    fun snippet(tx: Transaction, max: Int = 90): String? {
        val text = tx.rawMessage?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
    }

    /** Quick date ranges for the charge search chips; null bounds are open. */
    enum class Preset(val label: String) {
        ALL("All"), THIS_MONTH("This month"), LAST_3_MONTHS("Last 3 months"), THIS_YEAR("This year");

        fun range(today: LocalDate): Pair<LocalDate?, LocalDate?> = when (this) {
            ALL -> null to null
            THIS_MONTH -> today.withDayOfMonth(1) to today
            LAST_3_MONTHS -> today.minusMonths(3) to today
            THIS_YEAR -> today.withDayOfYear(1) to today
        }
    }
}
