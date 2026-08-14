package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.BillRecurrence
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillAdvancerTest {

    private val dayMillis = TimeUnit.DAYS.toMillis(1)
    private val dueMillis = 1_700_000_000_000L

    private fun bill(recurrence: BillRecurrence?) = Bill(
        id = 1,
        name = "Internet",
        amount = BigDecimal("299"),
        currencyCode = "AED",
        recurrence = recurrence,
        nextDueDateMillis = dueMillis,
        createdAt = dueMillis - 60 * dayMillis,
    )

    @Test
    fun `a monthly bill advances by exactly one cadence from its current due date`() {
        val advanced = BillAdvancer.advance(bill(BillRecurrence.MONTHLY))
        assertEquals(dueMillis + 30 * dayMillis, advanced!!.nextDueDateMillis)
    }

    @Test
    fun `a weekly bill advances by seven days`() {
        val advanced = BillAdvancer.advance(bill(BillRecurrence.WEEKLY))
        assertEquals(dueMillis + 7 * dayMillis, advanced!!.nextDueDateMillis)
    }

    @Test
    fun `a yearly bill advances by 365 days`() {
        val advanced = BillAdvancer.advance(bill(BillRecurrence.YEARLY))
        assertEquals(dueMillis + 365 * dayMillis, advanced!!.nextDueDateMillis)
    }

    @Test
    fun `a one-off bill has nothing to advance to`() {
        assertNull(BillAdvancer.advance(bill(recurrence = null)))
    }
}
