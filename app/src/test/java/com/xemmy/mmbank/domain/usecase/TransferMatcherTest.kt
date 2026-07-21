package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferMatcherTest {

    private fun tx(
        id: Long,
        direction: TransactionDirection,
        amount: String = "3600.00",
        timestampMillis: Long,
        spendingCategory: SpendingCategory = SpendingCategory.UNCATEGORIZED,
    ) = Transaction(
        id = id,
        amount = BigDecimal(amount),
        currencyCode = "AED",
        direction = direction,
        source = TransactionSource.SMS,
        timestampMillis = timestampMillis,
        spendingCategory = spendingCategory,
    )

    @Test
    fun `a recognized transfer-credit pairs with an ordinary opposite-direction candidate`() {
        val credit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 1_005, spendingCategory = SpendingCategory.TRANSFERS)
        val debit = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 1_000)
        assertEquals(debit, TransferMatcher.bestMatch(credit, listOf(debit)))
    }

    @Test
    fun `an ordinary debit pairs with an already-recognized transfer-credit candidate`() {
        val debit = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 1_000)
        val credit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 1_005, spendingCategory = SpendingCategory.TRANSFERS)
        assertEquals(credit, TransferMatcher.bestMatch(debit, listOf(credit)))
    }

    @Test
    fun `two ordinary transactions are never paired just for matching amount and time`() {
        // Neither side is tagged TRANSFERS -- e.g. a coincidental salary credit
        // and an unrelated same-amount debit minutes apart must stay separate.
        val debit = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 1_000)
        val credit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 1_050)
        assertNull(TransferMatcher.bestMatch(debit, listOf(credit)))
    }

    @Test
    fun `a candidate outside the match window is rejected`() {
        val credit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 0, spendingCategory = SpendingCategory.TRANSFERS)
        val tooFar = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = TransferMatcher.MATCH_WINDOW_MILLIS + 1)
        assertNull(TransferMatcher.bestMatch(credit, listOf(tooFar)))
    }

    @Test
    fun `a candidate at exactly the match window boundary is accepted`() {
        val credit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 0, spendingCategory = SpendingCategory.TRANSFERS)
        val atBoundary = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = TransferMatcher.MATCH_WINDOW_MILLIS)
        assertEquals(atBoundary, TransferMatcher.bestMatch(credit, listOf(atBoundary)))
    }

    @Test
    fun `never matches a transaction to itself`() {
        val credit = tx(id = 1, direction = TransactionDirection.CREDIT, timestampMillis = 1_000, spendingCategory = SpendingCategory.TRANSFERS)
        assertNull(TransferMatcher.bestMatch(credit, listOf(credit)))
    }

    @Test
    fun `rapid back-to-back identical-amount payments pick the closest candidate, not a stale one`() {
        // Two AED 1,000 card payments a minute apart: by the time the second
        // credit is processed, the caller only supplies still-unlinked
        // candidates, so debit1 (already paired) is never in the list. This
        // pins the tie-break rule that keeps that guarantee correct even if
        // more than one unlinked candidate of the same amount is ever passed.
        val secondCredit = tx(id = 4, direction = TransactionDirection.CREDIT, amount = "1000.00", timestampMillis = 9_953_000, spendingCategory = SpendingCategory.TRANSFERS)
        val firstDebit = tx(id = 1, direction = TransactionDirection.DEBIT, amount = "1000.00", timestampMillis = 9_921_000)
        val secondDebit = tx(id = 3, direction = TransactionDirection.DEBIT, amount = "1000.00", timestampMillis = 9_950_000)
        val match = TransferMatcher.bestMatch(secondCredit, listOf(firstDebit, secondDebit))
        assertEquals(secondDebit, match)
    }

    @Test
    fun `picks the closest-in-time candidate when several qualify`() {
        val credit = tx(id = 3, direction = TransactionDirection.CREDIT, timestampMillis = 10_000, spendingCategory = SpendingCategory.TRANSFERS)
        val near = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 10_100)
        val far = tx(id = 2, direction = TransactionDirection.DEBIT, timestampMillis = 5_000)
        assertEquals(near, TransferMatcher.bestMatch(credit, listOf(far, near)))
    }
}
