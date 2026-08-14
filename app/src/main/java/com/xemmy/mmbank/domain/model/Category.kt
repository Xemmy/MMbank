package com.xemmy.mmbank.domain.model

import java.math.BigDecimal

/**
 * A user-facing spending category. The starter set (Groceries, Dining, …) is
 * seeded once on first run; [isCustom] marks categories created later — either
 * by hand via [com.xemmy.mmbank.ui.components.CategoryPickerBottomSheet] or
 * accepted from an AI suggestion in the Uncategorized queue — so the UI can
 * tell the two apart without a separate flag table.
 */
data class Category(
    val id: Long = 0,
    val name: String,
    val icon: String,
    val colorHex: String,
    val isCustom: Boolean = false,
    /** Optional monthly spend cap in the transaction's currency; null = no cap. */
    val monthlyCap: BigDecimal? = null,
    /**
     * Parent category, for a two-level hierarchy (e.g. "Dining" under "Food").
     * A parent's [com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator] status
     * rolls up its children's spend on top of its own, so its cap reads as a
     * combined budget. Null = a top-level category.
     */
    val parentCategoryId: Long? = null,
)

/**
 * A learned merchant -> category rule, consulted at capture time (see
 * [com.xemmy.mmbank.domain.parser.CategoryMatcher]) so future transactions
 * from the same merchant self-categorize without asking again.
 *
 * [pending] distinguishes a confirmed rule (categoryId set, matched by
 * [com.xemmy.mmbank.domain.parser.CategoryMatcher]) from an unconfirmed AI
 * guess awaiting human approval (categoryId null, the suggested* fields carry
 * the proposal) — mirrors the existing pending-[ParsingRule] pattern used by
 * [com.xemmy.mmbank.domain.ai.AiSelfHealingRuleGenerator].
 */
data class MerchantCategoryMapping(
    val id: Long = 0,
    /**
     * Case-insensitively compared against the whole merchant string when
     * [isExactMatch] is true; otherwise treated as a case-insensitive regex
     * tested against it.
     */
    val merchantPattern: String,
    val categoryId: Long?,
    val isExactMatch: Boolean = true,
    val pending: Boolean = false,
    val suggestedName: String? = null,
    val suggestedIcon: String? = null,
    val suggestedColorHex: String? = null,
)
