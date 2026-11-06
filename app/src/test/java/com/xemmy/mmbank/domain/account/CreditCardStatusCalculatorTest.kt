package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.account.CreditCardStatusCalculator.Status
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditCardStatusCalculatorTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 20)

    private val card = Account(
        id = 3,
        name = "ADCB Card",
        type = AccountType.CREDIT_CARD,
        currencyCode = "AED",
        creditLimit = BigDecimal("10000"),
        statementCycleDay = 10,
        dueDayOfMonth = 25,
    )

    private fun tx(
        date: LocalDate,
        amount: String,
        direction: TransactionDirection,
        kind: TransactionKind = TransactionKind.defaultFor(direction),
        accountId: Long = 3,
    ) = Transaction(
        accountId = accountId,
        amount = BigDecimal(amount),
        currencyCode = "AED",
        direction = direction,
        source = TransactionSource.SMS,
        timestampMillis = date.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000,
        kind = kind,
    )

    private fun status(balance: String, txs: List<Transaction>, account: Account = card, on: LocalDate = today) =
        CreditCardStatusCalculator.status(account, BigDecimal(balance), txs, on, zone)

    @Test
    fun `owed, available and utilisation come from the computed balance`() {
        val s = status("-2500", emptyList())
        assertEquals(0, BigDecimal("2500").compareTo(s.outstanding))
        assertEquals(0, BigDecimal("7500").compareTo(s.available))
        assertEquals(0.25, s.utilisation!!, 1e-9)
    }

    @Test
    fun `statement is the balance when the cycle started, and payments since reduce what's due`() {
        // Cycle started 10 Oct. Since then: 300 of new spending and a 1,000 payment.
        val txs = listOf(
            tx(LocalDate.of(2026, 10, 12), "300", TransactionDirection.DEBIT),
            tx(LocalDate.of(2026, 10, 15), "1000", TransactionDirection.CREDIT, TransactionKind.CARD_PAYMENT),
            tx(LocalDate.of(2026, 10, 2), "999", TransactionDirection.DEBIT), // before the cycle
        )
        // Balance now -1,800 => at cycle start it was -1,800 - (+1000 - 300) = -2,500.
        val s = status("-1800", txs)
        assertEquals(LocalDate.of(2026, 10, 10), s.cycleStart)
        assertEquals(0, BigDecimal("2500").compareTo(s.statementBalance))
        assertEquals(0, BigDecimal("1000").compareTo(s.paidSinceStatement))
        assertEquals(0, BigDecimal("1500").compareTo(s.amountDue))
        assertEquals(0, BigDecimal("300").compareTo(s.spentThisCycle))
        assertEquals(LocalDate.of(2026, 10, 25), s.dueDate)
        assertEquals(5L, s.daysUntilDue)
        assertEquals(Status.DUE_SOON, s.status)
        assertFalse(s.estimated)
    }

    @Test
    fun `paying the full statement marks it paid even with new spending`() {
        val txs = listOf(
            tx(LocalDate.of(2026, 10, 11), "2500", TransactionDirection.CREDIT, TransactionKind.CARD_PAYMENT),
            tx(LocalDate.of(2026, 10, 12), "400", TransactionDirection.DEBIT),
        )
        val s = status("-400", txs)
        assertEquals(0, BigDecimal.ZERO.compareTo(s.amountDue))
        assertEquals(Status.PAID, s.status)
    }

    @Test
    fun `a refund on the card is not counted as a payment`() {
        val txs = listOf(tx(LocalDate.of(2026, 10, 11), "100", TransactionDirection.CREDIT, TransactionKind.REFUND))
        val s = status("-2400", txs)
        assertEquals(0, BigDecimal.ZERO.compareTo(s.paidSinceStatement))
        // ...but nothing more than is actually owed is ever "due".
        assertEquals(0, BigDecimal("2400").compareTo(s.amountDue))
    }

    @Test
    fun `past the due date with money still due is overdue`() {
        val s = status("-2500", emptyList(), on = LocalDate.of(2026, 10, 27))
        assertEquals(LocalDate.of(2026, 10, 25), s.dueDate)
        assertEquals(-2L, s.daysUntilDue)
        assertEquals(Status.OVERDUE, s.status)
    }

    @Test
    fun `a due day before the statement day falls in the next month`() {
        val s = status("-2500", emptyList(), account = card.copy(dueDayOfMonth = 5))
        assertEquals(LocalDate.of(2026, 11, 5), s.dueDate)
        assertEquals(Status.DUE, s.status)
    }

    @Test
    fun `a card in credit owes nothing`() {
        val s = status("150", emptyList())
        assertEquals(0, BigDecimal.ZERO.compareTo(s.outstanding))
        assertEquals(0, BigDecimal("10000").compareTo(s.available))
        assertEquals(Status.NOTHING_OWED, s.status)
    }

    @Test
    fun `without a statement day everything owed is due, marked estimated`() {
        val s = status("-700", emptyList(), account = card.copy(statementCycleDay = null))
        assertNull(s.statementBalance)
        assertTrue(s.estimated)
        assertEquals(0, BigDecimal("700").compareTo(s.amountDue))
        assertEquals(LocalDate.of(2026, 10, 25), s.dueDate)
    }

    @Test
    fun `without a due day there's no date to be late for`() {
        val s = status("-700", emptyList(), account = card.copy(dueDayOfMonth = null))
        assertEquals(Status.NO_DUE_DATE, s.status)
        assertNull(s.daysUntilDue)
    }

    @Test
    fun `without a limit there's no utilisation`() {
        val s = status("-700", emptyList(), account = card.copy(creditLimit = null))
        assertNull(s.available)
        assertNull(s.utilisation)
    }

    @Test
    fun `other accounts' transactions are ignored`() {
        val other = tx(LocalDate.of(2026, 10, 12), "5000", TransactionDirection.DEBIT, accountId = 99)
        assertEquals(0, BigDecimal.ZERO.compareTo(status("-2500", listOf(other)).spentThisCycle))
    }

    @Test
    fun `day 31 clamps to the end of a short month`() {
        assertEquals(
            LocalDate.of(2026, 2, 28),
            CreditCardStatusCalculator.mostRecentDay(31, LocalDate.of(2026, 3, 15)),
        )
        assertEquals(
            LocalDate.of(2028, 2, 29),
            CreditCardStatusCalculator.firstOnOrAfter(31, LocalDate.of(2028, 2, 10)),
        )
        assertEquals(
            LocalDate.of(2026, 3, 10),
            CreditCardStatusCalculator.mostRecentDay(10, LocalDate.of(2026, 3, 10)),
        )
    }
}
