package com.xemmy.mmbank.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class WalletCardTest {

    @Test
    fun `masks a long account number to its last 4 digits`() {
        assertEquals("•••• 1234", maskedAccountNumber("00981234"))
    }

    @Test
    fun `shorter than 4 digits is shown in full`() {
        assertEquals("•••• 617", maskedAccountNumber("617"))
    }

    @Test
    fun `null account number falls back to a placeholder mask`() {
        assertEquals("•••• ····", maskedAccountNumber(null))
    }

    @Test
    fun `blank account number falls back to a placeholder mask`() {
        assertEquals("•••• ····", maskedAccountNumber("   "))
    }

    @Test
    fun `whitespace around the number is trimmed before masking`() {
        assertEquals("•••• 9012", maskedAccountNumber("  345689012  "))
    }
}
