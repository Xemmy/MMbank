package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRemindersTest {

    private val dayMillis = TimeUnit.DAYS.toMillis(1)
    private val now = 1_700_000_000_000L

    private fun subscription(
        id: Long,
        dueInDays: Long,
        dismissed: Boolean = false,
        merchant: String = "Netflix",
    ) = Subscription(
        id = id,
        merchant = merchant,
        averageAmount = BigDecimal("39.99"),
        currencyCode = "AED",
        cadence = SubscriptionCadence.MONTHLY,
        nextDueDateMillis = now + dueInDays * dayMillis,
        lastSeenMillis = now - 30 * dayMillis,
        occurrenceCount = 4,
        dismissed = dismissed,
    )

    @Test
    fun `a renewal inside the window is flagged`() {
        val result = SubscriptionReminders.pending(listOf(subscription(1, dueInDays = 2)), now, emptySet())
        assertEquals(1, result.size)
    }

    @Test
    fun `a renewal beyond the window is not flagged yet`() {
        val result = SubscriptionReminders.pending(listOf(subscription(1, dueInDays = 10)), now, emptySet())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an overdue renewal still reminds rather than being hidden`() {
        val result = SubscriptionReminders.pending(listOf(subscription(1, dueInDays = -2)), now, emptySet())
        assertEquals(1, result.size)
    }

    @Test
    fun `a dismissed subscription never reminds`() {
        val result = SubscriptionReminders.pending(
            listOf(subscription(1, dueInDays = 1, dismissed = true)), now, emptySet(),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a renewal already announced is not announced again`() {
        val subscription = subscription(1, dueInDays = 1)
        val key = SubscriptionReminders.reminderKey(subscription)
        val result = SubscriptionReminders.pending(listOf(subscription), now, setOf(key))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `the next cycle re-arms on its own, because the key carries the due date`() {
        val thisCycle = subscription(1, dueInDays = 1)
        val announced = setOf(SubscriptionReminders.reminderKey(thisCycle))

        // Detection moves the due date forward once the charge lands.
        val nextCycle = thisCycle.copy(nextDueDateMillis = thisCycle.nextDueDateMillis + 30 * dayMillis)
        val result = SubscriptionReminders.pending(listOf(nextCycle), now + 30 * dayMillis, announced)

        assertEquals(1, result.size)
    }

    @Test
    fun `results come back soonest first`() {
        val result = SubscriptionReminders.pending(
            listOf(
                subscription(1, dueInDays = 3, merchant = "Later"),
                subscription(2, dueInDays = 0, merchant = "Sooner"),
            ),
            now,
            emptySet(),
        )
        assertEquals(listOf("Sooner", "Later"), result.map { it.merchant })
    }

    @Test
    fun `daysUntil floors, and goes negative once overdue`() {
        assertEquals(2, SubscriptionReminders.daysUntil(subscription(1, dueInDays = 2), now))
        assertEquals(0, SubscriptionReminders.daysUntil(subscription(1, dueInDays = 0), now))
        assertEquals(-1, SubscriptionReminders.daysUntil(subscription(1, dueInDays = -1), now))
    }

    @Test
    fun `daysUntil rounds a partial day down rather than up`() {
        val almostThree = subscription(1, dueInDays = 2).let {
            it.copy(nextDueDateMillis = it.nextDueDateMillis + dayMillis * 9 / 10)
        }
        assertEquals(2, SubscriptionReminders.daysUntil(almostThree, now))
    }

    @Test
    fun `prune drops keys for renewals that no longer exist`() {
        val live = subscription(1, dueInDays = 1)
        val stale = subscription(99, dueInDays = 1, merchant = "Gone")
        val stored = setOf(
            SubscriptionReminders.reminderKey(live),
            SubscriptionReminders.reminderKey(stale),
        )
        assertEquals(
            setOf(SubscriptionReminders.reminderKey(live)),
            SubscriptionReminders.prune(stored, listOf(live)),
        )
    }
}
