package com.xemmy.mmbank.ui.components

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryEditorFormStateTest {

    @Test
    fun `blank name is invalid regardless of cap`() {
        assertFalse(CategoryEditorFormState(name = "").isValid())
        assertFalse(CategoryEditorFormState(name = "  ").isValid())
    }

    @Test
    fun `named category with blank cap is valid`() {
        assertTrue(CategoryEditorFormState(name = "Groceries", monthlyCapText = "").isValid())
    }

    @Test
    fun `named category with numeric cap is valid`() {
        assertTrue(CategoryEditorFormState(name = "Groceries", monthlyCapText = "250.50").isValid())
    }

    @Test
    fun `named category with unparseable cap is invalid`() {
        assertFalse(CategoryEditorFormState(name = "Groceries", monthlyCapText = "12.34.56").isValid())
    }

    @Test
    fun `capValue parses a trimmed numeric string`() {
        assertEquals(BigDecimal("100"), CategoryEditorFormState(monthlyCapText = " 100 ").capValue())
    }

    @Test
    fun `capValue is null when blank`() {
        assertEquals(null, CategoryEditorFormState(monthlyCapText = "").capValue())
    }

    @Test
    fun `capValue is null when unparseable`() {
        assertEquals(null, CategoryEditorFormState(monthlyCapText = "abc").capValue())
    }
}
