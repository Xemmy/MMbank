package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.usecase.QuickAdd.Result
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAddTest {

    private fun account(id: Long, type: AccountType, currency: String = "AED", archived: Boolean = false) =
        Account(id = id, name = "A$id", type = type, currencyCode = currency, archived = archived)

    private fun ok(amount: String, merchant: String = "", accounts: List<Account> = emptyList(), currency: String = "AED") =
        (QuickAdd.build(amount, merchant, currency, accounts, 123L) as Result.Ok).transaction

    private fun invalid(amount: String, currency: String = "AED") =
        (QuickAdd.build(amount, "", currency, emptyList(), 0L) as Result.Invalid).reason

    @Test
    fun `a valid amount becomes a manual cash spend`() {
        val tx = ok("12.50", "Corner Cafe")
        assertEquals(0, tx.amount.compareTo(BigDecimal("12.5")))
        assertEquals("AED", tx.currencyCode)
        assertEquals(TransactionDirection.DEBIT, tx.direction)
        assertEquals(TransactionSource.MANUAL, tx.source)
        assertEquals(TransactionKind.SPEND, tx.kind)
        assertTrue(tx.kindUserSet)
        assertEquals("Corner Cafe", tx.merchant)
        assertEquals(123L, tx.timestampMillis)
        assertEquals(QuickAdd.RULE_LABEL, tx.matchedRuleName)
    }

    @Test
    fun `the merchant is optional, trimmed and capped`() {
        assertNull(ok("5", "   ").merchant)
        assertEquals("Shop", ok("5", "  Shop  ").merchant)
        assertEquals(QuickAdd.MAX_MERCHANT_CHARS, ok("5", "x".repeat(200)).merchant!!.length)
    }

    @Test
    fun `thousands separators, spaces and a decimal comma are read`() {
        assertEquals(0, ok("1,250.00").amount.compareTo(BigDecimal("1250")))
        assertEquals(0, ok(" 1 250 ").amount.compareTo(BigDecimal("1250")))
        assertEquals(0, ok("12,5").amount.compareTo(BigDecimal("12.5")))
        assertEquals(0, ok("1,250").amount.compareTo(BigDecimal("1250")))
    }

    @Test
    fun `empty, text, zero, negative, too precise and oversized amounts are rejected`() {
        assertTrue(invalid("").contains("Enter an amount"))
        assertTrue(invalid("abc").contains("Enter an amount"))
        assertTrue(invalid("-5").contains("Enter an amount"))
        assertTrue(invalid("0").contains("more than zero"))
        assertTrue(invalid("0.00").contains("more than zero"))
        assertTrue(invalid("1.234").contains("2 decimals"))
        assertTrue(invalid("10000001").contains("too large"))
        assertTrue(invalid("5", currency = "  ").contains("currency"))
    }

    @Test
    fun `it lands on a cash account, preferring one in the same currency, never an archived one`() {
        val accounts = listOf(
            account(1, AccountType.CHECKING),
            account(2, AccountType.CASH, "USD"),
            account(3, AccountType.CASH, "AED"),
            account(4, AccountType.CASH, "AED", archived = true),
        )
        assertEquals(3L, ok("5", accounts = accounts).accountId)
        assertEquals(2L, ok("5", accounts = accounts, currency = "USD").accountId)
        // No cash account in the currency: any cash account beats leaving it unassigned.
        assertEquals(2L, ok("5", accounts = accounts, currency = "EUR").accountId)
    }

    @Test
    fun `with no cash account it stays unassigned`() {
        assertNull(ok("5", accounts = listOf(account(1, AccountType.CHECKING))).accountId)
        assertNull(ok("5", accounts = listOf(account(9, AccountType.CASH, archived = true))).accountId)
    }

    @Test
    fun `a known merchant gets a spending category guess`() {
        assertEquals(SpendingCategory.DINING, ok("5", "Starbucks").spendingCategory)
        assertEquals(SpendingCategory.UNCATEGORIZED, ok("5", "").spendingCategory)
    }
}
