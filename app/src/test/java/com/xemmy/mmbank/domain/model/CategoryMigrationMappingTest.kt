package com.xemmy.mmbank.domain.model

import com.xemmy.mmbank.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryMigrationMappingTest {

    /** Every dynamic category name a mapping can target -- what v21->v22 seeds. */
    private val seededCategoryNames: Set<String> =
        (AppDatabase.DEFAULT_CATEGORIES.map { it.first } + AppDatabase.DEFAULT_CATEGORIES_V22.map { it.first }).toSet()

    @Test
    fun `every SpendingCategory value has a mapping entry`() {
        val mapped = CategoryMigrationMapping.ENUM_TO_CATEGORY_NAME.keys
        SpendingCategory.entries.forEach { value ->
            assertTrue("missing mapping for $value", value in mapped)
        }
    }

    @Test
    fun `every mapping targets a category name that actually gets seeded`() {
        CategoryMigrationMapping.ENUM_TO_CATEGORY_NAME.values.forEach { name ->
            assertTrue("'$name' is not among the seeded default categories", name in seededCategoryNames)
        }
    }

    @Test
    fun `OTHER and UNCATEGORIZED both fold into Uncategorized`() {
        assertEquals("Uncategorized", CategoryMigrationMapping.ENUM_TO_CATEGORY_NAME.getValue(SpendingCategory.OTHER))
        assertEquals(
            "Uncategorized",
            CategoryMigrationMapping.ENUM_TO_CATEGORY_NAME.getValue(SpendingCategory.UNCATEGORIZED),
        )
    }

    @Test
    fun `no other value collides onto Uncategorized`() {
        val targetingUncategorized = CategoryMigrationMapping.ENUM_TO_CATEGORY_NAME
            .filterValues { it == "Uncategorized" }
            .keys
        assertEquals(setOf(SpendingCategory.OTHER, SpendingCategory.UNCATEGORIZED), targetingUncategorized)
    }

    @Test
    fun `Uncategorized reverses onto UNCATEGORIZED, not OTHER`() {
        assertEquals(SpendingCategory.UNCATEGORIZED, CategoryMigrationMapping.CATEGORY_NAME_TO_ENUM["Uncategorized"])
    }

    @Test
    fun `every non-OTHER SpendingCategory round-trips through its category name`() {
        SpendingCategory.entries.filter { it != SpendingCategory.OTHER }.forEach { value ->
            val name = CategoryMigrationMapping.ENUM_TO_CATEGORY_NAME.getValue(value)
            assertEquals(value, CategoryMigrationMapping.CATEGORY_NAME_TO_ENUM[name])
        }
    }

    @Test
    fun `a name with no reverse entry returns null`() {
        assertEquals(null, CategoryMigrationMapping.CATEGORY_NAME_TO_ENUM["Some Custom Category"])
    }
}
