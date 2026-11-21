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

    @Test fun `candidates are unlinked same-currency spending newest first`() {
        val ok1 = tx(1, "APPLE.COM/BILL", LocalDate.of(2026, 3, 5))
        val ok2 = tx(2, null, LocalDate.of(2026, 4, 5))
        // Same merchant as the subscription: stays a candidate, because only the user can say which of
        // several subscriptions at one merchant a charge belongs to.
        val own = tx(3, "apple", LocalDate.of(2026, 4, 6))
        val linked = tx(4, "Other", LocalDate.of(2026, 4, 7))
        val usd = tx(5, "Other", LocalDate.of(2026, 4, 8), currency = "USD")
        val income = tx(6, "Other", LocalDate.of(2026, 4, 9), direction = TransactionDirection.CREDIT, kind = TransactionKind.INCOME)
        val excluded = tx(7, "Other", LocalDate.of(2026, 4, 10), excluded = true)
        val unsaved = tx(0, "Other", LocalDate.of(2026, 4, 11))

        val out = SubscriptionLinks.candidates(
            listOf(ok1, ok2, own, linked, usd, income, excluded, unsaved),
            listOf(SubscriptionLink(4, 77)),
            sub(),
        )
        assertEquals(listOf(3L, 2L, 1L), out.map { it.id })
    }

    @Test fun `candidates are capped`() {
        val many = (1L..60L).map { tx(it, "Misc $it", LocalDate.of(2026, 1, 1).plusDays(it)) }
        assertEquals(40, SubscriptionLinks.candidates(many, emptyList(), sub()).size)
        assertEquals(5, SubscriptionLinks.candidates(many, emptyList(), sub(), limit = 5).size)
    }

    @Test fun `linkedTo lists the ids for one subscription row`() {
        val links = listOf(SubscriptionLink(1, 10), SubscriptionLink(2, 20), SubscriptionLink(3, 10))
        assertEquals(setOf(1L, 3L), SubscriptionLinks.linkedTo(10, links))
        assertTrue(SubscriptionLinks.linkedTo(99, links).isEmpty())
    }

    @Test fun `similar finds unlinked same-brand charges within five percent, oldest first`() {
        val s = sub("Apple").copy(averageAmount = BigDecimal("50.00"))
        val a = tx(1, "APPLE.COM/BILL", LocalDate.of(2026, 3, 5), amount = "50.00")
        val b = tx(2, "Apple", LocalDate.of(2026, 2, 5), amount = "51.50")
        val farAmount = tx(3, "Apple", LocalDate.of(2026, 4, 5), amount = "80.00")
        val otherBrand = tx(4, "Spotify", LocalDate.of(2026, 4, 6), amount = "50.00")
        val usd = tx(5, "Apple", LocalDate.of(2026, 4, 7), amount = "50.00", currency = "USD")
        val taken = tx(6, "Apple", LocalDate.of(2026, 4, 8), amount = "50.00")
        val out = SubscriptionLinks.similar(listOf(a, b, farAmount, otherBrand, usd, taken), listOf(SubscriptionLink(6, 2)), s)
        assertEquals(listOf(2L, 1L), out.map { it.id })
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
        assertEquals(SubscriptionRescan.keyOf("Netflix"), seeded.merchantKey)
        assertEquals(charge.timestampMillis, seeded.lastSeenMillis)
        // 31 Jan + 1 month clamps to the end of February.
        assertEquals(LocalDate.of(2026, 2, 28).atStartOfDay(utc).toInstant().toEpochMilli(), seeded.nextDueDateMillis)
    }

    @Test fun `a charge with no merchant falls back to a generic name`() {
        assertEquals("Subscription", SubscriptionLinks.manualFrom(tx(1, null, LocalDate.of(2026, 1, 1)), utc).merchant)
        assertFalse(SubscriptionLinks.manualFrom(tx(1, "  ", LocalDate.of(2026, 1, 1)), utc).merchant.isBlank())
    }

    @Test fun `pickable lists unlinked real spending newest first and filters by merchant`() {
        val a = tx(1, "Netflix", LocalDate.of(2026, 3, 1))
        val b = tx(2, "Spotify", LocalDate.of(2026, 4, 1))
        val linked = tx(3, "Apple", LocalDate.of(2026, 5, 1))
        val income = tx(4, "Salary", LocalDate.of(2026, 6, 1), direction = TransactionDirection.CREDIT, kind = TransactionKind.INCOME)
        val all = listOf(a, b, linked, income)
        val links = listOf(SubscriptionLink(3, 1))
        assertEquals(listOf(2L, 1L), SubscriptionLinks.pickable(all, links).map { it.id })
        assertEquals(listOf(1L), SubscriptionLinks.pickable(all, links, query = " net ").map { it.id })
        assertEquals(1, SubscriptionLinks.pickable(all, links, limit = 1).size)
    }
}
