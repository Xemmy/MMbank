package com.xemmy.mmbank.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.xemmy.mmbank.domain.model.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `white text clears AA on every wallet gradient stop`() {
        for (type in AccountType.values()) {
            for (stop in walletGradient(type)) {
                val l = stop.luminance()
                val ratio = 1.05f / (l + 0.05f)
                assertTrue("$type $stop contrast $ratio", ratio >= 4.5f)
            }
        }
    }
}
