package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerMathTest {

    private fun tx(
        amount: String,
        kind: TransactionKind,
        direction: TransactionDirection = when (kind) {
            TransactionKind.INCOME, TransactionKind.REFUND, TransactionKind.CASHBACK -> TransactionDirection.CREDIT
            else -> TransactionDirection.DEBIT
        },
        excluded: Boolean = false,
        currency: String = "AED",
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = currency,
        direction = direction,
        source = TransactionSource.SMS,
        timestampMillis = 0,
        kind = kind,
        excludeFromStats = excluded,
    )

    @Test
    fun `spend and ATM add, refunds subtract`() {
        val rows = listOf(
            tx("100", TransactionKind.SPEND),
            tx("50", TransactionKind.ATM),
            tx("30", TransactionKind.REFUND),
        )
        assertEquals(0, BigDecimal("120").compareTo(LedgerMath.netSpendExact(rows)))
        assertEquals(120.0, LedgerMath.netSpend(rows) { it.amount.toDouble() }, 0.0)
    }

    @Test
    fun `a refund is not income`() {
        val refund = tx("30", TransactionKind.REFUND)
        assertFalse(LedgerMath.isIncome(refund))
        assertEquals(0, BigDecimal.ZERO.compareTo(LedgerMath.incomeDelta(refund)))
        assertFalse(LedgerMath.isSpend(refund))
        assertTrue(LedgerMath.affectsSpend(refund))
    }

    @Test
    fun `card payments, transfers, cashback and adjustments are in neither total`() {
        val rows = listOf(
            tx("3600", TransactionKind.CARD_PAYMENT),
            tx("3600", TransactionKind.CARD_PAYMENT, direction = TransactionDirection.CREDIT),
            tx("500", TransactionKind.TRANSFER),
            tx("500", TransactionKind.TRANSFER, direction = TransactionDirection.CREDIT),
            tx("5", TransactionKind.CASHBACK),
            tx("12", TransactionKind.ADJUSTMENT),
        )
        assertEquals(0, BigDecimal.ZERO.compareTo(LedgerMath.netSpendExact(rows)))
        assertEquals(0, BigDecimal.ZERO.compareTo(LedgerMath.incomeExact(rows)))
    }

    @Test
    fun `income counts as received`() {
        val rows = listOf(tx("1000", TransactionKind.INCOME), tx("20", TransactionKind.SPEND))
        assertEquals(0, BigDecimal("1000").compareTo(LedgerMath.incomeExact(rows)))
    }

    @Test
    fun `a row the user hid counts toward nothing`() {
        val rows = listOf(tx("100", TransactionKind.SPEND, excluded = true), tx("100", TransactionKind.INCOME, excluded = true))
        assertEquals(0, BigDecimal.ZERO.compareTo(LedgerMath.netSpendExact(rows)))
        assertEquals(0, BigDecimal.ZERO.compareTo(LedgerMath.incomeExact(rows)))
    }

    @Test
    fun `raw mode is plain debits versus credits`() {
        val rows = listOf(
            tx("3600", TransactionKind.CARD_PAYMENT),
            tx("30", TransactionKind.REFUND),
            tx("100", TransactionKind.SPEND, excluded = true),
        )
        assertEquals(0, BigDecimal("3700").compareTo(LedgerMath.netSpendExact(rows, raw = true)))
        assertEquals(0, BigDecimal("30").compareTo(LedgerMath.incomeExact(rows, raw = true)))
    }

    @Test
    fun `unconvertible rows are skipped in converted sums`() {
        val rows = listOf(tx("100", TransactionKind.SPEND), tx("100", TransactionKind.SPEND, currency = "XYZ"))
        val total = LedgerMath.netSpend(rows) { if (it.currencyCode == "AED") it.amount.toDouble() else null }
        assertEquals(100.0, total, 0.0)
    }
}
