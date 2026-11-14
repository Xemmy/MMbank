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
}
