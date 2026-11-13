package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpeningBalanceSolverTest {

    private fun tx(
        id: Long, at: Long, amount: String, dir: TransactionDirection,
        balance: String? = null, kind: ReportedBalanceKind? = ReportedBalanceKind.ACCOUNT_BALANCE,
    ) = Transaction(
        id = id, amount = BigDecimal(amount), currencyCode = "AED", direction = dir,
        source = TransactionSource.SMS, timestampMillis = at,
        reportedBalance = balance?.let(::BigDecimal), reportedBalanceKind = if (balance != null) kind else null,
    )

    private fun eq(expected: String, actual: BigDecimal?) =
        assertEquals("expected $expected got $actual", 0, BigDecimal(expected).compareTo(actual))

    @Test
    fun `opening is the printed balance minus everything up to it`() {
        val rows = listOf(
            tx(1, 1000, "100", TransactionDirection.DEBIT),
            tx(2, 2000, "40", TransactionDirection.CREDIT, balance = "500"),
        )
        eq("560", OpeningBalanceSolver.solve(AccountType.CHECKING, rows))
    }

    @Test
    fun `rows after the anchor do not count`() {
        val rows = listOf(
            tx(1, 1000, "100", TransactionDirection.DEBIT, balance = "900"),
            tx(2, 2000, "50", TransactionDirection.DEBIT),
        )
        // Computed at the anchor: opening - 100 = 900 => opening 1000 (the later row is moved on top of it).
        eq("1000", OpeningBalanceSolver.solve(AccountType.CHECKING, rows))
    }

    @Test
    fun `the newest printed balance is the anchor`() {
        val rows = listOf(
            tx(1, 1000, "10", TransactionDirection.DEBIT, balance = "90"),
            tx(2, 2000, "10", TransactionDirection.DEBIT, balance = "80"),
        )
        eq("100", OpeningBalanceSolver.solve(AccountType.CHECKING, rows))
    }

    @Test
    fun `nothing printed means unknown`() {
        assertNull(OpeningBalanceSolver.solve(AccountType.CHECKING, listOf(tx(1, 1000, "10", TransactionDirection.DEBIT))))
        assertNull(OpeningBalanceSolver.solve(AccountType.CHECKING, emptyList()))
    }

    @Test
    fun `a card needs its limit and works from available credit`() {
        val rows = listOf(
            tx(1, 1000, "300", TransactionDirection.DEBIT, balance = "4700", kind = ReportedBalanceKind.AVAILABLE_CREDIT),
        )
        assertNull(OpeningBalanceSolver.solve(AccountType.CREDIT_CARD, rows))
        // Owes 5000 - 4700 = 300, so the books should read -300; after the -300 row, opening is 0.
        eq("0", OpeningBalanceSolver.solve(AccountType.CREDIT_CARD, rows, creditLimit = BigDecimal("5000")))
    }

    @Test
    fun `a card ignores a printed account balance`() {
        val rows = listOf(tx(1, 1000, "300", TransactionDirection.DEBIT, balance = "4700", kind = ReportedBalanceKind.ACCOUNT_BALANCE))
        assertNull(OpeningBalanceSolver.solve(AccountType.CREDIT_CARD, rows, creditLimit = BigDecimal("5000")))
    }

    @Test
    fun `a card in credit owes nothing`() {
        val rows = listOf(tx(1, 1000, "10", TransactionDirection.CREDIT, balance = "6000", kind = ReportedBalanceKind.AVAILABLE_CREDIT))
        // Target is min(6000-5000, 0) = 0; opening = 0 - 10 = -10.
        eq("-10", OpeningBalanceSolver.solve(AccountType.CREDIT_CARD, rows, creditLimit = BigDecimal("5000")))
    }
}
