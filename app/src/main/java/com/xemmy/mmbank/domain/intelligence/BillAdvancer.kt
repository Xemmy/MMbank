package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Bill

/**
 * Decides what happens to a [Bill] when the user marks it paid. There's no
 * transaction signal to trust here (unlike [SubscriptionRadarEngine], which
 * only moves a subscription's due date once a matching charge actually
 * lands) -- a manually tracked bill only advances because the user said so.
 *
 * Pure and top-level so it's JVM-testable.
 */
object BillAdvancer {

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /**
     * The bill's next state after being marked paid: its due date moved
     * forward by exactly one [Bill.recurrence] cycle from the *current* due
     * date (not from today), mirroring how [Subscription.nextDueDateMillis]
     * projects from the last occurrence rather than from "now". A bill
     * marked paid well after it was due lands with its next due date still
     * in the past -- which is correct, not a bug: it's overdue again and
     * [BillReminders] will flag it on the next check, the same stance it
     * takes on an overdue subscription.
     *
     * Returns null for a one-off bill ([Bill.recurrence] is null) -- the
     * caller deletes it instead of persisting a paid one-off forever.
     */
    fun advance(bill: Bill): Bill? {
        val recurrence = bill.recurrence ?: return null
        return bill.copy(nextDueDateMillis = bill.nextDueDateMillis + recurrence.nominalDays * DAY_MILLIS)
    }
}
