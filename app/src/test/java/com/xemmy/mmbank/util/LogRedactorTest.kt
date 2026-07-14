package com.xemmy.mmbank.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRedactorTest {

    @Test
    fun `spaced card number is masked to last 4 digits`() {
        val result = LogRedactor.redactSensitiveData("Card 4111 2222 3333 4444 charged AED 50")
        assertEquals("Card **** **** **** 4444 charged AED 50", result)
    }

    @Test
    fun `dashed card number is masked to last 4 digits`() {
        val result = LogRedactor.redactSensitiveData("Card 4111-2222-3333-4444 charged AED 50")
        assertEquals("Card **** **** **** 4444 charged AED 50", result)
    }

    @Test
    fun `non-spaced 16-digit card number is masked`() {
        val result = LogRedactor.redactSensitiveData("PAN 4111222233334444 approved")
        assertEquals("PAN **** **** **** 4444 approved", result)
    }

    @Test
    fun `long bank account number is masked preserving last 4 digits`() {
        val result = LogRedactor.redactSensitiveData("Account 1234567890123 credited")
        assertEquals("Account *********0123 credited", result)
    }

    @Test
    fun `control string with no sensitive data passes through unchanged`() {
        val message = "Payment of AED 250.00 at CARREFOUR was made from your account *617"
        assertEquals(message, LogRedactor.redactSensitiveData(message))
    }
}
