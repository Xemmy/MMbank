package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantProfilerTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun tx(
        amount: String,
        merchant: String? = "Netflix",
        at: Long,
        direction: TransactionDirection = TransactionDirection.DEBIT,
        currencyCode: String = "AED",
        excludeFromStats: Boolean = false,
        isDemo: Boolean = false,
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = currencyCode,
        direction = direction,
        merchant = merchant,
        source = TransactionSource.SMS,
        timestampMillis = at,
        excludeFromStats = excludeFromStats,
        isDemo = isDemo,
    )

    private fun millisFor(year: Int, month: Int, day: Int): Long =
        Instant.parse("%04d-%02d-%02dT00:00:00Z".format(year, month, day)).toEpochMilli()

    @Test
    fun `aggregates total, count, average, min and max`() {
        val transactions = listOf(
            tx("39.99", at = millisFor(2025, 1, 5)),
            tx("39.99", at = millisFor(2025, 2, 5)),
            tx("56.00", at = millisFor(2025, 3, 5)),
        )
        val profile = MerchantProfiler.profile(transactions, "Netflix", zone)!!
        assertEquals(0, profile.totalSpent.compareTo(BigDecimal("135.98")))
        assertEquals(3, profile.transactionCount)
        assertEquals(0, profile.averageAmount.compareTo(BigDecimal("45.33")))
        assertEquals(0, profile.minAmount.compareTo(BigDecimal("39.99")))
        assertEquals(0, profile.maxAmount.compareTo(BigDecimal("56.00")))
    }

    @Test
    fun `no matching history returns null`() {
        val transactions = listOf(tx("10.00", merchant = "Spotify", at = millisFor(2025, 1, 5)))
        assertNull(MerchantProfiler.profile(transactions, "Netflix", zone))
    }

    @Test
    fun `empty transaction list returns null`() {
        assertNull(MerchantProfiler.profile(emptyList(), "Netflix", zone))
    }

    @Test
    fun `merchant match is case-insensitive and trims whitespace`() {
        val transactions = listOf(tx("10.00", merchant = "  NETFLIX  ", at = millisFor(2025, 1, 5)))
        val profile = MerchantProfiler.profile(transactions, "netflix", zone)
        assertTrue(profile != null)
        assertEquals(1, profile!!.transactionCount)
    }

    @Test
    fun `credit rows are excluded`() {
        val transactions = listOf(
            tx("10.00", at = millisFor(2025, 1, 5)),
            tx("500.00", at = millisFor(2025, 1, 6), direction = TransactionDirection.CREDIT),
        )
        val profile = MerchantProfiler.profile(transactions, "Netflix", zone)!!
        assertEquals(1, profile.transactionCount)
    }

    @Test
    fun `excludeFromStats rows are excluded`() {
        val transactions = listOf(
            tx("10.00", at = millisFor(2025, 1, 5)),
            tx("999.00", at = millisFor(2025, 1, 6), excludeFromStats = true),
        )
        val profile = MerchantProfiler.profile(transactions, "Netflix", zone)!!
        assertEquals(1, profile.transactionCount)
    }

    @Test
    fun `demo rows are excluded`() {
        val transactions = listOf(
            tx("10.00", at = millisFor(2025, 1, 5)),
            tx("999.00", at = millisFor(2025, 1, 6), isDemo = true),
        )
        val profile = MerchantProfiler.profile(transactions, "Netflix", zone)!!
        assertEquals(1, profile.transactionCount)
    }

    @Test
    fun `other merchants are excluded`() {
        val transactions = listOf(
            tx("10.00", merchant = "Netflix", at = millisFor(2025, 1, 5)),
            tx("20.00", merchant = "Spotify", at = millisFor(2025, 1, 6)),
        )
        val profile = MerchantProfiler.profile(transactions, "Netflix", zone)!!
        assertEquals(1, profile.transactionCount)
        assertEquals(0, profile.totalSpent.compareTo(BigDecimal("10.00")))
    }

    @Test
    fun `dominant currency wins and the other is left out`() {
        val transactions = listOf(
            tx("10.00", at = millisFor(2025, 1, 5), currencyCode = "AED"),
            tx("11.00", at = millisFor(2025, 2, 5), currencyCode = "AED"),
            tx("3.00", at = millisFor(2025, 3, 5), currencyCode = "USD"),
        )
        val profile = MerchantProfiler.profile(transactions, "Netflix", zone)!!
        assertEquals("AED", profile.currencyCode)
        assertEquals(2, profile.transactionCount)
        assertEquals(0, profile.totalSpent.compareTo(BigDecimal("21.00")))
    }

    @Test
    fun `single transaction reports itself as min, max and average`() {
        val transactions = listOf(tx("42.50", at = millisFor(2025, 1, 5)))
        val profile = MerchantProfiler.profile(transactions, "Netflix", zone)!!
        assertEquals(0, profile.minAmount.compareTo(BigDecimal("42.50")))
        assertEquals(0, profile.maxAmount.compareTo(BigDecimal("42.50")))
        assertEquals(0, profile.averageAmount.compareTo(BigDecimal("42.50")))
    }

    @Test
    fun `first and last seen track the earliest and latest timestamps`() {
        val transactions = listOf(
            tx("10.00", at = millisFor(2025, 3, 5)),
            tx("10.00", at = millisFor(2025, 1, 5)),
            tx("10.00", at = millisFor(2025, 2, 5)),
        )
        val profile = MerchantProfiler.profile(transactions, "Netflix", zone)!!
        assertEquals(millisFor(2025, 1, 5), profile.firstSeenMillis)
        assertEquals(millisFor(2025, 3, 5), profile.lastSeenMillis)
    }

    @Test
    fun `monthly series buckets and sorts across a year boundary`() {
        val transactions = listOf(
            tx("10.00", at = millisFor(2025, 12, 5)),
            tx("20.00", at = millisFor(2026, 1, 5)),
            tx("5.00", at = millisFor(2025, 12, 20)),
        )
        val profile = MerchantProfiler.profile(transactions, "Netflix", zone)!!
        assertEquals(2, profile.monthlySeries.size)
        assertEquals(YearMonth.of(2025, 12), profile.monthlySeries[0].yearMonth)
        assertEquals(YearMonth.of(2026, 1), profile.monthlySeries[1].yearMonth)
        assertEquals(0, profile.monthlySeries[0].total.compareTo(BigDecimal("15.00")))
        assertEquals(0, profile.monthlySeries[1].total.compareTo(BigDecimal("20.00")))
    }
}
