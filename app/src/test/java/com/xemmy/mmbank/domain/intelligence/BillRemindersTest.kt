package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.BillRecurrence
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillRemindersTest {

    private val dayMillis = TimeUnit.DAYS.toMillis(1)
    private val now = 1_700_000_000_000L

    private fun bill(
        id: Long,
        dueInDays: Long,
        name: String = "Rent",
    ) = Bill(
        id = id,
        name = name,
        amount = BigDecimal("2500"),
        currencyCode = "AED",
        recurrence = BillRecurrence.MONTHLY,
        nextDueDateMillis = now + dueInDays * dayMillis,
        createdAt = now - 90 * dayMillis,
    )

    @Test
    fun `a bill due inside the window is flagged`() {
        val result = BillReminders.pending(listOf(bill(1, dueInDays = 2)), now, emptySet())
        assertEquals(1, result.size)
    }

    @Test
    fun `a bill due beyond the window is not flagged yet`() {
        val result = BillReminders.pending(listOf(bill(1, dueInDays = 10)), now, emptySet())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an overdue bill still reminds rather than being hidden`() {
        val result = BillReminders.pending(listOf(bill(1, dueInDays = -2)), now, emptySet())
        assertEquals(1, result.size)
    }

    @Test
    fun `a due date already announced is not announced again`() {
        val due = bill(1, dueInDays = 1)
        val key = BillReminders.reminderKey(due)
        val result = BillReminders.pending(listOf(due), now, setOf(key))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `the next cycle re-arms on its own, because the key carries the due date`() {
        val thisCycle = bill(1, dueInDays = 1)
        val announced = setOf(BillReminders.reminderKey(thisCycle))

        val nextCycle = BillAdvancer.advance(thisCycle)!!
        val result = BillReminders.pending(listOf(nextCycle), now + 30 * dayMillis, announced)

        assertEquals(1, result.size)
    }

    @Test
    fun `results come back soonest first`() {
        val result = BillReminders.pending(
            listOf(
                bill(1, dueInDays = 3, name = "Later"),
                bill(2, dueInDays = 0, name = "Sooner"),
            ),
            now,
            emptySet(),
        )
        assertEquals(listOf("Sooner", "Later"), result.map { it.name })
    }

    @Test
    fun `daysUntil floors, and goes negative once overdue`() {
        assertEquals(2, BillReminders.daysUntil(bill(1, dueInDays = 2), now))
        assertEquals(0, BillReminders.daysUntil(bill(1, dueInDays = 0), now))
        assertEquals(-1, BillReminders.daysUntil(bill(1, dueInDays = -1), now))
    }

    @Test
    fun `prune drops keys for bills that no longer exist`() {
        val live = bill(1, dueInDays = 1)
        val gone = bill(99, dueInDays = 1, name = "Deleted")
        val stored = setOf(BillReminders.reminderKey(live), BillReminders.reminderKey(gone))
        assertEquals(
            setOf(BillReminders.reminderKey(live)),
            BillReminders.prune(stored, listOf(live)),
        )
    }
}
