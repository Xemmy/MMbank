package com.xemmy.mmbank.ui.screens.accounts

import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.ui.designsystem.moneyDisplayString
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountsFormatTest {

    @Test
    fun `negative balance renders signed`() {
        val b = BigDecimal("-1234.5")
        assertEquals(TransactionKind.SPEND, balanceKind(b))
        assertEquals(1234.5, balanceMagnitude(b), 0.0)
        assertEquals("−1,234.50 USD", moneyDisplayString(balanceMagnitude(b), "USD", balanceKind(b), hidden = false))
    }

    @Test
    fun `positive and zero balances render unsigned`() {
        val b = BigDecimal("10")
        assertEquals(TransactionKind.TRANSFER, balanceKind(b))
        assertEquals(TransactionKind.TRANSFER, balanceKind(BigDecimal.ZERO))
        assertEquals("10.00 USD", moneyDisplayString(balanceMagnitude(b), "USD", balanceKind(b), hidden = false))
    }

    @Test
    fun `hidden balance shows only the mask`() {
        val b = BigDecimal("-99")
        assertEquals(AMOUNT_MASK, moneyDisplayString(balanceMagnitude(b), "USD", balanceKind(b), hidden = true))
    }
}
