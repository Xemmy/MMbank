package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
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
        kind: TransactionKind = TransactionKind.defaultFor(direction),
        accountId: Long? = null,
        kindUserSet: Boolean = false,
    ) = Transaction(
        id = id,
        accountId = accountId,
        amount = BigDecimal(amount),
        currencyCode = "AED",
        direction = direction,
        source = TransactionSource.SMS,
        timestampMillis = timestampMillis,
        kind = kind,
        kindUserSet = kindUserSet,
    )

    private val hour = 60 * 60 * 1000L

    @Test
    fun `a recognized card-payment credit pairs with an ordinary opposite-direction debit`() {
        val credit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 1_005, kind = TransactionKind.CARD_PAYMENT)
        val debit = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 1_000)
        assertEquals(debit, TransferMatcher.bestMatch(credit, listOf(debit)))
    }

    @Test
    fun `an ordinary debit pairs with an already-recognized transfer credit`() {
        val debit = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 1_000)
        val credit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 1_005, kind = TransactionKind.TRANSFER)
        assertEquals(credit, TransferMatcher.bestMatch(debit, listOf(credit)))
    }

    @Test
    fun `two ordinary transactions are never paired just for matching amount and time`() {
        val debit = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 1_000)
        val credit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 1_050)
        assertNull(TransferMatcher.bestMatch(debit, listOf(credit)))
    }

    @Test
    fun `an ATM withdrawal never pairs with an unrelated same-amount deposit`() {
        // The old keyword check tagged anything mentioning "atm" as TRANSFERS
        // and let it pair -- both rows then vanished from the stats.
        val atm = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 1_000, kind = TransactionKind.ATM)
        val deposit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 1_050)
        assertNull(TransferMatcher.bestMatch(atm, listOf(deposit)))
    }

    @Test
    fun `a plain transfer outside the 10-minute window is rejected`() {
        val credit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 0, kind = TransactionKind.TRANSFER)
        val tooFar = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = TransferMatcher.MATCH_WINDOW_MILLIS + 1)
        assertNull(TransferMatcher.bestMatch(credit, listOf(tooFar)))
    }

    @Test
    fun `a plain transfer exactly at the window boundary is accepted`() {
        val credit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 0, kind = TransactionKind.TRANSFER)
        val atBoundary = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = TransferMatcher.MATCH_WINDOW_MILLIS)
        assertEquals(atBoundary, TransferMatcher.bestMatch(credit, listOf(atBoundary)))
    }

    @Test
    fun `a card payment credit posted two days late still pairs`() {
        val debit = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 0, accountId = 10)
        val cardCredit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 48 * hour, kind = TransactionKind.CARD_PAYMENT, accountId = 20)
        assertEquals(debit, TransferMatcher.bestMatch(cardCredit, listOf(debit)))
    }

    @Test
    fun `a card payment beyond 72 hours is rejected`() {
        val debit = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 0)
        val cardCredit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 73 * hour, kind = TransactionKind.CARD_PAYMENT)
        assertNull(TransferMatcher.bestMatch(cardCredit, listOf(debit)))
    }

    @Test
    fun `a credit on a credit-card account gets the long window too`() {
        val types = mapOf(20L to AccountType.CREDIT_CARD)
        val debit = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 0, accountId = 10)
        val cardCredit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 24 * hour, kind = TransactionKind.TRANSFER, accountId = 20)
        assertEquals(debit, TransferMatcher.bestMatch(cardCredit, listOf(debit), types))
        assertEquals(TransactionKind.CARD_PAYMENT, TransferMatcher.pairKind(cardCredit, debit, types))
    }

    @Test
    fun `two legs on the same account are never a transfer`() {
        val debit = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 0, accountId = 10)
        val credit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 10, kind = TransactionKind.TRANSFER, accountId = 10)
        assertNull(TransferMatcher.bestMatch(credit, listOf(debit)))
    }

    @Test
    fun `a leg the user classified as spending is never pulled into a pair`() {
        val debit = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 0, kind = TransactionKind.SPEND, kindUserSet = true)
        val credit = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 10, kind = TransactionKind.TRANSFER)
        assertNull(TransferMatcher.bestMatch(credit, listOf(debit)))
    }

    @Test
    fun `never matches a transaction to itself`() {
        val credit = tx(id = 1, direction = TransactionDirection.CREDIT, timestampMillis = 1_000, kind = TransactionKind.TRANSFER)
        assertNull(TransferMatcher.bestMatch(credit, listOf(credit)))
    }

    @Test
    fun `rapid back-to-back identical-amount payments pick the closest candidate`() {
        val secondCredit = tx(id = 4, direction = TransactionDirection.CREDIT, amount = "1000.00", timestampMillis = 9_953_000, kind = TransactionKind.TRANSFER)
        val firstDebit = tx(id = 1, direction = TransactionDirection.DEBIT, amount = "1000.00", timestampMillis = 9_921_000)
        val secondDebit = tx(id = 3, direction = TransactionDirection.DEBIT, amount = "1000.00", timestampMillis = 9_950_000)
        assertEquals(secondDebit, TransferMatcher.bestMatch(secondCredit, listOf(firstDebit, secondDebit)))
    }

    @Test
    fun `pair kind is a plain transfer between two non-card accounts`() {
        val a = tx(id = 1, direction = TransactionDirection.DEBIT, timestampMillis = 0, accountId = 10)
        val b = tx(id = 2, direction = TransactionDirection.CREDIT, timestampMillis = 0, kind = TransactionKind.TRANSFER, accountId = 11)
        assertEquals(TransactionKind.TRANSFER, TransferMatcher.pairKind(a, b, mapOf(10L to AccountType.CHECKING, 11L to AccountType.SAVINGS)))
    }
}
