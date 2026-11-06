package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription

/**
 * Picks which detected subscriptions are worth reminding about right now, and
 * keeps each renewal from being announced more than once.
 *
 * Pure state-in/state-out — the caller supplies "now" and the set of renewals
 * already announced — so the whole rule is JVM-testable without WorkManager,
 * a clock, or the notification framework, exactly like
 * [com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator].
 */
object SubscriptionReminders {

    /** How far ahead a renewal is worth flagging. */
    const val DEFAULT_WINDOW_DAYS = 3L

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /**
     * Identity of one renewal: the subscription plus the specific due date.
     *
     * Keyed on the due date rather than the subscription alone, so the reminder
     * re-arms by itself for the next cycle — when the charge lands, detection
     * moves `nextDueDateMillis` forward and the new cycle gets a fresh key with
     * no expiry job.
     */
    fun reminderKey(subscription: Subscription): String =
        "${subscription.id}:${subscription.nextDueDateMillis}"

    /**
     * Subscriptions renewing within [windowDays] of [nowMillis] that haven't
     * been announced yet, soonest first.
     *
     * Dismissed subscriptions never remind. An already-overdue renewal still
     * does: detection only moves the due date forward once the charge is
     * actually captured, so a renewal a day late is exactly the case a user
     * wants flagged, not hidden.
     */
    fun pending(
        subscriptions: List<Subscription>,
        nowMillis: Long,
        alreadyNotified: Set<String>,
        windowDays: Long = DEFAULT_WINDOW_DAYS,
    ): List<Subscription> {
        val horizon = nowMillis + windowDays * DAY_MILLIS
        return subscriptions
            .filter { !it.dismissed }
            .filter { it.nextDueDateMillis <= horizon }
            .filter { reminderKey(it) !in alreadyNotified }
            .sortedBy { it.nextDueDateMillis }
    }

    /**
     * Whole-days from [nowMillis] until the renewal; negative once overdue.
     * Rounded down, so "2.9 days away" reads as 2 rather than 3.
     */
    fun daysUntil(subscription: Subscription, nowMillis: Long): Long =
        Math.floorDiv(subscription.nextDueDateMillis - nowMillis, DAY_MILLIS)

    /**
     * Prunes announced keys down to the renewals still in play, so the stored
     * set tracks the active subscription count instead of growing forever.
     */
    fun prune(alreadyNotified: Set<String>, subscriptions: List<Subscription>): Set<String> {
        val live = subscriptions.mapTo(mutableSetOf()) { reminderKey(it) }
        return alreadyNotified.intersect(live)
    }
}
