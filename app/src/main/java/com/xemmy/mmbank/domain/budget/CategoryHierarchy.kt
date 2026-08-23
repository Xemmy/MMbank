package com.xemmy.mmbank.domain.budget

import com.xemmy.mmbank.domain.model.Category

/** One top-level category plus its children, both name-sorted. */
data class CategoryTreeNode(
    val category: Category,
    val children: List<Category>,
)

/**
 * Groups a flat category list into a two-level tree for hierarchy-first
 * rendering (see [com.xemmy.mmbank.ui.screens.budgets.CategoriesTabContent]).
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

    /**
     * Every category beneath [rootId], at any depth, excluding [rootId] itself.
     * Empty for a leaf or an id that isn't in [categories].
     *
     * Used by the delete flow to ask "does anything under here hold
     * transactions?" -- a parent with no direct transactions can still have
     * children full of them, and deleting it without saying so is the silent
     * loss the reassign prompt exists to prevent.
     *
     * Walks defensively rather than trusting the data: a `parentCategoryId`
     * cycle (corrupt rows, a bad restore) would otherwise hang the UI thread,
     * so a visited set bounds the traversal the same way
     * [CategoryBudgetCalculator.rollUpSpend]'s `path` guard does.
     */
    fun descendantIds(categories: List<Category>, rootId: Long): Set<Long> {
        val childrenByParent = categories
            .filter { it.parentCategoryId != null }
            .groupBy { it.parentCategoryId }

        val found = mutableSetOf<Long>()
        val queue = ArrayDeque(childrenByParent[rootId].orEmpty().map { it.id })
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            // Also covers a self-parented row, and re-entry back onto the root.
            if (id == rootId || !found.add(id)) continue
            childrenByParent[id].orEmpty().forEach { queue.addLast(it.id) }
        }
        return found
    }
}
