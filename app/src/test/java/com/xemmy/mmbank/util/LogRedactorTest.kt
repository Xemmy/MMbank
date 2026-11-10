package com.xemmy.mmbank.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // Diagnostics passes whole stack-trace entries through the redactor, so a
    // card number quoted inside an exception message must be masked even when
    // it sits mid-way through a multiline blob.
    @Test
    fun `card number embedded in a multiline stack trace is masked`() {
        val entry = """
            [2026-07-14 10:00:00] TransactionsViewModel.update
            java.lang.NumberFormatException: For input string: "4111222233334444"
                at java.lang.Integer.parseInt(Integer.java:652)
        """.trimIndent()
        val redacted = LogRedactor.redactSensitiveData(entry)
        assertEquals(
            """
            [2026-07-14 10:00:00] TransactionsViewModel.update
            java.lang.NumberFormatException: For input string: "**** **** **** 4444"
                at java.lang.Integer.parseInt(Integer.java:652)
            """.trimIndent(),
            redacted,
        )
    }

    @Test
    fun `arabic-indic card number is masked`() {
        val out = LogRedactor.redactSensitiveData("البطاقة ١٢٣٤ ٥٦٧٨ ٩٠١٢ ٣٤٥٦ تم")
        assertTrue(out.contains("**** **** **** 3456"))
        assertFalse(out.contains("١٢٣٤"))
    }

    @Test
    fun `unspaced iban is masked keeping the last four`() {
        val out = LogRedactor.redactSensitiveData("to AE070331234567890123456 today")
        assertFalse(out.contains("AE0703312"))
        assertTrue(out.endsWith("3456 today"))
    }
}

