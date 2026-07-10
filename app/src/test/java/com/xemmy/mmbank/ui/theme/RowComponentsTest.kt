package com.xemmy.mmbank.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** avatarInitials feeds the row avatar tile (spec 5.3) — pure string logic. */
class RowComponentsTest {

    @Test
    fun `two-word name takes the first letter of each word`() {
        assertEquals("CM", avatarInitials("Carrefour Market"))
    }

    @Test
    fun `single word takes its first two letters`() {
        assertEquals("TA", avatarInitials("tabby"))
    }

    @Test
    fun `extra whitespace and many words still use only the first two`() {
        assertEquals("BM", avatarInitials("  Binance   Mastercard Card  "))
    }

    @Test
    fun `blank name falls back to a placeholder`() {
        assertEquals("?", avatarInitials(""))
        assertEquals("?", avatarInitials("   "))
    }
}
