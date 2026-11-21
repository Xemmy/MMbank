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
        assertTrue(inserts.all { it.first.startsWith("INSERT INTO categories") && it.second.size == 5 })
        // Positions are the list order, and only "Income" starts on the income side.
        assertEquals(inserts.indices.toList(), inserts.map { it.second[3] as Int })
        assertEquals(listOf("Income"), inserts.filter { it.second[4] == "INCOME" }.map { it.second[0] })
    }
}
