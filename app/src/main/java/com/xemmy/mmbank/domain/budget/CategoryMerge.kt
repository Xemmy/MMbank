package com.xemmy.mmbank.domain.budget

import com.xemmy.mmbank.domain.model.Category
import java.math.BigDecimal

/** What merging one category into another changes; pure so every rule is testable on the JVM. */
data class CategoryMergePlan(
    val fromId: Long,
    val intoId: Long,
    /** Direct children of [fromId] that move under [intoId]. */
    val childrenToMove: List<Long>,
    /** The kept category's parent after the merge: cleared when it was a child of the one being merged away. */
    val newParentOfInto: Long?,
    /** Cap the kept category ends with: its own, else the merged one's. */
    val newCap: BigDecimal?,
)

object CategoryMerge {

    /** Null when the merge is not allowed (same category, or either one is missing). */
    fun plan(categories: List<Category>, fromId: Long, intoId: Long): CategoryMergePlan? {
        if (fromId == intoId) return null
        val from = categories.firstOrNull { it.id == fromId } ?: return null
        val into = categories.firstOrNull { it.id == intoId } ?: return null
        val children = categories.filter { it.parentCategoryId == fromId && it.id != intoId }.map { it.id }
        // Keeping a child of the merged-away category must not leave it pointing at a deleted parent.
        val parent = if (into.parentCategoryId == fromId) from.parentCategoryId else into.parentCategoryId
        return CategoryMergePlan(
            fromId = fromId,
            intoId = intoId,
            childrenToMove = children,
            newParentOfInto = parent,
            newCap = into.monthlyCap?.takeIf { it.signum() > 0 } ?: from.monthlyCap?.takeIf { it.signum() > 0 },
        )
    }

    /** The ids in their new order after moving [id] one step up (-1) or down (+1); unchanged at the ends. */
    fun move(orderedIds: List<Long>, id: Long, direction: Int): List<Long> =
        com.xemmy.mmbank.domain.ReorderList.move(orderedIds, id, direction)

    /**
     * [categories] with the side that fits the row first: income categories for money coming in,
     * expense categories otherwise. Order inside each side is kept, and nothing is hidden.
     */
    fun sideFirst(categories: List<Category>, income: Boolean): List<Category> {
        val first = if (income) com.xemmy.mmbank.domain.model.CategoryFlow.INCOME else com.xemmy.mmbank.domain.model.CategoryFlow.EXPENSE
        return categories.sortedBy { if (it.flow == first) 0 else 1 }
    }
}
