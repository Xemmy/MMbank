package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction

/**
 * A one-tap category pick offered by [CategorySuggestionEngine]. [categoryId]
 * is null only for a not-yet-created pending AI suggestion -- assigning it
 * must create the [Category] first (mirrors the existing accept-suggestion
 * flow in [com.xemmy.mmbank.ui.screens.transactions.UncategorizedQueueViewModel]).
 */
data class CategorySuggestion(
    val categoryId: Long?,
    val name: String,
    val icon: String,
    val colorHex: String,
)

/**
 * Ranks up to [limit] dynamic [Category] picks for a transaction still
 * missing one, for the Review screen's one-tap [CategorySuggestionRow]. Pure
 * and top-level so it's JVM-testable without a running Room/Flow pipeline.
 *
 * Ranking, first match wins a slot:
 * 1. A pending AI category suggestion on the transaction itself, if any --
 *    matched onto an existing same-named [Category] when one exists.
 * 2. [MerchantCategorizer]'s fixed-enum keyword guess, bridged onto a
 *    same-named dynamic category (the starter set is seeded with matching
 *    names -- "Dining", "Groceries", …).
 * 3. Other categories whose name appears in the merchant/message text.
 * 4. Whatever's left, in list order, skipping "Uncategorized".
 */
object CategorySuggestionEngine {

    fun suggest(transaction: Transaction, categories: List<Category>, limit: Int = 3): List<CategorySuggestion> {
        val picks = mutableListOf<CategorySuggestion>()
        val usedNames = mutableSetOf<String>()

        if (transaction.needsCategoryApproval && !transaction.suggestedCategoryName.isNullOrBlank()) {
            val name = transaction.suggestedCategoryName
            val existing = categories.firstOrNull { it.name.equals(name, ignoreCase = true) }
            picks += existing?.let { it.toSuggestion() }
                ?: CategorySuggestion(
                    categoryId = null,
                    name = name,
                    icon = transaction.suggestedCategoryIcon ?: "🏷️",
                    colorHex = transaction.suggestedCategoryColorHex ?: "#9E9E9E",
                )
            usedNames += name.lowercase()
        }

        if (picks.size < limit) {
            val guessed = MerchantCategorizer.categorize(
                transaction.extractedMerchant ?: transaction.merchant,
                transaction.rawMessage,
            )
            if (guessed != SpendingCategory.UNCATEGORIZED) {
                categories.firstOrNull { it.name.equals(guessed.label, ignoreCase = true) }
                    ?.takeIf { it.name.lowercase() !in usedNames }
                    ?.let {
                        picks += it.toSuggestion()
                        usedNames += it.name.lowercase()
                    }
            }
        }

        if (picks.size < limit) {
            val haystack = buildString {
                transaction.merchant?.let { append(it).append(' ') }
                transaction.extractedMerchant?.let { append(it).append(' ') }
                transaction.rawMessage?.let { append(it) }
            }.lowercase()

            val remaining = categories.filter {
                it.name.lowercase() !in usedNames && !it.name.equals("Uncategorized", ignoreCase = true)
            }
            val keywordMatches = remaining.filter { haystack.contains(it.name.lowercase()) }
            val rest = remaining.filterNot { it in keywordMatches }

            for (category in keywordMatches + rest) {
                if (picks.size >= limit) break
                if (usedNames.add(category.name.lowercase())) picks += category.toSuggestion()
            }
        }

        return picks.take(limit)
    }

    private fun Category.toSuggestion() = CategorySuggestion(id, name, icon, colorHex)
}
