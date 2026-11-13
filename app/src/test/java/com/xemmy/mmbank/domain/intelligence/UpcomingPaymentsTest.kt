package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.account.CardPaymentReminders
import com.xemmy.mmbank.domain.account.CreditCardStatusCalculator
import com.xemmy.mmbank.domain.intelligence.UpcomingPayments.Kind
import com.xemmy.mmbank.domain.intelligence.UpcomingPayments.Section
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpcomingPaymentsTest {

    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 12)

    private fun millis(d: LocalDate) = d.atTime(12, 0).atZone(utc).toInstant().toEpochMilli()

    private fun bill(name: String, inDays: Long, amount: String = "100", currency: String = "AED") = Bill(
        name = name, amount = BigDecimal(amount), currencyCode = currency,
        nextDueDateMillis = millis(today.plusDays(inDays)), createdAt = 0,
    )

    private fun sub(name: String, inDays: Long, amount: String = "30") = Subscription(
        merchant = name, averageAmount = BigDecimal(amount), currencyCode = "AED", cadence = SubscriptionCadence.MONTHLY,
        nextDueDateMillis = millis(today.plusDays(inDays)), lastSeenMillis = 0, occurrenceCount = 3,
    )

    private fun card(name: String, inDays: Long?, due: String = "500") = CardPaymentReminders.CardReminder(
        accountId = 1, accountName = name, currencyCode = "AED",
        status = CreditCardStatusCalculator.CardStatus(
            outstanding = BigDecimal(due), creditLimit = null, available = null, utilisation = null, cycleStart = null,
            statementBalance = BigDecimal(due), paidSinceStatement = BigDecimal.ZERO, amountDue = BigDecimal(due),
            dueDate = inDays?.let { today.plusDays(it) }, daysUntilDue = inDays, spentThisCycle = BigDecimal.ZERO,
            status = CreditCardStatusCalculator.Status.DUE, estimated = false,
        ),
    )

    private fun build(
        bills: List<Bill> = emptyList(), subs: List<Subscription> = emptyList(), cards: List<CardPaymentReminders.CardReminder> = emptyList(),
        horizon: Long = UpcomingPayments.DEFAULT_HORIZON_DAYS,
    ) = UpcomingPayments.build(bills, subs, cards, today, utc, horizon)

    @Test
    fun `bills, subscriptions and card dues merge into one list in date order`() {
        val items = build(listOf(bill("Rent", 9)), listOf(sub("Netflix", 3)), listOf(card("Visa", 20)))
        assertEquals(listOf("Netflix", "Rent", "Visa"), items.map { it.name })
        assertEquals(listOf(Kind.SUBSCRIPTION, Kind.BILL, Kind.CARD), items.map { it.kind })
        assertEquals(listOf(3L, 9L, 20L), items.map { it.daysUntil })
    }

    @Test
    fun `an overdue bill comes first and is marked overdue`() {
        val items = build(listOf(bill("Rent", 5), bill("Phone", -4)))
        assertEquals("Phone", items.first().name)
        assertEquals(Section.OVERDUE, UpcomingPayments.sectionOf(items.first()))
    }

    @Test
    fun `a subscription that stopped long ago is left out, a just-late one is kept`() {
        val items = build(subs = listOf(sub("Old", -30), sub("Late", -2)))
        assertEquals(listOf("Late"), items.map { it.name })
    }

    @Test
    fun `dismissed subscriptions never appear`() {
        assertTrue(build(subs = listOf(sub("X", 4).copy(dismissed = true))).isEmpty())
    }

    @Test
    fun `a card with nothing owed or no due date is left out, the amount due is used`() {
        val items = build(cards = listOf(card("A", 5, "0"), card("B", null), card("C", 6, "250")))
        assertEquals(listOf("C"), items.map { it.name })
        assertEquals(0, items.single().amount!!.compareTo(BigDecimal("250")))
    }

    @Test
    fun `anything beyond the horizon is not shown`() {
        val items = build(listOf(bill("Soon", 10), bill("Far", 90)), horizon = 60)
        assertEquals(listOf("Soon"), items.map { it.name })
    }

    @Test
    fun `sections split overdue, this week and later, skipping empty ones`() {
        val items = build(listOf(bill("Late", -1), bill("Today", 0), bill("Week", 7), bill("Next", 8)))
        val groups = UpcomingPayments.grouped(items)
        assertEquals(listOf(Section.OVERDUE, Section.THIS_WEEK, Section.LATER), groups.map { it.first })
        assertEquals(listOf("Today", "Week"), groups[1].second.map { it.name })
        assertEquals(listOf(Section.LATER), UpcomingPayments.grouped(build(listOf(bill("Next", 30)))).map { it.first })
    }

    @Test
    fun `due within sums per currency and never across them`() {
        val items = build(listOf(bill("A", 2, "100"), bill("B", 5, "50"), bill("C", 3, "20", "USD"), bill("D", 40, "999")))
        val total = UpcomingPayments.dueWithin(items, 7)
        assertEquals(0, total.getValue("AED").compareTo(BigDecimal("150")))
        assertEquals(0, total.getValue("USD").compareTo(BigDecimal("20")))
        assertEquals(setOf("AED", "USD"), total.keys)
    }

    @Test
    fun `ties on the same day order by kind then name`() {
        val items = build(listOf(bill("Zeta", 4), bill("Alpha", 4)), listOf(sub("Mid", 4)))
        assertEquals(listOf("Alpha", "Zeta", "Mid"), items.map { it.name })
    }

    @Test
    fun `nothing tracked means an empty list`() {
        assertTrue(build().isEmpty())
        assertTrue(UpcomingPayments.grouped(emptyList()).isEmpty())
    }
}
