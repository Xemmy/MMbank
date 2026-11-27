package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Bill

/**
 * Picks which bills are worth reminding about right now, and keeps each due
 * date from being announced more than once. Structurally identical to
 * [SubscriptionReminders] -- same dedupe-by-due-date key shape, same
 * "already overdue still reminds" stance -- but for manually tracked [Bill]s
 * instead of detected [com.xemmy.mmbank.domain.model.Subscription]s.
 *
 * Pure state-in/state-out, so it's JVM-testable without WorkManager, a
 * clock, or the notification framework.
 */
object BillReminders {

    /** How far ahead a due date is worth flagging. */
    const val DEFAULT_WINDOW_DAYS = 3L

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /**
     * Identity of one due date: the bill plus its specific
     * [Bill.nextDueDateMillis]. Keyed on the due date rather than the bill
     * alone, so the reminder re-arms by itself for the next cycle once
     * [BillAdvancer] moves it forward after the bill is marked paid.
     */
    fun reminderKey(bill: Bill): String = "${bill.id}:${bill.nextDueDateMillis}"

    /**
     * Bills due within [windowDays] of [nowMillis] that haven't been
     * announced yet, soonest first. An already-overdue bill still reminds --
     * nothing here moves [Bill.nextDueDateMillis] on its own, only marking it
     * paid does, so a bill left unpaid is exactly the case worth flagging,
     * not hiding.
     */
    fun pending(
        bills: List<Bill>,
        nowMillis: Long,
        alreadyNotified: Set<String>,
        windowDays: Long = DEFAULT_WINDOW_DAYS,
    ): List<Bill> {
        val horizon = nowMillis + windowDays * DAY_MILLIS
        return bills
            .filter { it.nextDueDateMillis <= horizon }
            .filter { reminderKey(it) !in alreadyNotified }
            .sortedBy { it.nextDueDateMillis }
    }

    /**
     * Calendar days from today until the bill is due, in [zone]: 0 = today, 1 = tomorrow, negative once
     * overdue. Calendar days, not elapsed 24-hour blocks: a due date stored at midnight read
     * "due today" the day before and "overdue by 1d" on the day itself. Matches
     * [UpcomingPayments], so every screen names the same day.
     */
    fun daysUntil(bill: Bill, nowMillis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long =
        java.time.temporal.ChronoUnit.DAYS.between(
            java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate(),
            java.time.Instant.ofEpochMilli(bill.nextDueDateMillis).atZone(zone).toLocalDate(),
        )

    /**
     * Prunes announced keys down to the bills still in play, so the stored
     * set tracks the active bill count instead of growing forever.
     */
    fun prune(alreadyNotified: Set<String>, bills: List<Bill>): Set<String> {
        val live = bills.mapTo(mutableSetOf()) { reminderKey(it) }
        return alreadyNotified.intersect(live)
    }
}
