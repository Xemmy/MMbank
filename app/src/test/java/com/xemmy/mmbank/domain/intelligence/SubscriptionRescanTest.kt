package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.SubscriptionLink
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRescanTest {
    private val utc = ZoneOffset.UTC

    private fun millis(date: LocalDate) = date.atTime(12, 0).atZone(utc).toInstant().toEpochMilli()

    private fun tx(id: Long, merchant: String, date: LocalDate, amount: String = "50.00") = Transaction(
        id = id, amount = BigDecimal(amount), currencyCode = "AED", direction = TransactionDirection.DEBIT,
        merchant = merchant, source = TransactionSource.SMS, timestampMillis = millis(date),
    )

    private fun row(
        id: Long,
        merchant: String,
        amount: String = "50.00",
        manual: Boolean = true,
        dismissed: Boolean = false,
        lastSeen: Long = 0,
        nextDue: Long = millis(LocalDate.of(2026, 12, 1)),
    ) = Subscription(
        id = id, merchant = merchant, averageAmount = BigDecimal(amount), currencyCode = "AED",
        cadence = SubscriptionCadence.MONTHLY, nextDueDateMillis = nextDue, lastSeenMillis = lastSeen,
        occurrenceCount = 0, dismissed = dismissed, isManual = manual,
        merchantKey = SubscriptionRescan.keyOf(merchant),
    )

    private val now = millis(LocalDate.of(2026, 4, 15))

    private fun plan(stored: List<Subscription>, links: List<SubscriptionLink>, ledger: List<Transaction>) =
        SubscriptionRescan.plan(stored, links, ledger, now, utc)

    @Test fun `identical twins at one merchant each keep their own charges and both survive`() {
        val gym1 = row(1, "Gym")
        val gym2 = row(2, "Gym")
        val months = listOf(1, 2, 3)
        val first = months.map { tx(it.toLong(), "Gym", LocalDate.of(2026, it, 1)) }
        val second = months.map { tx(10L + it, "Gym", LocalDate.of(2026, it, 2)) }
        val links = first.map { SubscriptionLink(it.id, 1) } + second.map { SubscriptionLink(it.id, 2) }

        val result = plan(listOf(gym1, gym2), links, first + second)

        assertTrue("neither twin is deleted", result.deleteIds.isEmpty())
        assertEquals("nothing new is detected from charges both rows already own", 0, result.created)
        val byId = result.upserts.associateBy { it.id }
        assertEquals(3, byId.getValue(1).occurrenceCount)
        assertEquals(3, byId.getValue(2).occurrenceCount)
        assertEquals(millis(LocalDate.of(2026, 3, 1)), byId.getValue(1).lastSeenMillis)
        assertEquals(millis(LocalDate.of(2026, 3, 2)), byId.getValue(2).lastSeenMillis)
        assertEquals(LocalDate.of(2026, 4, 1).atTime(12, 0).atZone(utc).toInstant().toEpochMilli(), byId.getValue(1).nextDueDateMillis)
    }

    @Test fun `a new charge is not guessed onto either of two identical twins`() {
        val links = listOf(SubscriptionLink(1, 1), SubscriptionLink(2, 2))
        val ledger = listOf(
            tx(1, "Gym", LocalDate.of(2026, 1, 1)),
            tx(2, "Gym", LocalDate.of(2026, 1, 2)),
            tx(3, "Gym", LocalDate.of(2026, 2, 1)),
        )
        val result = plan(listOf(row(1, "Gym"), row(2, "Gym")), links, ledger)
        assertTrue(result.newLinks.isEmpty())
    }

    @Test fun `a newer charge joins the only user-owned row it fits`() {
        val ledger = listOf(
            tx(1, "Gym", LocalDate.of(2026, 1, 1)),
            tx(2, "Gym", LocalDate.of(2026, 2, 1), amount = "51.00"),
        )
        val result = plan(listOf(row(1, "Gym")), listOf(SubscriptionLink(1, 1)), ledger)
        assertEquals(listOf(SubscriptionLink(2, 1)), result.newLinks)
        assertEquals(2, result.upserts.single { it.id == 1L }.occurrenceCount)
    }

    @Test fun `a charge older than the row's latest is not re-attached after an unlink`() {
        val ledger = listOf(
            tx(1, "Gym", LocalDate.of(2026, 1, 1)),
            tx(2, "Gym", LocalDate.of(2026, 3, 1)),
        )
        // Charge 1 was unlinked by hand; the row still owns the newer charge 2.
        val result = plan(listOf(row(1, "Gym")), listOf(SubscriptionLink(2, 1)), ledger)
        assertTrue(result.newLinks.isEmpty())
    }

    @Test fun `older unlinked charges of a tracked row do not spawn a duplicate detected row`() {
        val ledger = listOf(
            tx(1, "Netflix", LocalDate.of(2026, 1, 1), amount = "39.99"),
            tx(2, "Netflix", LocalDate.of(2026, 2, 1), amount = "39.99"),
            tx(3, "Netflix", LocalDate.of(2026, 3, 1), amount = "39.99"),
            tx(4, "Netflix", LocalDate.of(2026, 4, 1), amount = "39.99"),
        )
        val tracked = row(1, "Netflix", amount = "39.99")
        val result = plan(listOf(tracked), listOf(SubscriptionLink(4, 1)), ledger)
        assertEquals(0, result.created)
        assertTrue(result.upserts.none { it.id == 0L })
    }

    @Test fun `a stale detected row is removed but a dismissed one is kept`() {
        val stale = row(1, "Old Gym", manual = false)
        val dismissed = row(2, "Old Spotify", manual = false, dismissed = true)
        val result = plan(listOf(stale, dismissed), emptyList(), emptyList())
        assertEquals(listOf(1L), result.deleteIds)
        assertEquals(1, result.removed)
    }

    @Test fun `a hand-added row is never removed even with no charges`() {
        val result = plan(listOf(row(1, "Gym")), emptyList(), emptyList())
        assertTrue(result.deleteIds.isEmpty())
    }

    @Test fun `a re-detected row keeps its id and dismissed flag when the bank changes the spelling`() {
        val stored = row(5, "Netflix", amount = "39.99", manual = false, dismissed = true)
        val ledger = listOf(
            tx(1, "NETFLIX", LocalDate.of(2026, 2, 1), amount = "39.99"),
            tx(2, "NETFLIX", LocalDate.of(2026, 3, 1), amount = "39.99"),
            tx(3, "NETFLIX", LocalDate.of(2026, 4, 1), amount = "39.99"),
        )
        val result = plan(listOf(stored), emptyList(), ledger)
        val kept = result.upserts.single()
        assertEquals(5L, kept.id)
        assertTrue(kept.dismissed)
        assertEquals("NETFLIX", kept.merchant)
        assertEquals(0, result.created)
        assertTrue(result.deleteIds.isEmpty())
    }

    @Test fun `a brand new series is created with a brand key`() {
        val ledger = listOf(
            tx(1, "Spotify", LocalDate.of(2026, 2, 1), amount = "19.99"),
            tx(2, "Spotify", LocalDate.of(2026, 3, 1), amount = "19.99"),
            tx(3, "Spotify", LocalDate.of(2026, 4, 1), amount = "19.99"),
        )
        val result = plan(emptyList(), emptyList(), ledger)
        assertEquals(1, result.created)
        val made = result.upserts.single()
        assertEquals(0L, made.id)
        assertEquals(SubscriptionRescan.keyOf("Spotify"), made.merchantKey)
        assertFalse(made.isManual)
    }

    @Test fun `linking a charge to a detected row makes it user-owned so it survives`() {
        val detected = row(1, "Apple", manual = false)
        val result = plan(listOf(detected), listOf(SubscriptionLink(1, 1)), listOf(tx(1, "Apple", LocalDate.of(2026, 3, 1))))
        assertTrue(result.deleteIds.isEmpty())
        assertTrue(result.upserts.single { it.id == 1L }.isManual)
    }

    @Test fun `a linked charge counts only for its own row, whatever merchant text it has`() {
        val result = plan(
            listOf(row(1, "Apple")),
            listOf(SubscriptionLink(1, 1)),
            listOf(tx(1, "APPLE.COM/BILL", LocalDate.of(2026, 3, 5))),
        )
        val updated = result.upserts.single { it.id == 1L }
        assertEquals(1, updated.occurrenceCount)
        assertEquals(millis(LocalDate.of(2026, 3, 5)), updated.lastSeenMillis)
    }

    @Test fun `a link to a row that no longer exists is ignored`() {
        val result = plan(listOf(row(1, "Gym")), listOf(SubscriptionLink(9, 999)), listOf(tx(9, "Gym", LocalDate.of(2026, 3, 1))))
        assertTrue(result.deleteIds.isEmpty())
    }

    @Test fun `a typed date with no charges rolls forward once it has passed`() {
        val past = row(1, "Gym", nextDue = millis(LocalDate.of(2026, 1, 10)))
        val result = plan(listOf(past), emptyList(), emptyList())
        assertEquals(millis(LocalDate.of(2026, 5, 10)), result.upserts.single().nextDueDateMillis)
    }

    @Test fun `a typed future date is left alone`() {
        val future = row(1, "Gym", nextDue = millis(LocalDate.of(2026, 6, 1)))
        val result = plan(listOf(future), emptyList(), emptyList())
        assertTrue("an unchanged row is not rewritten", result.upserts.isEmpty())
        assertEquals(0, result.updated)
    }

    @Test fun `rollForward steps by the cadence`() {
        val weekly = SubscriptionRescan.rollForward(millis(LocalDate.of(2026, 4, 1)), SubscriptionCadence.WEEKLY, now, utc)
        assertEquals(millis(LocalDate.of(2026, 4, 15)), weekly)
        assertEquals(0L, SubscriptionRescan.rollForward(0L, SubscriptionCadence.MONTHLY, now, utc))
    }
}
