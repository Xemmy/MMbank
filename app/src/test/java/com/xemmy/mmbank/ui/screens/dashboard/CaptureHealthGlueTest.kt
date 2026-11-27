package com.xemmy.mmbank.ui.screens.dashboard

import com.xemmy.mmbank.domain.intelligence.CaptureHealthEvaluator
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureHealthGlueTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day
    private val access = CaptureAccessSnapshot(notificationAccess = true, sms = true, batteryExempt = true, checkedAt = now)

    private fun row(createdAt: Long, source: TransactionSource, demo: Boolean = false) = Transaction(
        amount = BigDecimal.TEN,
        currencyCode = "AED",
        direction = TransactionDirection.DEBIT,
        source = source,
        timestampMillis = createdAt,
        createdAt = createdAt,
        isDemo = demo,
    )

    private fun health(rows: List<Transaction>) =
        computeCaptureHealth(rows, access, now - 60_000, now - 10 * day, 0, 0, now).state

    @Test
    fun `live captures that stopped read as quiet`() {
        val rows = (0 until 10).map { row(now - 6 * day - it * day, TransactionSource.SMS) }
        assertEquals(CaptureHealthEvaluator.State.QUIET, health(rows))
    }

    @Test
    fun `pasted, manual and demo rows never prove capture is working`() {
        val old = (0 until 10).map { row(now - 6 * day - it * day, TransactionSource.PUSH_NOTIFICATION) }
        val recentNonLive = listOf(
            row(now - day, TransactionSource.PASTED),
            row(now - day, TransactionSource.MANUAL),
            row(now - day, TransactionSource.SMS, demo = true),
        )
        assertEquals(CaptureHealthEvaluator.State.QUIET, health(old + recentNonLive))
    }
}
