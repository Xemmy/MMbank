package com.xemmy.mmbank.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterCategoriesTest {
    @Test
    fun freshInstallSeedsEveryStarterCategoryOnce() {
        val inserts = AppDatabase.starterCategoryInserts()
        val names = inserts.map { it.second[0] as String }
        assertEquals(AppDatabase.DEFAULT_CATEGORIES.size + AppDatabase.DEFAULT_CATEGORIES_V22.size, inserts.size)
        assertEquals(names.size, names.toSet().size)
        assertTrue("Uncategorized" in names && "Income" in names && "Fuel" in names)
        assertTrue(inserts.all { it.first.startsWith("INSERT INTO categories") && it.second.size == 3 })
    }
}
