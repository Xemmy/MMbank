package com.xemmy.mmbank.ui.screens.accounts

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Balance adjustment" reconciliation row is a ledger correction, not real
 * spending or income — [buildBalanceAdjustment] must always flag it out of
 * analytics while keeping it an ordinary, balance-affecting transaction.
 */
class BalanceAdjustmentTest {

    private val account = Account(
        id = 5,
        name = "ADCB Current",
        type = AccountType.CHECKING,
        currencyCode = "AED",
    )

    @Test
    fun `shortfall posts an excluded credit for the gap`() {
        val adjustment = buildBalanceAdjustment(
            account = account,
            computedBalance = 900.0,
            targetBalance = BigDecimal("1000.00"),
            nowMillis = 1_700_000_000_000,
        )!!
        assertTrue(adjustment.excludeFromStats)
        assertEquals(TransactionDirection.CREDIT, adjustment.direction)
        assertEquals(BigDecimal("100.00"), adjustment.amount)
        assertEquals(5L, adjustment.accountId)
        assertEquals("AED", adjustment.currencyCode)
        assertEquals("Balance adjustment", adjustment.merchant)
        assertEquals(TransactionSource.MANUAL, adjustment.source)
        assertEquals(SpendingCategory.OTHER, adjustment.spendingCategory)
        assertEquals(1_700_000_000_000, adjustment.timestampMillis)
    }

    @Test
    fun `overshoot posts an excluded debit for the gap`() {
        val adjustment = buildBalanceAdjustment(
            account = account,
            computedBalance = 1250.75,
            targetBalance = BigDecimal("1200.00"),
        )!!
        assertTrue(adjustment.excludeFromStats)
        assertEquals(TransactionDirection.DEBIT, adjustment.direction)
        assertEquals(BigDecimal("50.75"), adjustment.amount)
    }

    @Test
    fun `matching balance produces no adjustment`() {
        assertNull(
            buildBalanceAdjustment(
                account = account,
                computedBalance = 1000.0,
                targetBalance = BigDecimal("1000.00"),
            )
        )
    }

    @Test
    fun `sub-cent drift rounds away to no adjustment`() {
        // Computed balances arrive as doubles; anything under half a cent
        // rounds to the same 2-dp figure and must not create a phantom row.
        assertNull(
            buildBalanceAdjustment(
                account = account,
                computedBalance = 1000.004,
                targetBalance = BigDecimal("1000.00"),
            )
        )
    }
}
