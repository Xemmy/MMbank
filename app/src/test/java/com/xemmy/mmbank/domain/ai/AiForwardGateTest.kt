package com.xemmy.mmbank.domain.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiForwardGateTest {

    @Test
    fun `a currency-adjacent amount in the body is plausible`() {
        assertTrue(AiForwardGate.isPlausibleTransaction(null, "AED 42.00 charged in a new format"))
    }

    @Test
    fun `a currency-adjacent amount only in the title is plausible`() {
        assertTrue(AiForwardGate.isPlausibleTransaction("Payment of AED 42.00", "See details in-app"))
    }

    @Test
    fun `a download-complete notification is not plausible`() {
        assertFalse(AiForwardGate.isPlausibleTransaction(null, "Download complete • 26.96 MB"))
    }

    @Test
    fun `a bare number with no adjacent currency is not plausible`() {
        assertFalse(AiForwardGate.isPlausibleTransaction(null, "Something with a number 42 but no known shape"))
    }

    @Test
    fun `arabic-indic digits normalize before the amount check`() {
        assertTrue(AiForwardGate.isPlausibleTransaction(null, "AED ٤٢.٠٠ spent today"))
    }

    @Test
    fun `blank body and title is not plausible`() {
        assertFalse(AiForwardGate.isPlausibleTransaction(null, ""))
        assertFalse(AiForwardGate.isPlausibleTransaction("", ""))
    }
}
