package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeverSubscriptionTest {
    private val utc = ZoneOffset.UTC
    private fun millis(d: LocalDate) = d.atTime(12, 0).atZone(utc).toInstant().toEpochMilli()

    private fun tx(id: Long, merchant: String, date: LocalDate, amount: String = "50.00") = Transaction(
        id = id, amount = BigDecimal(amount), currencyCode = "AED", direction = TransactionDirection.DEBIT,
        merchant = merchant, source = TransactionSource.SMS, timestampMillis = millis(date),
    )

    private fun monthly(merchant: String, amount: String = "50.00") =
        (1..4).map { tx(it.toLong(), merchant, LocalDate.of(2026, it, 5), amount) }

    @Test fun `rides, delivery, groceries and fuel are never subscriptions`() {
        assertEquals("Ride-hailing, not a subscription", NeverSubscription.reasonFor("CAREEM RIDE"))
        assertEquals("Ride-hailing, not a subscription", NeverSubscription.reasonFor("Careem ABUDHABI Taxi"))
        assertEquals("Ride-hailing, not a subscription", NeverSubscription.reasonFor("UBER *TRIP"))
        assertEquals("Food delivery, not a subscription", NeverSubscription.reasonFor("DELIVERY HERO TALABAT"))
        assertEquals("Food delivery, not a subscription", NeverSubscription.reasonFor("Careem Food"))
        assertEquals("Groceries, not a subscription", NeverSubscription.reasonFor("CARREFOUR MAF"))
        assertEquals("Fuel, not a subscription", NeverSubscription.reasonFor("ADNOC DISTRIBUTION"))
    }

    @Test fun `real subscriptions of the same brands and ordinary names pass`() {
        assertNull(NeverSubscription.reasonFor("Careem Plus"))
        assertNull(NeverSubscription.reasonFor("Uber One"))
        assertNull(NeverSubscription.reasonFor("Netflix"))
        assertNull(NeverSubscription.reasonFor("e& TELECOM CORP"))
        assertNull(NeverSubscription.reasonFor("SEPHORA EMIRATES LLC"))
        assertNull(NeverSubscription.reasonFor(null))
        assertNull(NeverSubscription.reasonFor("  "))
    }

    @Test fun `a built-in merchant is not detected even with a perfect rhythm`() {
        assertTrue(SubscriptionRadarEngine.detect(monthly("CAREEM RIDE"), utc).isEmpty())
        assertEquals(1, SubscriptionRadarEngine.detect(monthly("Netflix"), utc).size)
    }

    @Test fun `a merchant the user marked never is skipped by scan`() {
        val key = SubscriptionRescan.keyOf("Netflix")
        val scan = SubscriptionRadarEngine.scan(monthly("Netflix"), utc, setOf(key))
        assertTrue(scan.active.isEmpty() && scan.possible.isEmpty())
    }

    @Test fun `explain says why a blocked merchant is not detected`() {
        val builtIn = SubscriptionRadarEngine.explain(monthly("CAREEM RIDE"), utc).single()
        val out = builtIn.outcome as SubscriptionRadarEngine.Outcome.Rejected
        assertEquals(SubscriptionRadarEngine.Reason.NOT_A_SUBSCRIPTION, out.reason)
        assertEquals("Ride-hailing, not a subscription", out.detail)

        val user = SubscriptionRadarEngine.explain(monthly("Netflix"), utc, setOf(SubscriptionRescan.keyOf("Netflix"))).single()
        assertEquals("You marked this as not a subscription", (user.outcome as SubscriptionRadarEngine.Outcome.Rejected).detail)
    }

    @Test fun `weekly needs four charges`() {
        val three = (0..2).map { tx(it + 1L, "Gym Class", LocalDate.of(2026, 3, 1).plusDays(7L * it), "20.00") }
        assertTrue(SubscriptionRadarEngine.detect(three, utc).isEmpty())
        val four = (0..3).map { tx(it + 1L, "Gym Class", LocalDate.of(2026, 3, 1).plusDays(7L * it), "20.00") }
        assertEquals(1, SubscriptionRadarEngine.detect(four, utc).size)
    }

    @Test fun `loose weekly-ish gaps like taxi rides are rejected`() {
        val offsets = listOf(0L, 5L, 12L, 19L, 24L, 32L)
        val rides = offsets.mapIndexed { i, d -> tx(i + 1L, "Local Ride Co", LocalDate.of(2026, 3, 1).plusDays(d), "30.00") }
        assertTrue(SubscriptionRadarEngine.detect(rides, utc).isEmpty())
    }

    @Test fun `rescan with a never key removes the detected row and keeps user-owned ones`() {
        val key = SubscriptionRescan.keyOf("Netflix")
        val ledger = monthly("Netflix")
        val detected = SubscriptionRescan.plan(emptyList(), emptyList(), ledger, millis(LocalDate.of(2026, 4, 15)), utc)
        assertEquals(1, detected.created)
        val stored = detected.upserts.single().copy(id = 9)
        val blocked = SubscriptionRescan.plan(listOf(stored), emptyList(), ledger, millis(LocalDate.of(2026, 4, 15)), utc, setOf(key))
        assertEquals(listOf(9L), blocked.deleteIds)
        assertEquals(0, blocked.created)

        val owned: Subscription = stored.copy(isManual = true)
        val kept = SubscriptionRescan.plan(listOf(owned), emptyList(), ledger, millis(LocalDate.of(2026, 4, 15)), utc, setOf(key))
        assertTrue(kept.deleteIds.isEmpty())
        assertNotNull(kept)
    }
}
