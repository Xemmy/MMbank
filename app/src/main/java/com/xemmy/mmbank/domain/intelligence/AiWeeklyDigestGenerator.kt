package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Generates the Dashboard's weekly summary card: this week's spend vs last
 * week's, and the single biggest expense this week — e.g. "Spent 12% less
 * this week ($320 vs $365). Top spend: $85 at Target." Pure and top-level so
 * it's JVM-testable; formatting (money symbols, hide-balances masking) is a
 * UI-layer concern, same split as [com.xemmy.mmbank.ui.screens.dashboard.SmartInsight].
 */
object AiWeeklyDigestGenerator {

    /** The largest single expense of the week, already converted to the caller's display currency. */
    data class TopSpend(val merchant: String, val amount: Double)

    data class WeeklyDigest(
        val thisWeekSpend: Double,
        val lastWeekSpend: Double,
        /** Positive = spent more than last week, negative = spent less; null when there's no prior week to compare. */
        val percentChange: Double?,
        val topSpend: TopSpend?,
    )

    /**
     * Compares the trailing 7 days (including [today]) against the 7 days
     * immediately before that. [toBase] converts a transaction's amount into
     * the caller's display currency (a hook rather than a hard FX dependency,
     * so this stays framework-free); a transaction [toBase] can't convert
     * contributes nothing, same as the Dashboard's own stats. Returns null
     * when neither window has any spend to report on.
     */
    fun generate(
        transactions: List<Transaction>,
        toBase: (Transaction) -> Double?,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): WeeklyDigest? {
        val thisWeekStart = today.minusDays(6)
        val lastWeekStart = today.minusDays(13)
        val lastWeekEnd = today.minusDays(7)

        fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

        val debits = transactions.filter { LedgerMath.isSpend(it) }
        val thisWeek = debits.filter { tx ->
            val day = tx.timestampMillis.toLocalDate()
            day >= thisWeekStart && day <= today
        }
        val lastWeek = debits.filter { tx ->
            val day = tx.timestampMillis.toLocalDate()
            day >= lastWeekStart && day <= lastWeekEnd
        }

        val thisWeekSpend = thisWeek.sumOf { toBase(it) ?: 0.0 }
        val lastWeekSpend = lastWeek.sumOf { toBase(it) ?: 0.0 }
        if (thisWeekSpend <= 0.0 && lastWeekSpend <= 0.0) return null

        val percentChange = if (lastWeekSpend > 0.0) {
            (thisWeekSpend - lastWeekSpend) / lastWeekSpend * 100.0
        } else {
            null
        }

        val topSpend = thisWeek
            .mapNotNull { tx -> toBase(tx)?.let { tx to it } }
            .maxByOrNull { (_, amount) -> amount }
            ?.let { (tx, amount) ->
                TopSpend(tx.merchant?.trim()?.takeIf { it.isNotEmpty() } ?: "Uncategorized", amount)
            }

        return WeeklyDigest(
            thisWeekSpend = thisWeekSpend,
            lastWeekSpend = lastWeekSpend,
            percentChange = percentChange,
            topSpend = topSpend,
        )
    }
}
