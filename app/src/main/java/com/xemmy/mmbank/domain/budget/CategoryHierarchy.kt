package com.xemmy.mmbank.domain.budget

import com.xemmy.mmbank.domain.model.Category

/** One top-level category plus its children, both name-sorted. */
data class CategoryTreeNode(
    val category: Category,
    val children: List<Category>,
)

/**
 * Groups a flat category list into a two-level tree for hierarchy-first
 * rendering (see [com.xemmy.mmbank.ui.screens.budgets.CategoriesScreen]).
 * Pure and top-level for JVM testing.
 */
object CategoryHierarchy {

    /**
     * A category whose `parentCategoryId` doesn't resolve to another category
     * in this same list (its parent was deleted, or it's simply unset) is
     * treated as top-level rather than silently dropped.
     */
    fun buildTree(categories: List<Category>): List<CategoryTreeNode> {
        val idSet = categories.mapTo(mutableSetOf()) { it.id }
        val childrenByParent = categories
            .filter { it.parentCategoryId != null && it.parentCategoryId in idSet }
            .groupBy { it.parentCategoryId }
        val topLevel = categories.filter { it.parentCategoryId == null || it.parentCategoryId !in idSet }

        return topLevel
            .sortedBy { it.name.lowercase() }
            .map { top ->
                CategoryTreeNode(
                    category = top,
                    children = childrenByParent[top.id].orEmpty().sortedBy { it.name.lowercase() },
                )
            }
    }
}
