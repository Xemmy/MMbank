package com.xemmy.mmbank.domain.export

import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportRowsTest {

    private fun build(
        txs: List<com.xemmy.mmbank.domain.model.Transaction>,
        accounts: Map<Long, String> = mapOf(1L to "Main"),
        toBase: (BigDecimal, String) -> BigDecimal? = { a, c -> if (c == "AED") a else null },
    ) = ExportRows.build(
        transactions = txs,
        accountNames = accounts,
        categories = listOf(testCategory(1, "Food"), testCategory(2, "Dining", parent = 1)),
        tagNamesByTransaction = mapOf(1L to listOf("Holiday", "Tax")),
        toBase = toBase,
    )

    @Test fun spendIsNegativeAndIncomeIsPositive() {
        val rows = build(
            listOf(
                testTx(id = 1, amount = "10.00"),
                testTx(id = 2, amount = "20.00", direction = TransactionDirection.CREDIT, kind = TransactionKind.INCOME),
                testTx(id = 3, amount = "5.00", direction = TransactionDirection.CREDIT, kind = TransactionKind.REFUND),
                testTx(id = 4, amount = "7.00", kind = TransactionKind.ATM),
            ),
        )
        assertEquals(listOf("-10.00", "20.00", "5.00", "-7.00"), rows.map { it.amount.toPlainString() })
    }

    @Test fun transferAndCardPaymentFollowTheirDirection() {
        val rows = build(
            listOf(
                testTx(id = 1, amount = "100", kind = TransactionKind.TRANSFER, direction = TransactionDirection.DEBIT),
                testTx(id = 2, amount = "100", kind = TransactionKind.TRANSFER, direction = TransactionDirection.CREDIT),
                testTx(id = 3, amount = "50", kind = TransactionKind.CARD_PAYMENT, direction = TransactionDirection.CREDIT),
            ),
        )
        assertEquals(listOf("-100", "100", "50"), rows.map { it.amount.toPlainString() })
    }

    @Test fun anAdjustmentStoredNegativeStaysNegative() {
        val row = build(listOf(testTx(amount = "-12.00", kind = TransactionKind.ADJUSTMENT, direction = TransactionDirection.CREDIT))).single()
        assertEquals("-12.00", row.amount.toPlainString())
    }

    @Test fun baseAmountKeepsTheSignAndIsNullWithoutARate() {
        val rows = build(listOf(testTx(id = 1, amount = "10.00"), testTx(id = 2, amount = "9.00", currency = "USD")))
        assertEquals("-10.00", rows[0].amountInBase?.toPlainString())
        assertNull(rows[1].amountInBase)
    }

    @Test fun categoryShowsLeafAndParent() {
        val row = build(listOf(testTx(categoryId = 2))).single()
        assertEquals("Dining", row.category)
        assertEquals("Food", row.parentCategory)
    }

    @Test fun aTopLevelCategoryHasNoParentAndAnUnknownOneIsBlank() {
        val rows = build(listOf(testTx(id = 1, categoryId = 1), testTx(id = 2, categoryId = 99), testTx(id = 3, categoryId = null)))
        assertEquals("Food", rows[0].category)
        assertNull(rows[0].parentCategory)
        assertNull(rows[1].category)
        assertNull(rows[2].category)
    }

    @Test fun archivedAccountsNeedTheirNameInTheMapAndMissingOnesAreBlank() {
        val rows = build(
            listOf(testTx(id = 1, accountId = 1), testTx(id = 2, accountId = 7), testTx(id = 3, accountId = null)),
            accounts = mapOf(1L to "Main", 7L to "Old card (archived)"),
        )
        assertEquals(listOf("Main", "Old card (archived)", null), rows.map { it.account })
    }

    @Test fun tagsAreCarriedAndBlankTextBecomesNull() {
        val rows = build(listOf(testTx(id = 1, note = "  ", merchant = " ", person = ""), testTx(id = 2)))
        assertEquals(listOf("Holiday", "Tax"), rows[0].tags)
        assertNull(rows[0].note)
        assertNull(rows[0].merchant)
        assertNull(rows[0].person)
        assertEquals(emptyList<String>(), rows[1].tags)
    }

    @Test fun orderIsPreserved() {
        val rows = build(listOf(testTx(id = 5), testTx(id = 2), testTx(id = 9)))
        assertEquals(listOf(5L, 2L, 9L), rows.map { it.id })
    }
}
