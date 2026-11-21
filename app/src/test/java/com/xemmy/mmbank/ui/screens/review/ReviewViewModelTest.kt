package com.xemmy.mmbank.ui.screens.review

import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReviewViewModel]'s pure logic: confidence bucketing, ranked-suggestion
 * queue building and the batch-approval selection rule. The ViewModel class
 * itself is a thin wrapper over [com.xemmy.mmbank.di.AppContainer] repository
 * calls (same pattern as DashboardViewModel) -- these top-level functions
 * carry everything worth pinning under a plain JVM test.
 */
class ReviewViewModelTest {

    private val categories = listOf(
        Category(id = 1, name = "Dining", icon = "🍽️", colorHex = "#FF7043"),
        Category(id = 2, name = "Groceries", icon = "🛒", colorHex = "#4CAF50"),
    )

    private fun tx(
        id: Long,
        merchant: String? = "Some Merchant",
        needsCategoryApproval: Boolean = false,
        suggestedCategoryName: String? = null,
        aiConfidence: Int? = null,
    ) = Transaction(
        id = id,
        amount = BigDecimal("25.00"),
        currencyCode = "AED",
        direction = TransactionDirection.DEBIT,
        source = TransactionSource.SMS,
        timestampMillis = 0,
        merchant = merchant,
        needsCategoryApproval = needsCategoryApproval,
        suggestedCategoryName = suggestedCategoryName,
        aiConfidence = aiConfidence,
    )

    // --- confidence bucketing (drives the Green/Yellow/Red chip) ---

    @Test
    fun `scores at or above 80 read as HIGH`() {
        assertEquals(ConfidenceLevel.HIGH, confidenceLevelOf(80))
        assertEquals(ConfidenceLevel.HIGH, confidenceLevelOf(100))
    }

    @Test
    fun `scores from 50 to 79 read as MEDIUM`() {
        assertEquals(ConfidenceLevel.MEDIUM, confidenceLevelOf(50))
        assertEquals(ConfidenceLevel.MEDIUM, confidenceLevelOf(79))
    }

    @Test
    fun `scores below 50, including null (unscored), read as LOW`() {
        assertEquals(ConfidenceLevel.LOW, confidenceLevelOf(49))
        assertEquals(ConfidenceLevel.LOW, confidenceLevelOf(null))
    }

    // --- queue building / category assignment inputs ---

    @Test
    fun `every transaction in the queue gets a confidence bucket and up to 3 suggestions`() {
        val transactions = listOf(
            tx(1, aiConfidence = 90, needsCategoryApproval = true, suggestedCategoryName = "Dining"),
            tx(2, aiConfidence = 20),
        )
        val items = buildReviewQueue(transactions, categories)
        assertEquals(2, items.size)
        assertEquals(ConfidenceLevel.HIGH, items[0].confidenceLevel)
        assertEquals(ConfidenceLevel.LOW, items[1].confidenceLevel)
        assertTrue(items[0].suggestions.isNotEmpty())
        assertTrue(items[0].suggestions.size <= 3)
    }

    // --- "queue popping": once a transaction is no longer in the uncategorized
    // source list (assigned a category), it must not reappear in the built queue.

    @Test
    fun `a transaction removed from the source list is gone from the built queue`() {
        val before = listOf(tx(1), tx(2))
        assertEquals(2, buildReviewQueue(before, categories).size)

        // Simulates transaction 1 being assigned a category and dropping out of
        // observeUncategorized()'s stream -- the ViewModel never removes it by hand.
        val after = before.filterNot { it.id == 1L }
        val items = buildReviewQueue(after, categories)
        assertEquals(1, items.size)
        assertEquals(2L, items.single().transaction.id)
    }

    // --- batch approval selection ---

    @Test
    fun `only pending suggestions at or above the threshold are batch-approvable`() {
        val transactions = listOf(
            tx(1, needsCategoryApproval = true, suggestedCategoryName = "Dining", aiConfidence = 85),
            tx(2, needsCategoryApproval = true, suggestedCategoryName = "Groceries", aiConfidence = 60),
            tx(3, needsCategoryApproval = false, aiConfidence = 95),
            tx(4, needsCategoryApproval = true, suggestedCategoryName = null, aiConfidence = 95),
        )
        val approvable = highConfidenceApprovable(transactions)
        assertEquals(listOf(1L), approvable.map { it.id })
    }

    @Test
    fun `a custom threshold widens or narrows the batch-approvable set`() {
        val transactions = listOf(
            tx(1, needsCategoryApproval = true, suggestedCategoryName = "Dining", aiConfidence = 60),
        )
        assertTrue(highConfidenceApprovable(transactions, threshold = 80).isEmpty())
        assertEquals(1, highConfidenceApprovable(transactions, threshold = 50).size)
    }

    @Test
    fun `an unscored pending suggestion (null confidence) is never batch-approvable`() {
        val transactions = listOf(tx(1, needsCategoryApproval = true, suggestedCategoryName = "Dining", aiConfidence = null))
        assertTrue(highConfidenceApprovable(transactions).isEmpty())
    }
}
