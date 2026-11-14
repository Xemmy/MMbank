package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.account.CardPaymentReminders
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.Subscription
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * One list of money about to leave, from the three places it is tracked today
 * -- manually entered bills, detected subscriptions, and credit-card dues
 * (Phase 3 / B.4 "Recurring"). A read-only view over the existing models: no
 * data moves, so nothing can be lost; it simply answers "what's coming up?"
 * without opening three screens.
 *
 * Pure and JVM-testable.
 */
object UpcomingPayments {

    enum class Kind(val label: String) { BILL("Bill"), SUBSCRIPTION("Subscription"), CARD("Card payment") }

    data class Item(
        val kind: Kind,
        val name: String,
        /** What is expected to be paid; null when unknown. */
        val amount: BigDecimal?,
        val currencyCode: String,
        val dueDate: LocalDate,
        /** Whole days from today; negative once overdue. */
        val daysUntil: Long,
    )

    enum class Section(val title: String) { OVERDUE("Overdue"), THIS_WEEK("Due this week"), LATER("Later") }

    const val DEFAULT_HORIZON_DAYS = 60L

    /** A detected subscription more than this many days past its projected date has probably stopped; it is left out. */
    const val STALE_SUBSCRIPTION_DAYS = 3L

    private const val WEEK_DAYS = 7L

    fun build(
        bills: List<Bill>,
        subscriptions: List<Subscription>,
        cards: List<CardPaymentReminders.CardReminder>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        horizonDays: Long = DEFAULT_HORIZON_DAYS,
    ): List<Item> {
        fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        fun days(date: LocalDate): Long = ChronoUnit.DAYS.between(today, date)

        val billItems = bills.map { b ->
            val due = dateOf(b.nextDueDateMillis)
            Item(Kind.BILL, b.name, b.amount, b.currencyCode.uppercase(), due, days(due))
        }
        val subscriptionItems = subscriptions.filter { !it.dismissed }.mapNotNull { s ->
            val due = dateOf(s.nextDueDateMillis)
            val d = days(due)
            if (d < -STALE_SUBSCRIPTION_DAYS) null
            else Item(Kind.SUBSCRIPTION, s.merchant, s.averageAmount, s.currencyCode.uppercase(), due, d)
        }
        val cardItems = cards.mapNotNull { c ->
            val due = c.status.dueDate ?: return@mapNotNull null
            if (c.status.amountDue.signum() <= 0) return@mapNotNull null
            Item(Kind.CARD, c.accountName, c.status.amountDue, c.currencyCode.uppercase(), due, days(due))
        }
        return (billItems + subscriptionItems + cardItems)
            .filter { it.daysUntil <= horizonDays }
            .sortedWith(compareBy<Item> { it.dueDate }.thenBy { it.kind.ordinal }.thenBy { it.name.lowercase() })
    }

    fun sectionOf(item: Item): Section = when {
        item.daysUntil < 0 -> Section.OVERDUE
        item.daysUntil <= WEEK_DAYS -> Section.THIS_WEEK
        else -> Section.LATER
    }

    /** The items split into the sections the screen shows, in order, skipping empty ones. */
    fun grouped(items: List<Item>): List<Pair<Section, List<Item>>> =
        Section.entries.mapNotNull { section ->
            items.filter { sectionOf(it) == section }.takeIf { it.isNotEmpty() }?.let { section to it }
        }

    /** Money due in the next [days] days (overdue included), per currency -- never summed across currencies. */
    fun dueWithin(items: List<Item>, days: Long): Map<String, BigDecimal> =
        items.filter { it.daysUntil <= days && it.amount != null }
            .groupBy { it.currencyCode }
            .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { acc, i -> acc + i.amount!! } }
}
