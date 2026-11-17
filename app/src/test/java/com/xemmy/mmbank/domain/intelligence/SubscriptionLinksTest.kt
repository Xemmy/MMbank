package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.SubscriptionLink
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionLinksTest {
    private val utc = ZoneOffset.UTC

    private fun tx(
        id: Long,
        merchant: String?,
        date: LocalDate,
        amount: String = "10.00",
        currency: String = "AED",
        direction: TransactionDirection = TransactionDirection.DEBIT,
        kind: TransactionKind = TransactionKind.SPEND,
        excluded: Boolean = false,
    ) = Transaction(
        id = id, amount = BigDecimal(amount), currencyCode = currency, direction = direction, merchant = merchant,
        source = TransactionSource.SMS, timestampMillis = date.atTime(12, 0).atZone(utc).toInstant().toEpochMilli(),
        kind = kind, excludeFromStats = excluded,
    )

    private fun sub(merchant: String = "Apple", currency: String = "AED") = Subscription(
        id = 1, merchant = merchant, averageAmount = BigDecimal("10.00"), currencyCode = currency,
        cadence = SubscriptionCadence.MONTHLY, nextDueDateMillis = 0, lastSeenMillis = 0, occurrenceCount = 2,
    )

    @Test fun `apply renames only the linked transactions`() {
        val a = tx(1, "APPLE.COM/BILL", LocalDate.of(2026, 1, 5))
        val b = tx(2, "Carrefour", LocalDate.of(2026, 1, 6))
        val out = SubscriptionLinks.apply(listOf(a, b), listOf(SubscriptionLink(1, "Apple")))
        assertEquals("Apple", out[0].merchant)
        assertEquals("Carrefour", out[1].merchant)
        assertEquals(a.amount, out[0].amount)
    }

    @Test fun `apply without links returns the same list`() {
        val list = listOf(tx(1, "X", LocalDate.of(2026, 1, 5)))
        assertTrue(SubscriptionLinks.apply(list, emptyList()) === list)
    }

    @Test fun `a differently named charge joins the series once linked`() {
        val charges = listOf(
            tx(1, "Apple", LocalDate.of(2026, 1, 5)),
            tx(2, "Apple", LocalDate.of(2026, 2, 5)),
            tx(3, "APPLE.COM/BILL", LocalDate.of(2026, 3, 5)),
        )
        assertTrue("two Apple charges alone are not a pattern", SubscriptionRadarEngine.detect(charges, utc).isEmpty())

        val linked = SubscriptionLinks.apply(charges, listOf(SubscriptionLink(3, "Apple")))
        val detected = SubscriptionRadarEngine.detect(linked, utc).single()
        assertEquals("Apple", detected.merchant)
        assertEquals(3, detected.occurrenceCount)
        assertEquals(charges[2].timestampMillis, detected.lastSeenMillis)
    }

    @Test fun `candidates are unlinked same-currency spending newest first`() {
        val ok1 = tx(1, "APPLE.COM/BILL", LocalDate.of(2026, 3, 5))
        val ok2 = tx(2, null, LocalDate.of(2026, 4, 5))
        val own = tx(3, "apple", LocalDate.of(2026, 4, 6))
        val linked = tx(4, "Other", LocalDate.of(2026, 4, 7))
        val usd = tx(5, "Other", LocalDate.of(2026, 4, 8), currency = "USD")
        val income = tx(6, "Other", LocalDate.of(2026, 4, 9), direction = TransactionDirection.CREDIT, kind = TransactionKind.INCOME)
        val excluded = tx(7, "Other", LocalDate.of(2026, 4, 10), excluded = true)
        val unsaved = tx(0, "Other", LocalDate.of(2026, 4, 11))

        val out = SubscriptionLinks.candidates(
            listOf(ok1, ok2, own, linked, usd, income, excluded, unsaved),
            listOf(SubscriptionLink(4, "Spotify")),
            sub(),
        )
        assertEquals(listOf(2L, 1L), out.map { it.id })
    }

    @Test fun `candidates are capped`() {
        val many = (1L..60L).map { tx(it, "Misc $it", LocalDate.of(2026, 1, 1).plusDays(it)) }
        assertEquals(40, SubscriptionLinks.candidates(many, emptyList(), sub()).size)
        assertEquals(5, SubscriptionLinks.candidates(many, emptyList(), sub(), limit = 5).size)
    }

    @Test fun `linkedTo lists the ids for one merchant`() {
        val links = listOf(SubscriptionLink(1, "Apple"), SubscriptionLink(2, "Spotify"), SubscriptionLink(3, "Apple"))
        assertEquals(setOf(1L, 3L), SubscriptionLinks.linkedTo("Apple", links))
        assertTrue(SubscriptionLinks.linkedTo("Nope", links).isEmpty())
    }

    @Test fun `a subscription seeded from a charge is monthly and manual`() {
        val charge = tx(9, " Netflix ", LocalDate.of(2026, 1, 31), amount = "39.999", currency = "aed")
        val seeded = SubscriptionLinks.manualFrom(charge, utc)
        assertEquals("Netflix", seeded.merchant)
        assertEquals(BigDecimal("40.00"), seeded.averageAmount)
        assertEquals("AED", seeded.currencyCode)
        assertEquals(SubscriptionCadence.MONTHLY, seeded.cadence)
        assertTrue(seeded.isManual)
        assertEquals(1, seeded.occurrenceCount)
        assertEquals(charge.timestampMillis, seeded.lastSeenMillis)
        // 31 Jan + 1 month clamps to the end of February.
        assertEquals(LocalDate.of(2026, 2, 28).atStartOfDay(utc).toInstant().toEpochMilli(), seeded.nextDueDateMillis)
    }

    @Test fun `a charge with no merchant falls back to a generic name`() {
        assertEquals("Subscription", SubscriptionLinks.manualFrom(tx(1, null, LocalDate.of(2026, 1, 1)), utc).merchant)
        assertFalse(SubscriptionLinks.manualFrom(tx(1, "  ", LocalDate.of(2026, 1, 1)), utc).merchant.isBlank())
    }
}
