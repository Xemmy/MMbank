package com.xemmy.mmbank.ui.screens.transactions

import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.designsystem.MoneyTone
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Amounts store magnitude and direction carries the sign, so the tone that
 * picks the amount's list color must read DEBIT as negative (copper), CREDIT
 * as positive (mint), and any zero — 0, 0.0, 0.00 — as neutral either way.
 */
class AmountToneTest {

    @Test
    fun `debit reads as negative money`() {
        assertEquals(
            AmountTone.NEGATIVE,
            amountTone(BigDecimal("85.00"), TransactionDirection.DEBIT),
        )
    }

    @Test
    fun `credit reads as positive money`() {
        assertEquals(
            AmountTone.POSITIVE,
            amountTone(BigDecimal("1200.50"), TransactionDirection.CREDIT),
        )
    }

    @Test
    fun `zero is neutral regardless of direction or scale`() {
        for (zero in listOf("0", "0.0", "0.00")) {
            for (direction in TransactionDirection.entries) {
                assertEquals(
                    "amount=$zero direction=$direction",
                    AmountTone.NEUTRAL,
                    amountTone(BigDecimal(zero), direction),
                )
            }
        }
    }

    @Test
    fun `tones map onto the design-system money tones`() {
        assertEquals(MoneyTone.OUT, AmountTone.NEGATIVE.toMoneyTone())
        assertEquals(MoneyTone.IN, AmountTone.POSITIVE.toMoneyTone())
        assertEquals(MoneyTone.NEUTRAL, AmountTone.NEUTRAL.toMoneyTone())
    }
}
