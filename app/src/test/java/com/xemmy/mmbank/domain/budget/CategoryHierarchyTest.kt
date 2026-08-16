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
}
