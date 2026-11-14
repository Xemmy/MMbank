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
        rawMessage: String? = null,
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = currencyCode,
        direction = direction,
        merchant = merchant,
        source = TransactionSource.SMS,
        timestampMillis = baseTime - daysAgo * dayMillis,
        excludeFromStats = excludeFromStats,
        rawMessage = rawMessage,
    )

    private val utc = java.time.ZoneOffset.UTC

    /** A charge on an exact calendar date at noon UTC, for the renewal-date tests. */
    private fun on(merchant: String, amount: String, year: Int, month: Int, day: Int) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = "AED",
        direction = TransactionDirection.DEBIT,
        merchant = merchant,
        source = TransactionSource.SMS,
        timestampMillis = java.time.LocalDate.of(year, month, day).atTime(12, 0).atZone(utc).toInstant().toEpochMilli(),
    )

    private fun renewalDate(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(utc).toLocalDate()

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

    // -- v2: tolerance, cadences, calendar-true renewals ----------------------

    @Test
    fun `one missed month does not hide a subscription`() {
        val result = SubscriptionRadarEngine.detect(
            listOf(
                tx("Netflix", "39.99", daysAgo = 120),
                tx("Netflix", "39.99", daysAgo = 90),
                tx("Netflix", "39.99", daysAgo = 30),
                tx("Netflix", "39.99", daysAgo = 0),
            ),
        )
        assertEquals(SubscriptionCadence.MONTHLY, result.single().cadence)
        assertEquals(4, result.single().occurrenceCount)
    }

    @Test
    fun `every other month is not mistaken for monthly`() {
        val result = SubscriptionRadarEngine.detect(
            listOf(
                tx("Club", "100.00", daysAgo = 120),
                tx("Club", "100.00", daysAgo = 60),
                tx("Club", "100.00", daysAgo = 0),
            ),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an extra purchase at the same merchant does not break the chain`() {
        val result = SubscriptionRadarEngine.detect(
            listOf(
                tx("Apple", "39.99", daysAgo = 90),
                tx("Apple", "9.99", daysAgo = 75), // a one-off app purchase between renewals
                tx("Apple", "39.99", daysAgo = 60),
                tx("Apple", "39.99", daysAgo = 30),
                tx("Apple", "39.99", daysAgo = 0),
            ),
        )
        val sub = result.single()
        assertEquals(SubscriptionCadence.MONTHLY, sub.cadence)
        assertEquals(4, sub.occurrenceCount)
        assertEquals(0, sub.averageAmount.compareTo(BigDecimal("39.99")))
    }

    @Test
    fun `frequent same-price shopping is not a subscription`() {
        val coffee = (0L..14L).map { tx("Corner Cafe", "12.00", daysAgo = it) }
        assertTrue(SubscriptionRadarEngine.detect(coffee).isEmpty())
    }

    @Test
    fun `detects bi-weekly, quarterly and yearly cadences`() {
        val biweekly = SubscriptionRadarEngine.detect(
            listOf(42L, 28L, 14L, 0L).map { tx("Cleaner", "80.00", daysAgo = it) },
        ).single()
        assertEquals(SubscriptionCadence.BIWEEKLY, biweekly.cadence)

        val quarterly = SubscriptionRadarEngine.detect(
            listOf(270L, 180L, 90L, 0L).map { tx("Insurer", "300.00", daysAgo = it) },
        ).single()
        assertEquals(SubscriptionCadence.QUARTERLY, quarterly.cadence)

        val yearly = SubscriptionRadarEngine.detect(
            listOf(730L, 365L, 0L).map { tx("Domain Co", "55.00", daysAgo = it) },
        ).single()
        assertEquals(SubscriptionCadence.YEARLY, yearly.cadence)
    }

    @Test
    fun `a weekly run is weekly, not bi-weekly with missed weeks`() {
        val result = SubscriptionRadarEngine.detect(
            listOf(21L, 14L, 7L, 0L).map { tx("Gym Class", "20.00", daysAgo = it) },
        )
        assertEquals(SubscriptionCadence.WEEKLY, result.single().cadence)
    }

    @Test
    fun `the billing day of the month is kept, not drifted by 30-day steps`() {
        val result = SubscriptionRadarEngine.detect(
            listOf(on("Spotify", "21.99", 2024, 1, 15), on("Spotify", "21.99", 2024, 2, 15), on("Spotify", "21.99", 2024, 3, 15)),
            utc,
        )
        assertEquals(java.time.LocalDate.of(2024, 4, 15), renewalDate(result.single().nextDueDateMillis))
    }

    @Test
    fun `a month-end biller stays at month end after a short month`() {
        val result = SubscriptionRadarEngine.detect(
            listOf(on("Rent Co", "500.00", 2023, 1, 31), on("Rent Co", "500.00", 2023, 2, 28), on("Rent Co", "500.00", 2023, 3, 31)),
            utc,
        )
        assertEquals(java.time.LocalDate.of(2023, 4, 30), renewalDate(result.single().nextDueDateMillis))
    }

    @Test
    fun `quarterly and yearly renewals land on the same calendar day`() {
        val quarterly = SubscriptionRadarEngine.detect(
            listOf(on("Insurer", "300.00", 2023, 3, 10), on("Insurer", "300.00", 2023, 6, 10), on("Insurer", "300.00", 2023, 9, 10)),
            utc,
        ).single()
        assertEquals(SubscriptionCadence.QUARTERLY, quarterly.cadence)
        assertEquals(java.time.LocalDate.of(2023, 12, 10), renewalDate(quarterly.nextDueDateMillis))

        val yearly = SubscriptionRadarEngine.detect(
            listOf(on("Domain Co", "55.00", 2021, 5, 20), on("Domain Co", "55.00", 2022, 5, 20), on("Domain Co", "55.00", 2023, 5, 20)),
            utc,
        ).single()
        assertEquals(SubscriptionCadence.YEARLY, yearly.cadence)
        assertEquals(java.time.LocalDate.of(2024, 5, 20), renewalDate(yearly.nextDueDateMillis))
    }

    @Test
    fun `merchant names that differ only by location or case are one merchant`() {
        val result = SubscriptionRadarEngine.detect(
            listOf(
                tx("NETFLIX DUBAI", "39.99", daysAgo = 60),
                tx("Netflix", "39.99", daysAgo = 30),
                tx("NETFLIX AE", "39.99", daysAgo = 0),
            ),
        )
        assertEquals(1, result.size)
        assertEquals(3, result.single().occurrenceCount)
    }

    @Test
    fun `wording that says subscription lets two charges on a clean cadence count`() {
        val hinted = SubscriptionRadarEngine.detect(
            listOf(
                tx("Cloud Storage", "9.99", daysAgo = 30, rawMessage = "Your subscription renewal of AED 9.99 was charged"),
                tx("Cloud Storage", "9.99", daysAgo = 0, rawMessage = "Your subscription renewal of AED 9.99 was charged"),
            ),
        )
        assertEquals(SubscriptionCadence.MONTHLY, hinted.single().cadence)
        assertEquals(2, hinted.single().occurrenceCount)

        val arabic = SubscriptionRadarEngine.detect(
            listOf(
                tx("Cloud Storage", "9.99", daysAgo = 30, rawMessage = "تم تجديد الاشتراك"),
                tx("Cloud Storage", "9.99", daysAgo = 0),
            ),
        )
        assertEquals(1, arabic.size)
    }

    @Test
    fun `wording does not rescue two charges that are not on a cadence`() {
        val result = SubscriptionRadarEngine.detect(
            listOf(
                tx("Cloud Storage", "9.99", daysAgo = 20, rawMessage = "subscription renewal"),
                tx("Cloud Storage", "9.99", daysAgo = 0, rawMessage = "subscription renewal"),
            ),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `without wording two charges are still not enough`() {
        val result = SubscriptionRadarEngine.detect(
            listOf(tx("Cloud Storage", "9.99", daysAgo = 30), tx("Cloud Storage", "9.99", daysAgo = 0)),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `month-based cadences report their calendar months`() {
        assertEquals(null, SubscriptionCadence.WEEKLY.months)
        assertEquals(null, SubscriptionCadence.BIWEEKLY.months)
        assertEquals(1, SubscriptionCadence.MONTHLY.months)
        assertEquals(3, SubscriptionCadence.QUARTERLY.months)
        assertEquals(6, SubscriptionCadence.SEMI_ANNUAL.months)
        assertEquals(12, SubscriptionCadence.YEARLY.months)
    }
}
