package com.xemmy.mmbank.domain.report

import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Transaction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What one calendar day added up to, by the one definition of Spent and Received. */
data class DayTotal(val day: LocalDate, val spent: Double, val received: Double) {
    /** Money in minus money out; transfers, card payments and adjustments are in neither. */
    val net: Double get() = received - spent

    /** -1, 0 or +1, ignoring sub-cent noise, for choosing a "+" or "−" in front of the amount. */
    val netSign: Int get() = if (net > 0.005) 1 else if (net < -0.005) -1 else 0
}

object DayTotals {
    /**
     * Totals per day over [transactions], in first-seen day order. [value] converts a row to one
     * currency (null = skipped, like every other total). A day with only uncounted rows gets 0 / 0.
     */
    fun byDay(
        transactions: List<Transaction>,
        zone: ZoneId,
        raw: Boolean = false,
        value: (Transaction) -> Double?,
    ): Map<LocalDate, DayTotal> {
        val out = LinkedHashMap<LocalDate, DayTotal>()
        transactions.groupBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }
            .forEach { (day, rows) ->
                out[day] = DayTotal(
                    day = day,
                    spent = LedgerMath.netSpend(rows, raw, value),
                    received = LedgerMath.income(rows, raw, value),
                )
            }
        return out
    }
}
