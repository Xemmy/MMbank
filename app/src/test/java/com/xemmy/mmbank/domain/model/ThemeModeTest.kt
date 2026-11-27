package com.xemmy.mmbank.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `resolve follows the system only in SYSTEM mode`() {
        assertTrue(ThemeMode.DARK.resolve(systemDark = false))
        assertTrue(ThemeMode.DARK.resolve(systemDark = true))
        assertFalse(ThemeMode.LIGHT.resolve(systemDark = true))
        assertFalse(ThemeMode.LIGHT.resolve(systemDark = false))
        assertTrue(ThemeMode.SYSTEM.resolve(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.resolve(systemDark = false))
    }

    @Test
    fun `stored value round-trips and unknown values fall back to DARK`() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromStored(it.name)) }
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored("PURPLE"))
    }
}
