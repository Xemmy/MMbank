package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategorySuggestionEngineTest {

    private val starterCategories = listOf(
        Category(id = 1, name = "Groceries", icon = "🛒", colorHex = "#4CAF50"),
        Category(id = 2, name = "Dining", icon = "🍽️", colorHex = "#FF7043"),
        Category(id = 3, name = "Transport", icon = "🚕", colorHex = "#29B6F6"),
        Category(id = 9, name = "Uncategorized", icon = "🏷️", colorHex = "#9E9E9E"),
    )

    private fun tx(
        merchant: String? = null,
        rawMessage: String? = null,
        needsCategoryApproval: Boolean = false,
        suggestedCategoryName: String? = null,
        suggestedCategoryIcon: String? = null,
        suggestedCategoryColorHex: String? = null,
        extractedMerchant: String? = null,
    ) = Transaction(
        amount = BigDecimal("50.00"),
        currencyCode = "AED",
        direction = TransactionDirection.DEBIT,
        source = TransactionSource.SMS,
        timestampMillis = 0,
        merchant = merchant,
        rawMessage = rawMessage,
        needsCategoryApproval = needsCategoryApproval,
        suggestedCategoryName = suggestedCategoryName,
        suggestedCategoryIcon = suggestedCategoryIcon,
        suggestedCategoryColorHex = suggestedCategoryColorHex,
        extractedMerchant = extractedMerchant,
    )

    @Test
    fun `a pending suggestion matching an existing category is offered first, using the existing id`() {
        val transaction = tx(
            merchant = "Some Cafe",
            needsCategoryApproval = true,
            suggestedCategoryName = "Dining",
        )
        val result = CategorySuggestionEngine.suggest(transaction, starterCategories)
        assertEquals(2L, result.first().categoryId)
        assertEquals("Dining", result.first().name)
    }

    @Test
    fun `a pending suggestion with no matching category comes back with a null id, ready to be created`() {
        val transaction = tx(
            merchant = "Pet Zone",
            needsCategoryApproval = true,
            suggestedCategoryName = "Pets",
            suggestedCategoryIcon = "🐾",
            suggestedCategoryColorHex = "#8D6E63",
        )
        val result = CategorySuggestionEngine.suggest(transaction, starterCategories)
        val top = result.first()
        assertNull(top.categoryId)
        assertEquals("Pets", top.name)
        assertEquals("🐾", top.icon)
    }

    @Test
    fun `MerchantCategorizer keyword hit bridges onto the same-named dynamic category`() {
        val transaction = tx(merchant = "Careem Ride", rawMessage = "You spent AED 20 at Careem Ride")
        val result = CategorySuggestionEngine.suggest(transaction, starterCategories)
        assertTrue(result.any { it.categoryId == 3L && it.name == "Transport" })
    }

    @Test
    fun `suggestions never exceed the requested limit`() {
        val transaction = tx(merchant = "Carrefour Grocery Store", rawMessage = "Carrefour Grocery Store dining transport")
        val result = CategorySuggestionEngine.suggest(transaction, starterCategories, limit = 3)
        assertTrue(result.size <= 3)
    }

    @Test
    fun `Uncategorized is never offered as a suggestion`() {
        val transaction = tx(merchant = "Unknown Merchant")
        val result = CategorySuggestionEngine.suggest(transaction, starterCategories)
        assertTrue(result.none { it.name.equals("Uncategorized", ignoreCase = true) })
    }

    @Test
    fun `no category or merchant signal still returns picks without duplicates`() {
        val transaction = tx()
        val result = CategorySuggestionEngine.suggest(transaction, starterCategories)
        assertEquals(result.map { it.categoryId }.distinct().size, result.size)
    }

    @Test
    fun `extractedMerchant is used for the keyword guess when merchant is blank`() {
        val transaction = tx(merchant = null, extractedMerchant = "Careem Ride", rawMessage = "ride payment")
        val result = CategorySuggestionEngine.suggest(transaction, starterCategories)
        assertTrue(result.any { it.name == "Transport" })
    }
}
