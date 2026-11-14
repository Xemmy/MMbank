package com.xemmy.mmbank.ui.screens.accounts

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import java.math.BigDecimal
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountFormTest {

    private val zone = ZoneId.of("UTC")

    private fun input(type: AccountType = AccountType.CHECKING) =
        AccountFormInput(name = "ADCB", type = type, currency = "aed", openingBalance = "1,250.50")

    @Test
    fun `money input accepts commas, spaces and a minus sign`() {
        assertEquals(BigDecimal("1250.50"), parseMoneyInput(" 1,250.50 "))
        assertEquals(BigDecimal("-300"), parseMoneyInput("-300"))
        assertEquals(BigDecimal("2000"), parseMoneyInput("2 000"))
        assertNull(parseMoneyInput(""))
        assertNull(parseMoneyInput("abc"))
    }

    @Test
    fun `a minimal valid form has no errors`() {
        assertTrue(AccountFormValidator.errors(input()).isEmpty())
    }

    @Test
    fun `name, currency and opening balance are checked`() {
        val errors = AccountFormValidator.errors(input().copy(name = " ", currency = "AE", openingBalance = "x"))
        assertEquals(setOf(AccountField.NAME, AccountField.CURRENCY, AccountField.OPENING), errors.keys)
    }

    @Test
    fun `card fields are validated only for credit cards`() {
        val bad = input(AccountType.CREDIT_CARD).copy(creditLimit = "-5", statementDay = "32", dueDay = "0")
        assertEquals(
            setOf(AccountField.CREDIT_LIMIT, AccountField.STATEMENT_DAY, AccountField.DUE_DAY),
            AccountFormValidator.errors(bad).keys,
        )
        // The same junk on a current account is simply not part of the form.
        assertTrue(AccountFormValidator.errors(bad.copy(type = AccountType.CHECKING)).isEmpty())
    }

    @Test
    fun `loan fields are validated for loans`() {
        val bad = input(AccountType.LOAN).copy(profitRate = "-1", maturity = "31/12/2030", outstanding = "lots")
        assertEquals(
            setOf(AccountField.PROFIT_RATE, AccountField.MATURITY, AccountField.OUTSTANDING),
            AccountFormValidator.errors(bad).keys,
        )
    }

    @Test
    fun `a credit card saves its limit, statement and due day and card numbers`() {
        val saved = AccountFormValidator.apply(
            Account(name = "", type = AccountType.CHECKING, currencyCode = "USD"),
            input(AccountType.CREDIT_CARD).copy(
                creditLimit = "20,000", statementDay = "10", dueDay = "5",
                accountNumber = " 876 ", cardEndings = "*5555, 66",
            ),
            zone,
        )
        assertEquals(AccountType.CREDIT_CARD, saved.type)
        assertEquals("AED", saved.currencyCode)
        assertEquals(BigDecimal("1250.50"), saved.openingBalance)
        assertEquals(BigDecimal("20000"), saved.creditLimit)
        assertEquals(10, saved.statementCycleDay)
        assertEquals(5, saved.dueDayOfMonth)
        assertEquals("876", saved.accountNumber)
        assertEquals("5555", saved.cardEndings)
    }

    @Test
    fun `switching type clears the fields that no longer apply`() {
        val wasLoan = Account(
            name = "Car loan", type = AccountType.LOAN, currencyCode = "AED",
            outstandingBalance = BigDecimal("40000"), profitRatePa = 4.5, loanNotes = "x",
            creditLimit = BigDecimal("1"), statementCycleDay = 3,
        )
        val saved = AccountFormValidator.apply(wasLoan, input(AccountType.SAVINGS), zone)
        assertNull(saved.outstandingBalance)
        assertNull(saved.profitRatePa)
        assertNull(saved.loanNotes)
        assertNull(saved.creditLimit)
        assertNull(saved.statementCycleDay)
        assertNull(saved.dueDayOfMonth)
    }

    @Test
    fun `a loan keeps its loan fields and parses the maturity date`() {
        val saved = AccountFormValidator.apply(
            Account(name = "", type = AccountType.LOAN, currencyCode = "AED"),
            input(AccountType.LOAN).copy(outstanding = "40,000", profitRate = "4.5%", maturity = "2030-01-01", dueDay = "15"),
            zone,
        )
        assertEquals(BigDecimal("40000"), saved.outstandingBalance)
        assertEquals(4.5, saved.profitRatePa!!, 0.0)
        assertEquals(1_893_456_000_000L, saved.maturityDateMillis)
        assertEquals(15, saved.dueDayOfMonth)
        assertNull(saved.cardEndings)
    }

    @Test
    fun `card endings are normalized`() {
        assertEquals("4321, 8765", AccountFormValidator.normalizeCardEndings("4321, *8765 , 12, 4321"))
        assertNull(AccountFormValidator.normalizeCardEndings(" , 12"))
    }
}
