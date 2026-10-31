package com.xemmy.mmbank.ui.screens.accounts

import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
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
            computedBalance = BigDecimal("900.00"),
            targetBalance = BigDecimal("1000.00"),
            nowMillis = 1_700_000_000_000,
        )!!
        assertEquals(TransactionKind.ADJUSTMENT, adjustment.kind)
        assertEquals(LedgerMath.Effect.NONE, LedgerMath.effect(adjustment))
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
            computedBalance = BigDecimal("1250.75"),
            targetBalance = BigDecimal("1200.00"),
        )!!
        assertEquals(TransactionKind.ADJUSTMENT, adjustment.kind)
        assertEquals(LedgerMath.Effect.NONE, LedgerMath.effect(adjustment))
        assertEquals(TransactionDirection.DEBIT, adjustment.direction)
        assertEquals(BigDecimal("50.75"), adjustment.amount)
    }

    @Test
    fun `matching balance produces no adjustment`() {
        assertNull(
            buildBalanceAdjustment(
                account = account,
                computedBalance = BigDecimal("1000.00"),
                targetBalance = BigDecimal("1000.00"),
            )
        )
    }

    @Test
    fun `sub-cent drift rounds away to no adjustment`() {
        // Anything under half a cent rounds to the same 2-dp figure and must
        // not create a phantom row.
        assertNull(
            buildBalanceAdjustment(
                account = account,
                computedBalance = BigDecimal("1000.004"),
                targetBalance = BigDecimal("1000.00"),
            )
        )
    }

    @Test
    fun `an exact computed balance produces no spurious one-cent adjustment`() {
        // The computed balance now arrives as an exact BigDecimal rather than a
        // Double. A sum like 0.1 + 0.2 that Double renders as 0.30000000000000004
        // used to be capable of manufacturing a one-cent correction out of
        // nothing; with exact arithmetic it agrees with the target exactly.
        val exact = BigDecimal("0.1") + BigDecimal("0.2")
        assertNull(
            buildBalanceAdjustment(
                account = account,
                computedBalance = exact,
                targetBalance = BigDecimal("0.30"),
            )
        )
    }
}
