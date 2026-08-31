package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Bill
import java.time.Instant
import java.time.ZoneId

/**
 * Decides what happens to a [Bill] when the user marks it paid. There's no
 * transaction signal to trust here (unlike [SubscriptionRadarEngine], which
 * only moves a subscription's due date once a matching charge actually
 * lands) -- a manually tracked bill only advances because the user said so.
 *
 * Pure and top-level so it's JVM-testable; the caller supplies the zone, the
 * same stance as [CashFlowForecaster] and [GoalForecaster].
 */
object BillAdvancer {

    /**
     * The bill's next state after being marked paid: its due date moved
     * forward by exactly one [Bill.recurrence] cycle from the *current* due
     * date (not from today). A bill marked paid well after it was due lands
     * with its next due date still in the past -- which is correct, not a bug:
     * it's overdue again and [BillReminders] will flag it on the next check,
     * the same stance it takes on an overdue subscription.
     *
     * The step is a **calendar** [java.time.Period], not a fixed span of
     * milliseconds. A bill is due on a date the user picked, so "monthly" has
     * to mean "same day next month": adding a flat 30 days walks rent due on
     * the 1st back to the 31st, then the 2nd, drifting ~5 days a year, and a
     * flat 365 slips an annual bill a day earlier at every leap year. Working
     * in the zone's local time also keeps the due date's time-of-day stable
     * across a DST boundary, which fixed-millis arithmetic shifts by an hour.
     *
     * Month-end is preserved deliberately. `java.time` clamps Jan 31 + 1 month
     * to Feb 28, and advancing from *that* would strand a month-end bill on
     * the 28th forever; so when the current due date is its month's last day
     * and the cycle is month-based, the result snaps to the new month's last
     * day (Jan 31 -> Feb 28 -> Mar 31). A weekly cycle never snaps -- landing
     * on a month's last day is a coincidence there, not the user's intent.
     *
     * Returns null for a one-off bill ([Bill.recurrence] is null) -- the
     * caller deletes it instead of persisting a paid one-off forever.
     */
    fun advance(bill: Bill, zone: ZoneId = ZoneId.systemDefault()): Bill? {
        val recurrence = bill.recurrence ?: return null
        val current = Instant.ofEpochMilli(bill.nextDueDateMillis).atZone(zone)
        var next = current.plus(recurrence.period)

        val isMonthBased = recurrence.period.toTotalMonths() > 0
        if (isMonthBased && current.dayOfMonth == current.toLocalDate().lengthOfMonth()) {
            next = next.withDayOfMonth(next.toLocalDate().lengthOfMonth())
        }

        return bill.copy(nextDueDateMillis = next.toInstant().toEpochMilli())
    }
}
