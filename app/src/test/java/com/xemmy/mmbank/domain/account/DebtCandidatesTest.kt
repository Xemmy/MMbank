package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtCandidatesTest {

    private fun account(id: Long, type: AccountType, rate: Double? = null, installment: String? = null, outstanding: String? = null) =
        Account(
            id = id, name = "A$id", type = type, currencyCode = "aed",
            profitRatePa = rate,
            nextInstallmentAmount = installment?.let(::BigDecimal),
            outstandingBalance = outstanding?.let(::BigDecimal),
        )

    @Test
    fun `cards and loans that owe money become debts, largest first`() {
        val accounts = listOf(
            account(1, AccountType.CREDIT_CARD, rate = 39.0),
            account(2, AccountType.LOAN, rate = 6.5, installment = "1200", outstanding = "40000"),
            account(3, AccountType.CHECKING),
            account(4, AccountType.CREDIT_CARD),
        )
        val balances = mapOf(1L to BigDecimal("-3000"), 2L to BigDecimal("0"), 3L to BigDecimal("-50"), 4L to BigDecimal("200"))
        val debts = DebtCandidates.build(accounts, balances)
        assertEquals(listOf(2L, 1L), debts.map { it.accountId })

        val loan = debts[0]
        assertEquals(BigDecimal("40000.00"), loan.owed)
        assertEquals(BigDecimal("1200.00"), loan.minimumPayment)
        assertFalse(loan.minimumIsEstimate)
        assertEquals("AED", loan.currencyCode)

        val card = debts[1]
        assertEquals(BigDecimal("3000.00"), card.owed)
        assertEquals(39.0, card.aprPercent!!, 0.0)
        assertEquals(BigDecimal("150.00"), card.minimumPayment)
        assertTrue(card.minimumIsEstimate)
    }

    @Test
    fun `a loan without a stated outstanding uses its balance`() {
        val debts = DebtCandidates.build(listOf(account(2, AccountType.LOAN)), mapOf(2L to BigDecimal("-900")))
        assertEquals(BigDecimal("900.00"), debts.single().owed)
        assertNull(debts.single().aprPercent)
    }

    @Test
    fun `estimated minimum is 5 percent with a floor of 100, never more than owed`() {
        assertEquals(BigDecimal("250.00"), DebtCandidates.estimatedMinimum(BigDecimal("5000")))
        assertEquals(BigDecimal("100.00"), DebtCandidates.estimatedMinimum(BigDecimal("800")))
        assertEquals(BigDecimal("60.00"), DebtCandidates.estimatedMinimum(BigDecimal("60")))
    }

    @Test
    fun `duration labels`() {
        assertEquals("11 months", DebtCandidates.durationLabel(11))
        assertEquals("1 year", DebtCandidates.durationLabel(12))
        assertEquals("2 years 3 months", DebtCandidates.durationLabel(27))
        assertEquals("1 month", DebtCandidates.durationLabel(1))
        assertEquals("0 months", DebtCandidates.durationLabel(0))
    }
}
