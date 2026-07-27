package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiWeeklyDigestGeneratorTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2024, 6, 15)
    private val identity: (Transaction) -> Double? = { it.amount.toDouble() }

    private fun tx(
        amount: String,
        day: LocalDate,
        merchant: String? = "Store",
        direction: TransactionDirection = TransactionDirection.DEBIT,
        excludeFromStats: Boolean = false,
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = "USD",
        direction = direction,
        merchant = merchant,
        source = TransactionSource.SMS,
        timestampMillis = day.atStartOfDay(zone).toInstant().toEpochMilli(),
        excludeFromStats = excludeFromStats,
    )

    @Test
    fun `spending less this week produces a negative percent change`() {
        val transactions = listOf(
            tx("100.00", today), // this week
            tx("200.00", today.minusDays(10)), // last week
        )
        val digest = AiWeeklyDigestGenerator.generate(transactions, identity, zone, today)!!

        assertEquals(100.0, digest.thisWeekSpend, 0.001)
        assertEquals(200.0, digest.lastWeekSpend, 0.001)
        assertEquals(-50.0, digest.percentChange!!, 0.001)
    }

    @Test
    fun `spending more this week produces a positive percent change`() {
        val transactions = listOf(
            tx("300.00", today),
            tx("200.00", today.minusDays(10)),
        )
        val digest = AiWeeklyDigestGenerator.generate(transactions, identity, zone, today)!!
        assertEquals(50.0, digest.percentChange!!, 0.001)
    }

    @Test
    fun `top spend is the single biggest expense this week`() {
        val transactions = listOf(
            tx("85.00", today, merchant = "Target"),
            tx("20.00", today.minusDays(1), merchant = "Coffee Shop"),
            tx("40.00", today.minusDays(2), merchant = "Gas Station"),
        )
        val digest = AiWeeklyDigestGenerator.generate(transactions, identity, zone, today)!!
        assertEquals("Target", digest.topSpend!!.merchant)
        assertEquals(85.0, digest.topSpend!!.amount, 0.001)
    }

    @Test
    fun `no transactions at all returns null`() {
        assertNull(AiWeeklyDigestGenerator.generate(emptyList(), identity, zone, today))
    }

    @Test
    fun `spend only this week leaves percent change undefined`() {
        val transactions = listOf(tx("50.00", today))
        val digest = AiWeeklyDigestGenerator.generate(transactions, identity, zone, today)!!
        assertEquals(50.0, digest.thisWeekSpend, 0.001)
        assertEquals(0.0, digest.lastWeekSpend, 0.001)
        assertNull(digest.percentChange)
    }

    @Test
    fun `credit transactions are excluded from spend totals`() {
        val transactions = listOf(
            tx("500.00", today, direction = TransactionDirection.CREDIT),
        )
        assertNull(AiWeeklyDigestGenerator.generate(transactions, identity, zone, today))
    }

    @Test
    fun `transactions excluded from stats are ignored`() {
        val transactions = listOf(tx("50.00", today, excludeFromStats = true))
        assertNull(AiWeeklyDigestGenerator.generate(transactions, identity, zone, today))
    }

    @Test
    fun `transactions outside the trailing 14 days are ignored`() {
        val transactions = listOf(tx("999.00", today.minusDays(30)))
        assertNull(AiWeeklyDigestGenerator.generate(transactions, identity, zone, today))
    }

    @Test
    fun `a transaction toBase cannot convert contributes nothing`() {
        val unconvertible: (Transaction) -> Double? = { null }
        val transactions = listOf(tx("50.00", today))
        assertNull(AiWeeklyDigestGenerator.generate(transactions, unconvertible, zone, today))
    }

    @Test
    fun `blank merchant falls back to Uncategorized`() {
        val transactions = listOf(tx("10.00", today, merchant = "  "))
        val digest = AiWeeklyDigestGenerator.generate(transactions, identity, zone, today)!!
        assertEquals("Uncategorized", digest.topSpend!!.merchant)
    }

    @Test
    fun `week boundaries are inclusive of exactly 7 and 13 days ago`() {
        val transactions = listOf(
            tx("10.00", today.minusDays(6)), // oldest day still "this week"
            tx("20.00", today.minusDays(7)), // newest day still "last week"
            tx("30.00", today.minusDays(13)), // oldest day still "last week"
        )
        val digest = AiWeeklyDigestGenerator.generate(transactions, identity, zone, today)!!
        assertEquals(10.0, digest.thisWeekSpend, 0.001)
        assertEquals(50.0, digest.lastWeekSpend, 0.001)
        assertTrue(digest.lastWeekSpend > 0.0)
    }
}
