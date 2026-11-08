package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.account.CardPaymentReminders
import com.xemmy.mmbank.domain.account.CreditCardStatusCalculator
import com.xemmy.mmbank.domain.intelligence.WhatChangedDigest.Item
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatChangedDigestTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 7)
    private val now = today.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()
    private val day = 24L * 60 * 60 * 1000
    private val toBase: (Transaction) -> Double? = { if (it.currencyCode == "AED") it.amount.toDouble() else null }

    private val dining = Category(id = 2, name = "Dining", icon = "", colorHex = "#000000", monthlyCap = BigDecimal("500"))
    private val groceries = Category(id = 3, name = "Groceries", icon = "", colorHex = "#000000", monthlyCap = BigDecimal("1000"))
    private val categories = listOf(dining, groceries)

    private fun spend(amount: String, date: LocalDate, categoryId: Long? = null, merchant: String? = null, kind: TransactionKind = TransactionKind.SPEND) =
        Transaction(
            amount = BigDecimal(amount),
            currencyCode = "AED",
            direction = if (kind == TransactionKind.REFUND) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
            merchant = merchant,
            source = TransactionSource.SMS,
            timestampMillis = date.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli(),
            categoryId = categoryId,
            kind = kind,
        )

    private fun build(
        transactions: List<Transaction> = emptyList(),
        subscriptions: List<Subscription> = emptyList(),
        bills: List<Bill> = emptyList(),
        cards: List<CardPaymentReminders.CardReminder> = emptyList(),
        quiet: Int? = null,
    ) = WhatChangedDigest.build(transactions, categories, subscriptions, bills, cards, toBase, "AED", today, zone, now, quiet)

    @Test
    fun `spend against last week, refunds netted`() {
        val d = build(
            listOf(
                spend("200", today.minusDays(1)),
                spend("50", today.minusDays(2), kind = TransactionKind.REFUND),
                spend("100", today.minusDays(9)),
            ),
        )!!
        assertEquals(150.0, d.thisWeekSpend, 1e-9)
        assertEquals(100.0, d.lastWeekSpend, 1e-9)
        assertEquals(50.0, d.percentChange!!, 1e-9)
    }

    @Test
    fun `an empty week with nothing to say is null`() {
        assertNull(build())
    }

    @Test
    fun `categories at or over their cap`() {
        val d = build(listOf(spend("520", today.minusDays(1), categoryId = 2), spend("850", today.minusDays(2), categoryId = 3)))!!
        val caps = d.items.filterIsInstance<WhatChangedDigest.CategoryCap>()
        assertEquals(listOf("Dining" to true, "Groceries" to false), caps.map { it.category to it.over })
    }

    @Test
    fun `bills and card payments due within a week`() {
        val bill = Bill(id = 1, name = "Rent", amount = BigDecimal("4000"), currencyCode = "aed", nextDueDateMillis = now + 3 * day, createdAt = 0)
        val later = bill.copy(id = 2, name = "Insurance", nextDueDateMillis = now + 20 * day)
        val status = CreditCardStatusCalculator.CardStatus(
            outstanding = BigDecimal("900"), creditLimit = null, available = null, utilisation = null, cycleStart = null,
            statementBalance = BigDecimal("900"), paidSinceStatement = BigDecimal.ZERO, amountDue = BigDecimal("900"),
            dueDate = today.plusDays(2), daysUntilDue = 2, spentThisCycle = BigDecimal.ZERO,
            status = CreditCardStatusCalculator.Status.DUE_SOON, estimated = false,
        )
        val card = CardPaymentReminders.CardReminder(9, "Visa", "AED", status)
        val due = build(bills = listOf(bill, later), cards = listOf(card))!!.items.filterIsInstance<WhatChangedDigest.PaymentDue>()
        assertEquals(listOf("Rent" to false, "Visa" to true), due.map { it.name to it.isCard })
        assertEquals(3L, due[0].daysUntil)
    }

    @Test
    fun `new subscriptions and price changes seen this week`() {
        fun sub(merchant: String, count: Int, previous: String? = null, lastSeen: Long = now - day) = Subscription(
            merchant = merchant, averageAmount = BigDecimal("45"), currencyCode = "AED",
            cadence = SubscriptionCadence.entries.first(), nextDueDateMillis = now + 20 * day, lastSeenMillis = lastSeen,
            occurrenceCount = count, previousAmount = previous?.let(::BigDecimal),
        )
        val d = build(
            subscriptions = listOf(
                sub("Netflix", 3),
                sub("Spotify", 8, previous = "40"),
                sub("Old", 3, lastSeen = now - 30 * day),
                sub("Gym", 12),
            ),
        )!!
        assertEquals(listOf("Netflix"), d.items.filterIsInstance<WhatChangedDigest.NewSubscription>().map { it.merchant })
        val change = d.items.filterIsInstance<WhatChangedDigest.PriceChange>().single()
        assertEquals("Spotify", change.merchant)
        assertEquals(BigDecimal("40"), change.from)
    }

    @Test
    fun `the category that jumped most against last week`() {
        val d = build(
            listOf(
                spend("300", today.minusDays(1), categoryId = 3),
                spend("100", today.minusDays(8), categoryId = 3),
                spend("60", today.minusDays(1), categoryId = 2),
                spend("50", today.minusDays(8), categoryId = 2),
            ),
        )!!
        val swing = d.items.filterIsInstance<WhatChangedDigest.CategorySwing>().single()
        assertEquals("Groceries", swing.category)
    }

    @Test
    fun `unusual charges this week are included`() {
        val history = (1..5).map { spend("24", today.minusDays(20L + it), merchant = "Careem") }
        val d = build(history + spend("100", today.minusDays(1), merchant = "Careem"))!!
        assertTrue(d.items.any { it is WhatChangedDigest.UnusualCharge })
    }

    @Test
    fun `capture quiet comes first and the list is capped`() {
        val bills = (1L..8L).map { Bill(id = it, name = "Bill $it", amount = BigDecimal.TEN, currencyCode = "AED", nextDueDateMillis = now + day, createdAt = 0) }
        val d = build(bills = bills, quiet = 5)!!
        assertEquals(WhatChangedDigest.MAX_ITEMS, d.items.size)
        assertEquals(WhatChangedDigest.CaptureQuiet(5), d.items.first())
        assertTrue(d.items.drop(1).all { it is WhatChangedDigest.PaymentDue })
    }

    @Test
    fun `items are ranked`() {
        val items: List<Item> = build(
            transactions = listOf(spend("520", today.minusDays(1), categoryId = 2)),
            bills = listOf(Bill(id = 1, name = "Rent", amount = BigDecimal.TEN, currencyCode = "AED", nextDueDateMillis = now + day, createdAt = 0)),
        )!!.items
        assertTrue(items.first() is WhatChangedDigest.CategoryCap)
    }
}
