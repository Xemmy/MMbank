package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionSource

/**
 * Which captured rows may raise a budget alert. An alert is about money that just went out,
 * so only a live capture (SMS or a notification, not a pasted backlog, a manual entry or a
 * sync) from the last day qualifies: pasting last month's messages must not announce last
 * month's overspend, nor reset this month's "already alerted" keys.
 *
 * Daily pacing compares the amount with the monthly budget, which is in the default
 * currency, so a spend in another currency is left out rather than compared at face value
 * (a JPY 10,000 purchase against an AED 3,000 budget). Pure and JVM-testable.
 */
object CaptureAlertGate {

    const val FRESH_MILLIS: Long = 24L * 60 * 60 * 1000

    /** A notification's post time can run slightly ahead of this device's clock. */
    const val CLOCK_SKEW_MILLIS: Long = 5L * 60 * 1000

    private val LIVE_SOURCES = setOf(TransactionSource.SMS, TransactionSource.PUSH_NOTIFICATION)

    fun isLiveAndFresh(tx: Transaction, nowMillis: Long): Boolean =
        tx.source in LIVE_SOURCES && nowMillis - tx.timestampMillis in -CLOCK_SKEW_MILLIS..FRESH_MILLIS

    /** The daily-budget pacing check applies to this row. */
    fun pacesDailyBudget(tx: Transaction, nowMillis: Long, baseCurrency: String): Boolean =
        LedgerMath.isSpend(tx) && isLiveAndFresh(tx, nowMillis) && tx.currencyCode.equals(baseCurrency.trim(), ignoreCase = true)

    /** The per-category monthly cap check applies to this row. */
    fun checksCategoryCap(tx: Transaction, nowMillis: Long): Boolean =
        LedgerMath.isSpend(tx) && tx.categoryId != null && isLiveAndFresh(tx, nowMillis)
}
