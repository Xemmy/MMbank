package com.xemmy.mmbank.domain.budget

import com.xemmy.mmbank.domain.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryHierarchyTest {

    private fun category(id: Long, name: String, parentCategoryId: Long? = null) =
        Category(id = id, name = name, icon = "🏷️", colorHex = "#9E9E9E", parentCategoryId = parentCategoryId)

    @Test
    fun `empty input yields empty tree`() {
        assertTrue(CategoryHierarchy.buildTree(emptyList()).isEmpty())
    }

    @Test
    fun `top-level categories are sorted by name`() {
        val categories = listOf(category(1, "Zebra"), category(2, "Apple"), category(3, "Mango"))
        val tree = CategoryHierarchy.buildTree(categories)
        assertEquals(listOf("Apple", "Mango", "Zebra"), tree.map { it.category.name })
    }

    @Test
    fun `children are grouped under their parent and sorted by name`() {
        val categories = listOf(
            category(1, "Food"),
            category(2, "Dining", parentCategoryId = 1),
            category(3, "Bakery", parentCategoryId = 1),
        )
        val tree = CategoryHierarchy.buildTree(categories)
        assertEquals(1, tree.size)
        assertEquals("Food", tree.single().category.name)
        assertEquals(listOf("Bakery", "Dining"), tree.single().children.map { it.name })
    }

    @Test
    fun `a category whose parent id doesn't resolve is treated as top-level`() {
        val categories = listOf(category(1, "Orphan", parentCategoryId = 999))
        val tree = CategoryHierarchy.buildTree(categories)
        assertEquals(listOf("Orphan"), tree.map { it.category.name })
        assertTrue(tree.single().children.isEmpty())
    }

    @Test
    fun `a childless top-level category has an empty children list`() {
        val tree = CategoryHierarchy.buildTree(listOf(category(1, "Solo")))
        assertTrue(tree.single().children.isEmpty())
    }

    // --- descendantIds: what a delete has to warn about ---

    @Test
    fun `descendantIds returns direct children`() {
        val categories = listOf(
            category(1, "Food"),
            category(2, "Dining", parentCategoryId = 1),
            category(3, "Bakery", parentCategoryId = 1),
        )
        assertEquals(setOf(2L, 3L), CategoryHierarchy.descendantIds(categories, 1))
    }

    @Test
    fun `descendantIds reaches grandchildren, not just the level below`() {
        val categories = listOf(
            category(1, "Food"),
            category(2, "Dining", parentCategoryId = 1),
            category(3, "Sushi", parentCategoryId = 2),
        )
        assertEquals(setOf(2L, 3L), CategoryHierarchy.descendantIds(categories, 1))
    }

    @Test
    fun `descendantIds excludes the root and unrelated branches`() {
        val categories = listOf(
            category(1, "Food"),
            category(2, "Dining", parentCategoryId = 1),
            category(3, "Travel"),
            category(4, "Flights", parentCategoryId = 3),
        )
        assertEquals(setOf(2L), CategoryHierarchy.descendantIds(categories, 1))
    }

    @Test
    fun `descendantIds is empty for a leaf and for an unknown id`() {
        val categories = listOf(category(1, "Food"), category(2, "Dining", parentCategoryId = 1))
        assertTrue(CategoryHierarchy.descendantIds(categories, 2).isEmpty())
        assertTrue(CategoryHierarchy.descendantIds(categories, 999).isEmpty())
        assertTrue(CategoryHierarchy.descendantIds(emptyList(), 1).isEmpty())
    }

    @Test
    fun `descendantIds terminates on a parent cycle instead of hanging`() {
        // Corrupt data (a bad restore, a hand-edited DB) must not spin the UI thread.
        val categories = listOf(
            category(1, "A", parentCategoryId = 2),
            category(2, "B", parentCategoryId = 1),
        )
        assertEquals(setOf(2L), CategoryHierarchy.descendantIds(categories, 1))
    }

    @Test
    fun `descendantIds terminates on a self-parented category`() {
        val categories = listOf(category(1, "Loop", parentCategoryId = 1))
        assertTrue(CategoryHierarchy.descendantIds(categories, 1).isEmpty())
    }
}
