package com.xemmy.mmbank.domain.budget

import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.CategoryFlow
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryMergeTest {
    private fun c(id: Long, parent: Long? = null, cap: String? = null) =
        Category(id = id, name = "C$id", icon = "x", colorHex = "#000000", parentCategoryId = parent, monthlyCap = cap?.let(::BigDecimal))

    @Test
    fun `merging into itself or a missing category is refused`() {
        val all = listOf(c(1), c(2))
        assertNull(CategoryMerge.plan(all, 1, 1))
        assertNull(CategoryMerge.plan(all, 1, 9))
        assertNull(CategoryMerge.plan(all, 9, 1))
    }

    @Test
    fun `children move to the kept category`() {
        val plan = CategoryMerge.plan(listOf(c(1), c(2), c(3, parent = 1), c(4, parent = 1)), 1, 2)!!
        assertEquals(listOf(3L, 4L), plan.childrenToMove)
        assertNull(plan.newParentOfInto)
    }

    @Test
    fun `the kept category keeps its own cap, else takes the other one`() {
        assertEquals(0, BigDecimal("300").compareTo(CategoryMerge.plan(listOf(c(1, cap = "500"), c(2, cap = "300")), 1, 2)!!.newCap))
        assertEquals(0, BigDecimal("500").compareTo(CategoryMerge.plan(listOf(c(1, cap = "500"), c(2)), 1, 2)!!.newCap))
        assertNull(CategoryMerge.plan(listOf(c(1), c(2)), 1, 2)!!.newCap)
    }

    @Test
    fun `merging a parent into its own child lifts the child to the parent's level`() {
        val all = listOf(c(1, parent = 9), c(2, parent = 1), c(9))
        val plan = CategoryMerge.plan(all, 1, 2)!!
        assertEquals(9L, plan.newParentOfInto)
        assertEquals(emptyList<Long>(), plan.childrenToMove)
    }

    @Test
    fun `moving up and down swaps neighbours and stops at the ends`() {
        val ids = listOf(1L, 2L, 3L)
        assertEquals(listOf(2L, 1L, 3L), CategoryMerge.move(ids, 2, -1))
        assertEquals(listOf(1L, 3L, 2L), CategoryMerge.move(ids, 2, 1))
        assertEquals(ids, CategoryMerge.move(ids, 1, -1))
        assertEquals(ids, CategoryMerge.move(ids, 3, 1))
        assertEquals(ids, CategoryMerge.move(ids, 9, 1))
    }

    @Test
    fun `flow is read from storage with an expense fallback`() {
        assertEquals(CategoryFlow.INCOME, CategoryFlow.fromStored("INCOME"))
        assertEquals(CategoryFlow.EXPENSE, CategoryFlow.fromStored("nonsense"))
        assertEquals(CategoryFlow.EXPENSE, CategoryFlow.fromStored(null))
    }
}
