package com.xemmy.mmbank.ui.screens

import com.xemmy.mmbank.ui.screens.splits.BalanceDirection
import com.xemmy.mmbank.ui.screens.splits.balanceDirection
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitsBalanceDirectionTest {
    @Test fun positiveIsOwed() = assertEquals(BalanceDirection.OWED, balanceDirection(BigDecimal("12.50")))
    @Test fun negativeOwes() = assertEquals(BalanceDirection.OWES, balanceDirection(BigDecimal("-0.01")))
    @Test fun zeroIsSettled() = assertEquals(BalanceDirection.SETTLED, balanceDirection(BigDecimal("0.00")))
}
