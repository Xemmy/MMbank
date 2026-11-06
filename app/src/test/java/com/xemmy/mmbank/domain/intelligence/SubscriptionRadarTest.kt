package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRadarTest {

    private val dayMillis = TimeUnit.DAYS.toMillis(1)
    private val baseTime = 1_700_000_000_000L

    private fun tx(
        merchant: String,
        amount: String,
        daysAgo: Long,
        direction: TransactionDirection = TransactionDirection.DEBIT,
        currencyCode: String = "AED",
        excludeFromStats: Boolean = false,
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = currencyCode,
        direction = direction,
        merchant = merchant,
        source = TransactionSource.SMS,
        timestampMillis = baseTime - daysAgo * dayMillis,
        excludeFromStats = excludeFromStats,
    )

    @Test
    fun `detects a monthly subscription with charges 30 days apart`() {
        val transactions = listOf(
            tx("Netflix", "39.99", daysAgo = 90),
            tx("Netflix", "39.99", daysAgo = 60),
            tx("Netflix", "39.99", daysAgo = 30),
            tx("Netflix", "39.99", daysAgo = 0),
        )
        val result = SubscriptionRadarEngine.detect(transactions)

        assertEquals(1, result.size)
        val subscription = result.single()
        assertEquals("Netflix", subscription.merchant)
        assertEquals(SubscriptionCadence.MONTHLY, subscription.cadence)
        assertEquals(4, subscription.occurrenceCount)
        assertEquals(0, subscription.averageAmount.compareTo(BigDecimal("39.99")))
        assertEquals(baseTime + 30 * dayMillis, subscription.nextDueDateMillis)
    }

    @Test
    fun `detects a weekly subscription with charges 7 days apart`() {
        val transactions = listOf(
            tx("Gym Class", "20.00", daysAgo = 21),
            tx("Gym Class", "20.00", daysAgo = 14),
            tx("Gym Class", "20.00", daysAgo = 7),
            tx("Gym Class", "20.00", daysAgo = 0),
        )
        val result = SubscriptionRadarEngine.detect(transactions)

        assertEquals(1, result.size)
        assertEquals(SubscriptionCadence.WEEKLY, result.single().cadence)
        assertEquals(baseTime + 7 * dayMillis, result.single().nextDueDateMillis)
    }

    @Test
    fun `amount variance beyond 5 percent is not detected as a subscription`() {
        val transactions = listOf(
            tx("Random Store", "40.00", daysAgo = 60),
            tx("Random Store", "20.00", daysAgo = 30),
            tx("Random Store", "60.00", daysAgo = 0),
        )
        assertTrue(SubscriptionRadarEngine.detect(transactions).isEmpty())
    }

    @Test
    fun `amount variance within 5 percent is still detected`() {
        val transactions = listOf(
            tx("Spotify", "19.99", daysAgo = 60),
            tx("Spotify", "20.50", daysAgo = 30),
            tx("Spotify", "19.80", daysAgo = 0),
        )
        val result = SubscriptionRadarEngine.detect(transactions)
        assertEquals(1, result.size)
        assertEquals(SubscriptionCadence.MONTHLY, result.single().cadence)
    }

    @Test
    fun `fewer than 3 occurrences is not enough to detect a pattern`() {
        val transactions = listOf(
            tx("Netflix", "39.99", daysAgo = 30),
            tx("Netflix", "39.99", daysAgo = 0),
        )
        assertTrue(SubscriptionRadarEngine.detect(transactions).isEmpty())
    }

    @Test
    fun `irregular gaps are not classified into a cadence`() {
        val transactions = listOf(
            tx("One-off Shop", "50.00", daysAgo = 55),
            tx("One-off Shop", "50.00", daysAgo = 40),
            tx("One-off Shop", "50.00", daysAgo = 5),
        )
        assertTrue(SubscriptionRadarEngine.detect(transactions).isEmpty())
    }

    @Test
    fun `credit transactions are ignored`() {
        val transactions = listOf(
            tx("Refund Co", "39.99", daysAgo = 60, direction = TransactionDirection.CREDIT),
            tx("Refund Co", "39.99", daysAgo = 30, direction = TransactionDirection.CREDIT),
            tx("Refund Co", "39.99", daysAgo = 0, direction = TransactionDirection.CREDIT),
        )
        assertTrue(SubscriptionRadarEngine.detect(transactions).isEmpty())
    }

    @Test
    fun `transactions excluded from stats are ignored`() {
        val transactions = listOf(
            tx("Netflix", "39.99", daysAgo = 60, excludeFromStats = true),
            tx("Netflix", "39.99", daysAgo = 30, excludeFromStats = true),
            tx("Netflix", "39.99", daysAgo = 0, excludeFromStats = true),
        )
        assertTrue(SubscriptionRadarEngine.detect(transactions).isEmpty())
    }

    @Test
    fun `multiple merchants each produce a subscription sorted by next due date`() {
        val transactions = listOf(
            tx("Netflix", "39.99", daysAgo = 60),
            tx("Netflix", "39.99", daysAgo = 30),
            tx("Netflix", "39.99", daysAgo = 0),
            tx("Gym Class", "20.00", daysAgo = 14),
            tx("Gym Class", "20.00", daysAgo = 7),
            tx("Gym Class", "20.00", daysAgo = 1),
        )
        val result = SubscriptionRadarEngine.detect(transactions)
        assertEquals(2, result.size)
        // Gym's weekly renewal (1 day ago + 7) lands sooner than Netflix's monthly one.
        assertEquals("Gym Class", result.first().merchant)
        assertEquals("Netflix", result.last().merchant)
    }

    @Test
    fun `blank merchant is never grouped into a subscription`() {
        val transactions = listOf(
            tx("", "10.00", daysAgo = 60),
            tx("", "10.00", daysAgo = 30),
            tx("", "10.00", daysAgo = 0),
        )
        assertTrue(SubscriptionRadarEngine.detect(transactions).isEmpty())
    }

    // -- Price changes ---------------------------------------------------------
    // A flat variance check drops these entirely: the subscription vanishes from
    // the radar at exactly the moment its price moved.

    @Test
    fun `a price rise is still detected, and reports the old price`() {
        val transactions = listOf(
            tx("Netflix", "39.99", daysAgo = 90),
            tx("Netflix", "39.99", daysAgo = 60),
            tx("Netflix", "39.99", daysAgo = 30),
            tx("Netflix", "56.00", daysAgo = 0),
        )
        val result = SubscriptionRadarEngine.detect(transactions)

        assertEquals(1, result.size)
        val subscription = result.single()
        assertEquals(0, subscription.averageAmount.compareTo(BigDecimal("56.00")))
        assertEquals(0, subscription.previousAmount!!.compareTo(BigDecimal("39.99")))
        assertEquals(4, subscription.occurrenceCount)
    }

    @Test
    fun `a price drop is reported the same way`() {
        val transactions = listOf(
            tx("Spotify", "25.00", daysAgo = 90),
            tx("Spotify", "25.00", daysAgo = 60),
            tx("Spotify", "15.00", daysAgo = 30),
            tx("Spotify", "15.00", daysAgo = 0),
        )
        val subscription = SubscriptionRadarEngine.detect(transactions).single()

        assertEquals(0, subscription.averageAmount.compareTo(BigDecimal("15.00")))
        assertEquals(0, subscription.previousAmount!!.compareTo(BigDecimal("25.00")))
    }

    @Test
    fun `cadence and next due date survive a price change`() {
        val transactions = listOf(
            tx("Gym Class", "20.00", daysAgo = 21),
            tx("Gym Class", "20.00", daysAgo = 14),
            tx("Gym Class", "20.00", daysAgo = 7),
            tx("Gym Class", "30.00", daysAgo = 0),
        )
        val subscription = SubscriptionRadarEngine.detect(transactions).single()

        assertEquals(SubscriptionCadence.WEEKLY, subscription.cadence)
        assertEquals(baseTime + 7 * dayMillis, subscription.nextDueDateMillis)
    }

    @Test
    fun `a steady price reports no previous amount`() {
        val transactions = listOf(
            tx("Netflix", "39.99", daysAgo = 60),
            tx("Netflix", "39.99", daysAgo = 30),
            tx("Netflix", "39.99", daysAgo = 0),
        )
        assertNull(SubscriptionRadarEngine.detect(transactions).single().previousAmount)
    }

    @Test
    fun `a price change on an irregular schedule is still rejected`() {
        val transactions = listOf(
            tx("One-off Shop", "39.99", daysAgo = 55),
            tx("One-off Shop", "39.99", daysAgo = 40),
            tx("One-off Shop", "56.00", daysAgo = 5),
        )
        assertTrue(SubscriptionRadarEngine.detect(transactions).isEmpty())
    }

    @Test
    fun `three different amounts in a row is erratic spending, not a price rise`() {
        val transactions = listOf(
            tx("Random Store", "10.00", daysAgo = 60),
            tx("Random Store", "20.00", daysAgo = 30),
            tx("Random Store", "30.00", daysAgo = 0),
        )
        assertTrue(SubscriptionRadarEngine.detect(transactions).isEmpty())
    }

    @Test
    fun `a rise after only one charge at the old price still counts`() {
        val transactions = listOf(
            tx("Netflix", "39.99", daysAgo = 60),
            tx("Netflix", "56.00", daysAgo = 30),
            tx("Netflix", "56.00", daysAgo = 0),
        )
        val subscription = SubscriptionRadarEngine.detect(transactions).single()

        assertEquals(0, subscription.averageAmount.compareTo(BigDecimal("56.00")))
        assertEquals(0, subscription.previousAmount!!.compareTo(BigDecimal("39.99")))
    }
}
