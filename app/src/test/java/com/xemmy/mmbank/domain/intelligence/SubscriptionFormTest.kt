package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.SubscriptionLink
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFormTest {

    @Test fun `a complete form is valid and normalised`() {
        val parsed = SubscriptionForm.validate("  Netflix ", "39,9", "aed")
        assertNotNull(parsed)
        assertEquals("Netflix", parsed!!.name)
        assertEquals(BigDecimal("39.90"), parsed.amount)
        assertEquals("AED", parsed.currency)
    }

    @Test fun `blank name, bad amount or bad currency are rejected`() {
        assertNull(SubscriptionForm.validate("", "10", "AED"))
        assertNull(SubscriptionForm.validate("X", "", "AED"))
        assertNull(SubscriptionForm.validate("X", "abc", "AED"))
        assertNull(SubscriptionForm.validate("X", "0", "AED"))
        assertNull(SubscriptionForm.validate("X", "-5", "AED"))
        assertNull(SubscriptionForm.validate("X", "10", "AE"))
        assertNull(SubscriptionForm.validate("X", "10", "A1D"))
    }

    private val existing = Subscription(
        id = 7, merchant = "Apple", averageAmount = BigDecimal("10.00"), currencyCode = "AED",
        cadence = SubscriptionCadence.MONTHLY, nextDueDateMillis = 1_000, lastSeenMillis = 500, occurrenceCount = 4,
        previousAmount = BigDecimal("8.00"), dismissed = true, isManual = false, confidence = 0.6,
    )

    @Test fun `editing keeps id and history, becomes user-owned and undismissed`() {
        val parsed = SubscriptionForm.validate("Apple One", "10.00", "AED")!!
        val saved = SubscriptionForm.build(existing, parsed, SubscriptionCadence.YEARLY, 2_000)
        assertEquals(7L, saved.id)
        assertEquals(4, saved.occurrenceCount)
        assertEquals(500L, saved.lastSeenMillis)
        assertEquals("Apple One", saved.merchant)
        assertEquals(SubscriptionCadence.YEARLY, saved.cadence)
        assertEquals(2_000L, saved.nextDueDateMillis)
        assertTrue(saved.isManual)
        assertFalse(saved.dismissed)
        assertEquals(1.0, saved.confidence, 0.0)
        assertEquals(SubscriptionRescan.keyOf("Apple One"), saved.merchantKey)
        assertEquals("same price keeps the old-price badge", BigDecimal("8.00"), saved.previousAmount)
    }

    @Test fun `changing the amount clears the price-change badge`() {
        val parsed = SubscriptionForm.validate("Apple", "12", "AED")!!
        assertNull(SubscriptionForm.build(existing, parsed, SubscriptionCadence.MONTHLY, 1_000).previousAmount)
    }

    @Test fun `a new row has id 0 and is user-owned`() {
        val parsed = SubscriptionForm.validate("Gym", "150", "AED")!!
        val saved = SubscriptionForm.build(null, parsed, SubscriptionCadence.MONTHLY, 5_000)
        assertEquals(0L, saved.id)
        assertEquals(0, saved.occurrenceCount)
        assertTrue(saved.isManual)
    }

    @Test fun `a snapshot keeps only the links of its own row`() {
        val links = listOf(SubscriptionLink(1, 7), SubscriptionLink(2, 8), SubscriptionLink(3, 7))
        val snap = SubscriptionSnapshot.of(existing, links)
        assertEquals(listOf(SubscriptionLink(1, 7), SubscriptionLink(3, 7)), snap.links)
        assertEquals(existing, snap.row)
    }

    @Test fun `a month-end due date is anchored, an ordinary day is not`() {
        val utc = java.time.ZoneOffset.UTC
        fun ms(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).atStartOfDay(utc).toInstant().toEpochMilli()
        assertEquals(31, SubscriptionForm.anchorDayOf(ms(2026, 1, 31), SubscriptionCadence.MONTHLY, utc))
        assertEquals(29, SubscriptionForm.anchorDayOf(ms(2026, 3, 29), SubscriptionCadence.MONTHLY, utc))
        assertEquals(0, SubscriptionForm.anchorDayOf(ms(2026, 1, 15), SubscriptionCadence.MONTHLY, utc))
        assertEquals(0, SubscriptionForm.anchorDayOf(ms(2026, 1, 31), SubscriptionCadence.WEEKLY, utc))
        val parsed = SubscriptionForm.validate("Rent", "10", "AED")!!
        assertEquals(31, SubscriptionForm.build(null, parsed, SubscriptionCadence.MONTHLY, ms(2026, 1, 31), utc).anchorDay)
    }
}
