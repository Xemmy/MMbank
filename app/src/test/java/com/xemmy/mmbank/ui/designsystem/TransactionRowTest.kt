package com.xemmy.mmbank.ui.designsystem

import com.xemmy.mmbank.domain.model.TransactionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionRowTest {
    private fun row(
        kind: TransactionKind = TransactionKind.SPEND,
        category: String? = "Groceries",
        account: String? = "ADCB ••1234",
        time: String? = "14:20",
        pending: Boolean = false,
        excluded: Boolean = false,
        merchant: String = "Carrefour",
        amount: Double = 86.40,
    ) = TransactionRowUi(1, merchant, amount, "AED", kind, category, account, time, pending, excluded)

    @Test
    fun `densities are 72 and 56`() {
        assertEquals(72, TransactionRowDensity.COMFORTABLE.minHeightDp)
        assertEquals(56, TransactionRowDensity.COMPACT.minHeightDp)
    }

    @Test
    fun `subtitle joins category and account`() {
        assertEquals("Groceries · ADCB ••1234", transactionRowSubtitle(row()))
    }

    @Test
    fun `subtitle omits missing or blank account`() {
        assertEquals("Groceries", transactionRowSubtitle(row(account = null)))
        assertEquals("Groceries", transactionRowSubtitle(row(account = " ")))
    }

    @Test
    fun `uncategorised spend is flagged`() {
        val r = row(category = null)
        assertTrue(isUncategorised(r))
        assertEquals("Uncategorised · ADCB ••1234", transactionRowSubtitle(r))
        assertEquals(listOf(TransactionRowTag("Uncategorised", MMTone.INFO)), transactionRowTags(r))
        assertTrue(isUncategorised(row(category = "  ")))
    }

    @Test
    fun `transfers need no category and lead with their kind`() {
        val r = row(kind = TransactionKind.TRANSFER, category = null)
        assertFalse(isUncategorised(r))
        assertEquals("Transfer · ADCB ••1234", transactionRowSubtitle(r))
        assertTrue(transactionRowTags(r).isEmpty())
        assertEquals("Card payment", transactionRowSubtitle(row(kind = TransactionKind.CARD_PAYMENT, account = null)))
    }

    @Test
    fun `tags come in a stable order`() {
        val tags = transactionRowTags(row(category = null, pending = true, excluded = true))
        assertEquals(listOf("Pending", "Uncategorised", "Excluded"), tags.map { it.label })
        assertEquals(MMTone.WARNING, tags[0].tone)
        assertNull(tags[2].tone)
    }

    @Test
    fun `plain categorised row has no tags`() {
        assertTrue(transactionRowTags(row()).isEmpty())
    }

    @Test
    fun `initial is the first letter or digit uppercased`() {
        assertEquals("C", merchantInitial("carrefour"))
        assertEquals("N", merchantInitial("  *noon"))
        assertEquals("7", merchantInitial("7-Eleven"))
        assertEquals("?", merchantInitial("  "))
        assertEquals("?", merchantInitial("***"))
    }

    @Test
    fun `description is one sentence in reading order`() {
        assertEquals(
            "Carrefour, spent 86 dirhams 40 fils, Groceries, ADCB ••1234, 14:20",
            transactionRowDescription(row(), hidden = false),
        )
    }

    @Test
    fun `description speaks states`() {
        assertEquals(
            "Carrefour, spent 86 dirhams 40 fils, uncategorised, ADCB ••1234, 14:20, pending, excluded from insights",
            transactionRowDescription(row(category = null, pending = true, excluded = true), hidden = false),
        )
        assertEquals(
            "Move, transferred 500 dirhams",
            transactionRowDescription(
                row(kind = TransactionKind.TRANSFER, category = null, account = null, time = null, merchant = "Move", amount = 500.0),
                hidden = false,
            ),
        )
    }

    @Test
    fun `hidden description never leaks the amount`() {
        val text = transactionRowDescription(row(), hidden = true)
        assertTrue(text.contains("amount hidden"))
        assertFalse(text.contains("86"))
    }

    @Test
    fun `blank merchant reads as unknown`() {
        assertTrue(transactionRowDescription(row(merchant = " "), hidden = false).startsWith("Unknown merchant, "))
    }

    @Test
    fun `excluded alpha is 60 percent`() {
        assertEquals(0.6f, EXCLUDED_ROW_ALPHA, 0f)
    }

    @Test
    fun `row is interactive on tap or long-press only`() {
        assertFalse(transactionRowIsInteractive(hasClick = false, hasLongClick = false))
        assertTrue(transactionRowIsInteractive(hasClick = true, hasLongClick = false))
        assertTrue(transactionRowIsInteractive(hasClick = false, hasLongClick = true))
        assertEquals("Select", LONG_CLICK_LABEL)
    }
}
