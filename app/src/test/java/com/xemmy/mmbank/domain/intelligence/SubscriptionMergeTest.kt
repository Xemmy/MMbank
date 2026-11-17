package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionMergeTest {
    private fun sub(id: Long, merchant: String, dismissed: Boolean = false, due: Long = 100) = Subscription(
        id = id, merchant = merchant, averageAmount = BigDecimal("10"), currencyCode = "AED",
        cadence = SubscriptionCadence.MONTHLY, nextDueDateMillis = due, lastSeenMillis = 0,
        occurrenceCount = 3, dismissed = dismissed,
    )

    @Test fun `a rescan keeps the stored id so the reminder key is stable`() {
        val merged = SubscriptionMerge.merge(listOf(sub(7, "Netflix")), listOf(sub(0, "Netflix")))
        assertEquals(7L, merged.single().id)
        assertEquals(
            SubscriptionReminders.reminderKey(sub(7, "Netflix")),
            SubscriptionReminders.reminderKey(merged.single()),
        )
    }

    @Test fun `dismissed flag carries over and new merchants stay new`() {
        val merged = SubscriptionMerge.merge(
            listOf(sub(7, "Netflix", dismissed = true)),
            listOf(sub(0, "Netflix"), sub(0, "Spotify")),
        )
        assertTrue(merged[0].dismissed)
        assertEquals(0L, merged[1].id)
    }

    @Test fun `a manual row keeps its typed amount and cadence and takes only what the scan saw`() {
        val stored = sub(7, "Gym", due = 500).copy(
            averageAmount = BigDecimal("150"), cadence = SubscriptionCadence.YEARLY,
            lastSeenMillis = 10, occurrenceCount = 0, isManual = true, dismissed = false,
        )
        val scanned = sub(0, "Gym", due = 900).copy(
            averageAmount = BigDecimal("12"), cadence = SubscriptionCadence.MONTHLY, lastSeenMillis = 800, occurrenceCount = 3,
        )
        val merged = SubscriptionMerge.merge(listOf(stored), listOf(scanned)).single()
        assertEquals(7L, merged.id)
        assertTrue(merged.isManual)
        assertEquals(BigDecimal("150"), merged.averageAmount)
        assertEquals(SubscriptionCadence.YEARLY, merged.cadence)
        assertEquals(800L, merged.lastSeenMillis)
        assertEquals(3, merged.occurrenceCount)
        assertEquals("a different cadence must not move the typed schedule", 500L, merged.nextDueDateMillis)
    }

    @Test fun `a manual row follows the scan's next date when the cadence agrees`() {
        val stored = sub(7, "Gym", due = 500).copy(isManual = true)
        val merged = SubscriptionMerge.merge(listOf(stored), listOf(sub(0, "Gym", due = 900))).single()
        assertEquals(900L, merged.nextDueDateMillis)
    }

    @Test fun `a detected row is still replaced by the scan`() {
        val stored = sub(7, "Netflix").copy(averageAmount = BigDecimal("1"))
        val merged = SubscriptionMerge.merge(listOf(stored), listOf(sub(0, "Netflix"))).single()
        assertEquals(BigDecimal("10"), merged.averageAmount)
        assertEquals(false, merged.isManual)
    }
}
