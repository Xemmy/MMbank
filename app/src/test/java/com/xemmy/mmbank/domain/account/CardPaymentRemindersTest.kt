package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.account.CardPaymentReminders.CardReminder
import com.xemmy.mmbank.domain.account.CreditCardStatusCalculator.CardStatus
import com.xemmy.mmbank.domain.account.CreditCardStatusCalculator.Status
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPaymentRemindersTest {

    private fun status(status: Status, days: Long?, due: String = "500") = CardStatus(
        outstanding = BigDecimal(due),
        creditLimit = null,
        available = null,
        utilisation = null,
        cycleStart = null,
        statementBalance = null,
        paidSinceStatement = BigDecimal.ZERO,
        amountDue = BigDecimal(due),
        dueDate = days?.let { LocalDate.of(2026, 10, 20).plusDays(it) },
        daysUntilDue = days,
        spentThisCycle = BigDecimal.ZERO,
        status = status,
        estimated = false,
    )

    private fun card(id: Long, status: CardStatus) = CardReminder(id, "Card $id", "AED", status)

    @Test
    fun `reminds within three days and when overdue, most urgent first`() {
        val cards = listOf(
            card(1, status(Status.DUE_SOON, 3)),
            card(2, status(Status.DUE_SOON, 6)),
            card(3, status(Status.OVERDUE, -1)),
            card(4, status(Status.PAID, 1, due = "0")),
            card(5, status(Status.DUE, 12)),
        )
        assertEquals(listOf(3L, 1L), CardPaymentReminders.pending(cards, emptySet()).map { it.accountId })
    }

    @Test
    fun `a due date is announced once, and the next month's re-arms`() {
        val thisMonth = card(1, status(Status.DUE_SOON, 2))
        val sent = setOf(CardPaymentReminders.reminderKey(thisMonth))
        assertTrue(CardPaymentReminders.pending(listOf(thisMonth), sent).isEmpty())
        val nextMonth = card(1, status(Status.DUE_SOON, 32 - 30))
            .copy(status = status(Status.DUE_SOON, 2).copy(dueDate = LocalDate.of(2026, 11, 22)))
        assertEquals(1, CardPaymentReminders.pending(listOf(nextMonth), sent).size)
    }

    @Test
    fun `prune keeps only keys for due dates still in play`() {
        val c = card(1, status(Status.DUE_SOON, 2))
        val key = CardPaymentReminders.reminderKey(c)
        assertEquals(setOf(key), CardPaymentReminders.prune(setOf(key, "card:9:2020-01-01"), listOf(c)))
    }

    @Test
    fun `build covers only credit cards, with balances from the ledger`() {
        val accounts = listOf(
            Account(id = 1, name = "Current", type = AccountType.CHECKING, currencyCode = "AED"),
            Account(id = 2, name = "Card", type = AccountType.CREDIT_CARD, currencyCode = "AED", dueDayOfMonth = 25),
        )
        val entries = listOf(
            AccountBalanceCalculator.LedgerEntry(2, BigDecimal("300"), "AED", TransactionDirection.DEBIT),
        )
        val built = CardPaymentReminders.build(accounts, emptyList(), entries, LocalDate.of(2026, 10, 20))
        assertEquals(listOf(2L), built.map { it.accountId })
        assertEquals(0, BigDecimal("300").compareTo(built.single().status.outstanding))
        assertEquals(Status.DUE_SOON, built.single().status.status)
        assertEquals(built, CardPaymentReminders.dueSoon(built))
    }

    @Test
    fun `when text reads naturally`() {
        assertEquals("due in 3d", CardPaymentReminders.whenText(3))
        assertEquals("due tomorrow", CardPaymentReminders.whenText(1))
        assertEquals("due today", CardPaymentReminders.whenText(0))
        assertEquals("was due 2d ago", CardPaymentReminders.whenText(-2))
    }
}
