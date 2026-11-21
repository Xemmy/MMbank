package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceChangeReasonTest {

    @Test
    fun `names round trip and unknown ones are null`() {
        BalanceChangeReason.entries.forEach { assertEquals(it, BalanceChangeReason.parse(it.name)) }
        assertNull(BalanceChangeReason.parse("NOPE"))
        assertNull(BalanceChangeReason.parse(null))
    }

    @Test
    fun `labelFor shows the label, or the raw text of an unknown reason`() {
        assertEquals("Cash count", BalanceChangeReason.labelFor("CASH_COUNT"))
        assertEquals("Paid the landlord", BalanceChangeReason.labelFor("  Paid the landlord "))
        assertNull(BalanceChangeReason.labelFor(null))
        assertNull(BalanceChangeReason.labelFor("   "))
    }

    @Test
    fun `labels are distinct`() {
        assertEquals(BalanceChangeReason.entries.size, BalanceChangeReason.entries.map { it.label }.toSet().size)
    }

    @Test
    fun `every type is offered every reason exactly once`() {
        AccountType.entries.forEach { type ->
            val ordered = BalanceChangeReason.orderedFor(type)
            assertEquals(type.name, BalanceChangeReason.entries.toSet(), ordered.toSet())
            assertEquals(type.name, BalanceChangeReason.entries.size, ordered.size)
        }
    }

    @Test
    fun `the likeliest reason for the account type comes first`() {
        assertEquals(BalanceChangeReason.CASH_COUNT, BalanceChangeReason.orderedFor(AccountType.CASH).first())
        assertEquals(BalanceChangeReason.MARKET_VALUE, BalanceChangeReason.orderedFor(AccountType.INVESTMENT).first())
        assertEquals(BalanceChangeReason.MARKET_VALUE, BalanceChangeReason.orderedFor(AccountType.CRYPTO_SPOT).first())
        assertEquals(BalanceChangeReason.INTEREST_OR_FEES, BalanceChangeReason.orderedFor(AccountType.LOAN).first())
        assertEquals(BalanceChangeReason.MISSED_TRANSACTION, BalanceChangeReason.orderedFor(AccountType.CHECKING).first())
        assertTrue(BalanceChangeReason.OTHER == BalanceChangeReason.orderedFor(AccountType.CHECKING).last())
    }

    @Test
    fun `quick add picks a sensible reason per type`() {
        assertEquals(BalanceChangeReason.CASH_COUNT, BalanceChangeReason.forQuickAdd(AccountType.CASH))
        assertEquals(BalanceChangeReason.MARKET_VALUE, BalanceChangeReason.forQuickAdd(AccountType.INVESTMENT))
        assertEquals(BalanceChangeReason.MARKET_VALUE, BalanceChangeReason.forQuickAdd(AccountType.CRYPTO_SPOT))
        assertEquals(BalanceChangeReason.STATEMENT_CORRECTION, BalanceChangeReason.forQuickAdd(AccountType.CHECKING))
        assertEquals(BalanceChangeReason.STATEMENT_CORRECTION, BalanceChangeReason.forQuickAdd(AccountType.CREDIT_CARD))
    }
}
