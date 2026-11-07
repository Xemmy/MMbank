package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendAnomalyDetectorTest {

    private val zone = ZoneId.of("UTC")
    private val start = LocalDate.of(2026, 9, 1)

    private var nextId = 1L

    private fun charge(
        amount: String,
        dayOffset: Long,
        merchant: String? = "Careem",
        currency: String = "AED",
        kind: TransactionKind = TransactionKind.SPEND,
        excluded: Boolean = false,
        source: TransactionSource = TransactionSource.SMS,
    ) = Transaction(
        id = nextId++,
        amount = BigDecimal(amount),
        currencyCode = currency,
        direction = if (kind == TransactionKind.REFUND) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
        merchant = merchant,
        source = source,
        timestampMillis = start.plusDays(dayOffset).atStartOfDay(zone).toInstant().toEpochMilli(),
        kind = kind,
        excludeFromStats = excluded,
    )

    /** Five ordinary rides around 24. */
    private fun usualRides() = listOf("22", "24", "25", "23", "26").mapIndexed { i, a -> charge(a, i.toLong()) }

    @Test
    fun `a charge far above the usual is flagged with its ratio`() {
        val history = usualRides()
        val spike = charge("74", 10)
        val a = SpendAnomalyDetector.evaluate(spike, history + spike)!!
        assertEquals(BigDecimal("24.00"), a.usual)
        assertEquals(74.0 / 24.0, a.ratio, 1e-9)
        assertEquals("Careem", a.merchant)
        assertEquals("AED", a.currencyCode)
    }

    @Test
    fun `needs five earlier purchases`() {
        val history = usualRides().take(4)
        assertNull(SpendAnomalyDetector.evaluate(charge("200", 10), history))
    }

    @Test
    fun `an ordinary charge is not flagged`() {
        assertNull(SpendAnomalyDetector.evaluate(charge("27", 10), usualRides()))
    }

    @Test
    fun `must be at least twice the usual even when amounts never vary`() {
        // MAD is 0 here, so the median + k*MAD bound alone would flag 25.
        val flat = (0L until 6L).map { charge("20", it) }
        assertNull(SpendAnomalyDetector.evaluate(charge("35", 10), flat))
        assertNotNull(SpendAnomalyDetector.evaluate(charge("45", 10), flat))
    }

    @Test
    fun `small absolute jumps are ignored`() {
        val coffees = (0L until 6L).map { charge("4", it, merchant = "Starbucks") }
        // 4 -> 12 is 3x, but only 8 more.
        assertNull(SpendAnomalyDetector.evaluate(charge("12", 10, merchant = "Starbucks"), coffees))
    }

    @Test
    fun `a merchant whose prices vary a lot needs a bigger jump`() {
        val varied = listOf("20", "60", "35", "80", "25", "50").mapIndexed { i, a -> charge(a, i.toLong(), merchant = "Amazon") }
        // Median 42.5, MAD 17.5: 2x would be 85, but median + 4*1.4826*MAD is ~146.
        assertNull(SpendAnomalyDetector.evaluate(charge("120", 10, merchant = "Amazon"), varied))
        assertNotNull(SpendAnomalyDetector.evaluate(charge("160", 10, merchant = "Amazon"), varied))
    }

    @Test
    fun `other currencies, refunds, transfers and excluded rows never form the baseline`() {
        val noise = listOf(
            charge("5", 0, currency = "USD"), charge("5", 1, currency = "USD"),
            charge("5", 2, kind = TransactionKind.REFUND), charge("5", 3, kind = TransactionKind.TRANSFER),
            charge("5", 4, excluded = true),
        )
        // Without a real AED baseline there's nothing to compare against.
        assertNull(SpendAnomalyDetector.evaluate(charge("200", 10), noise + usualRides().take(4)))
    }

    @Test
    fun `later rows and the charge itself are left out of its baseline`() {
        val spike = charge("74", 3)
        val history = usualRides() // days 0..4, two of them after the spike
        assertNull(SpendAnomalyDetector.evaluate(spike, history + spike))
    }

    @Test
    fun `merchant matching is trimmed and case-insensitive`() {
        val history = usualRides().map { it.copy(merchant = "  CAREEM ") }
        assertNotNull(SpendAnomalyDetector.evaluate(charge("90", 10, merchant = "careem"), history))
    }

    @Test
    fun `a refund or a row with no merchant is never flagged`() {
        assertNull(SpendAnomalyDetector.evaluate(charge("90", 10, kind = TransactionKind.REFUND), usualRides()))
        assertNull(SpendAnomalyDetector.evaluate(charge("90", 10, merchant = null), usualRides()))
    }

    @Test
    fun `scan returns the strongest charges in the window`() {
        val rides = usualRides()
        val big = charge("120", 20)
        val bigger = charge("240", 21)
        val outside = charge("500", 40)
        val shopping = (0L until 5L).map { charge("100", it, merchant = "IKEA") } + charge("150", 22, merchant = "IKEA")
        val window = DateWindow(start.plusDays(15), start.plusDays(30))
        val found = SpendAnomalyDetector.scan(rides + big + bigger + outside + shopping, window, zone, max = 5)
        assertEquals(listOf(bigger, big), found.map { it.transaction })
        assertEquals(1, SpendAnomalyDetector.scan(rides + big + bigger, window, zone, max = 1).size)
    }

    @Test
    fun `alerts only for fresh live captures the user opted into`() {
        val tx = charge("90", 0)
        val now = tx.timestampMillis + 60_000
        assertTrue(SpendAnomalyDetector.shouldAlert(tx, now, enabled = true))
        assertFalse(SpendAnomalyDetector.shouldAlert(tx, now, enabled = false))
        assertFalse(SpendAnomalyDetector.shouldAlert(tx.copy(source = TransactionSource.PASTED), now, enabled = true))
        assertFalse(SpendAnomalyDetector.shouldAlert(tx.copy(source = TransactionSource.MANUAL), now, enabled = true))
        assertFalse(SpendAnomalyDetector.shouldAlert(tx, tx.timestampMillis + 3L * 24 * 60 * 60 * 1000, enabled = true))
        // A post time slightly ahead of the device clock still counts.
        assertTrue(SpendAnomalyDetector.shouldAlert(tx, tx.timestampMillis - 60_000, enabled = true))
    }

    @Test
    fun `median`() {
        assertEquals(2.0, SpendAnomalyDetector.median(listOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(2.5, SpendAnomalyDetector.median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
        assertEquals(0.0, SpendAnomalyDetector.median(emptyList()), 0.0)
    }
}
