package com.xemmy.mmbank.ui.screens.transactions

import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorKindsTest {

    @Test
    fun `the editor offers the everyday kinds`() {
        val kinds = editorKindsFor(null)
        assertEquals(
            listOf(
                TransactionKind.SPEND, TransactionKind.INCOME, TransactionKind.REFUND,
                TransactionKind.TRANSFER, TransactionKind.CARD_PAYMENT, TransactionKind.ATM,
            ),
            kinds,
        )
        assertFalse(TransactionKind.ADJUSTMENT in kinds)
    }

    @Test
    fun `a rarer current kind stays selectable when editing`() {
        assertTrue(TransactionKind.CASHBACK in editorKindsFor(TransactionKind.CASHBACK))
        assertEquals(editorKindsFor(null), editorKindsFor(TransactionKind.REFUND))
    }

    @Test
    fun `each one-sided kind fixes the direction, two-sided ones don't`() {
        assertEquals(TransactionDirection.DEBIT, TransactionKind.SPEND.fixedDirection)
        assertEquals(TransactionDirection.DEBIT, TransactionKind.ATM.fixedDirection)
        assertEquals(TransactionDirection.CREDIT, TransactionKind.REFUND.fixedDirection)
        assertEquals(TransactionDirection.CREDIT, TransactionKind.INCOME.fixedDirection)
        assertNull(TransactionKind.TRANSFER.fixedDirection)
        assertNull(TransactionKind.CARD_PAYMENT.fixedDirection)
    }

    @Test
    fun `the hint says how the kind counts`() {
        assertEquals("Money back — lowers spending, not income", kindHint(TransactionKind.REFUND, transferMode = false))
        assertTrue(kindHint(TransactionKind.TRANSFER, transferMode = true).contains("two linked sides"))
    }
}
