package com.xemmy.mmbank.ui.screens.transactions

import com.xemmy.mmbank.domain.model.Category
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryFilterLabelTest {

    private fun cat(id: Long, name: String, parent: Long? = null) =
        Category(id = id, name = name, icon = "", colorHex = "#000000", parentCategoryId = parent)

    private val categories = listOf(cat(1, "Food"), cat(5, "Coffee", parent = 1), cat(6, "Bakery", parent = 1), cat(2, "Dining"))

    @Test
    fun `one category shows its name`() {
        assertEquals("Dining", categoryFilterLabel(setOf(2), categories))
    }

    @Test
    fun `a category tree leads with the parent`() {
        assertEquals("Food + 2 more", categoryFilterLabel(setOf(5, 1, 6), categories))
    }

    @Test
    fun `categories that no longer exist still read sensibly`() {
        assertEquals("Selected categories", categoryFilterLabel(setOf(99), categories))
    }
}
