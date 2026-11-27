package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CashFlowForecasterTest {

    private val zone = ZoneId.of("UTC")
    private val dayMillis = TimeUnit.DAYS.toMillis(1)
    private val baseTime = 1_700_000_000_000L // 2023-11-14T22:13:20Z

    private fun tx(
        amount: String,
        daysAgo: Long,
        direction: TransactionDirection = TransactionDirection.CREDIT,
        currencyCode: String = "AED",
        excludeFromStats: Boolean = false,
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = currencyCode,
        direction = direction,
        source = TransactionSource.SMS,
        timestampMillis = baseTime - daysAgo * dayMillis,
        excludeFromStats = excludeFromStats,
    )

    @Test
    fun `detects a monthly salary pattern from large recurring credits`() {
        val transactions = listOf(
            tx("5000.00", daysAgo = 60),
            tx("5000.00", daysAgo = 30),
            tx("5000.00", daysAgo = 0),
        )
        val pattern = CashFlowForecaster.detectIncomePattern(transactions, "AED")

        requireNotNull(pattern)
        assertEquals(0, pattern.averageAmount.compareTo(BigDecimal("5000.00")))
        assertEquals(30L, pattern.averageIntervalDays)
        assertEquals(3, pattern.occurrenceCount)
        assertEquals(baseTime, pattern.lastOccurrenceMillis)
    }

    @Test
    fun `credits below the minimum income amount are ignored`() {
        val transactions = listOf(
            tx("100.00", daysAgo = 30),
            tx("100.00", daysAgo = 0),
        )
        assertNull(CashFlowForecaster.detectIncomePattern(transactions, "AED"))
    }

    @Test
    fun `debits never count toward the income pattern`() {
        val transactions = listOf(
            tx("5000.00", daysAgo = 30, direction = TransactionDirection.DEBIT),
            tx("5000.00", daysAgo = 0, direction = TransactionDirection.DEBIT),
        )
        assertNull(CashFlowForecaster.detectIncomePattern(transactions, "AED"))
    }

    @Test
    fun `excluded transactions never count toward the income pattern`() {
        val transactions = listOf(
            tx("5000.00", daysAgo = 30, excludeFromStats = true),
            tx("5000.00", daysAgo = 0, excludeFromStats = true),
        )
        assertNull(CashFlowForecaster.detectIncomePattern(transactions, "AED"))
    }

    @Test
    fun `a single qualifying credit is not enough to detect a pattern`() {
        val transactions = listOf(tx("5000.00", daysAgo = 0))
        assertNull(CashFlowForecaster.detectIncomePattern(transactions, "AED"))
    }

    @Test
    fun `manual payday override wins over the detected pattern`() {
        val pattern = CashFlowForecaster.IncomePattern(
            averageAmount = BigDecimal("5000.00"),
            currencyCode = "AED",
            averageIntervalDays = 30,
            lastOccurrenceMillis = baseTime,
            occurrenceCount = 3,
        )
        val today = LocalDate.of(2026, 7, 10)
        val next = CashFlowForecaster.projectNextPayday(pattern, manualPaydayOfMonth = 25, today, zone)
        assertEquals(LocalDate.of(2026, 7, 25), next)
    }

    @Test
    fun `manual payday rolls to next month once this month's day has passed`() {
        val today = LocalDate.of(2026, 7, 28)
        val next = CashFlowForecaster.projectNextPayday(null, manualPaydayOfMonth = 1, today, zone)
        assertEquals(LocalDate.of(2026, 8, 1), next)
    }

    @Test
    fun `next payday projects forward from the detected pattern when no override is set`() {
        val today = LocalDate.of(2023, 11, 20)
        val pattern = CashFlowForecaster.IncomePattern(
            averageAmount = BigDecimal("5000.00"),
            currencyCode = "AED",
            averageIntervalDays = 30,
            lastOccurrenceMillis = baseTime, // 2023-11-14
            occurrenceCount = 3,
        )
        val next = CashFlowForecaster.projectNextPayday(pattern, manualPaydayOfMonth = null, today, zone)
        assertEquals(LocalDate.of(2023, 12, 14), next)
    }

    @Test
    fun `no pattern and no override yields no payday`() {
        assertNull(CashFlowForecaster.projectNextPayday(null, null, LocalDate.of(2026, 1, 1), zone))
    }

    @Test
    fun `safe to spend subtracts mandatory bills from liquid balance`() {
        val result = CashFlowForecaster.computeSafeToSpend(
            currentLiquidBalance = BigDecimal("3000.00"),
            mandatoryBillsDueBeforePayday = BigDecimal("1000.00"),
            nextPayday = LocalDate.of(2026, 7, 30),
            today = LocalDate.of(2026, 7, 20),
            zone = zone,
        )
        assertEquals(0, result.safeToSpend.compareTo(BigDecimal("2000.00")))
        assertEquals(10, result.daysUntilPayday)
        assertEquals(0, result.dailyAllowance.compareTo(BigDecimal("200.00")))
    }

    @Test
    fun `safe to spend floors at zero when bills exceed the liquid balance`() {
        val result = CashFlowForecaster.computeSafeToSpend(
            currentLiquidBalance = BigDecimal("500.00"),
            mandatoryBillsDueBeforePayday = BigDecimal("900.00"),
            nextPayday = LocalDate.of(2026, 7, 25),
            today = LocalDate.of(2026, 7, 20),
            zone = zone,
        )
        assertEquals(0, result.safeToSpend.compareTo(BigDecimal.ZERO))
        assertEquals(0, result.dailyAllowance.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `days until payday is never less than one even when payday is today`() {
        val result = CashFlowForecaster.computeSafeToSpend(
            currentLiquidBalance = BigDecimal("1000.00"),
            mandatoryBillsDueBeforePayday = BigDecimal.ZERO,
            nextPayday = LocalDate.of(2026, 7, 20),
            today = LocalDate.of(2026, 7, 20),
            zone = zone,
        )
        assertEquals(1, result.daysUntilPayday)
        assertTrue(result.dailyAllowance.compareTo(BigDecimal.ZERO) > 0)
    }
}
