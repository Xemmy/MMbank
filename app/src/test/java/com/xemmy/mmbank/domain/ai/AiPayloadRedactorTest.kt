package com.xemmy.mmbank.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPayloadRedactorTest {

    @Test
    fun `card numbers keep only their last four digits`() {
        val out = AiPayloadRedactor.redact("Spent AED 50 on card 4111 1111 1111 1234 at NOON")
        assertTrue(out, out.contains("**** **** **** 1234"))
        assertFalse(out, out.contains("4111"))
        assertTrue(out, out.contains("AED 50"))
    }

    @Test
    fun `long account numbers and IBANs are masked but keep the ending`() {
        val out = AiPayloadRedactor.redact("Credited to AE070331234567890123456 and a/c 123456789012345")
        assertFalse(out, out.contains("0331234567"))
        assertFalse(out, out.contains("12345678901"))
        assertTrue(out, out.contains("3456"))
    }

    @Test
    fun `a one-time code after its keyword is masked and the amount survives`() {
        val out = AiPayloadRedactor.redact("Your OTP is 482913 for AED 250.00 purchase at NOON")
        assertFalse(out, out.contains("482913"))
        assertTrue(out, out.contains("OTP is ${AiPayloadRedactor.CODE_MASK}"))
        assertTrue(out, out.contains("AED 250.00"))
    }

    @Test
    fun `a one-time code before its keyword is masked`() {
        val out = AiPayloadRedactor.redact("482913 is your verification code for AED 99")
        assertFalse(out, out.contains("482913"))
        assertTrue(out, out.contains("AED 99"))
    }

    @Test
    fun `other security codes are masked`() {
        assertFalse(AiPayloadRedactor.redact("Your PIN 4321 was changed").contains("4321"))
        assertFalse(AiPayloadRedactor.redact("CVV: 987 used").contains("987"))
        assertFalse(AiPayloadRedactor.redact("Verification code: 1234").contains("1234"))
    }

    @Test
    fun `emails and international phone numbers are masked`() {
        val out = AiPayloadRedactor.redact("Receipt sent to ahmed.k@example.com, call +971 50 123 4567 for help")
        assertFalse(out, out.contains("example.com"))
        assertFalse(out, out.contains("123 4567"))
        assertTrue(out, out.contains("[email]") && out.contains("[phone]"))
    }

    @Test
    fun `a message with nothing sensitive is returned unchanged`() {
        val message = "Spent AED 1,250.50 at CARREFOUR on 12/11/2026 with card ending 1234. Avail bal AED 3,400.00"
        assertEquals(message, AiPayloadRedactor.redact(message))
    }

    @Test
    fun `amounts that look like digit runs are not mistaken for phone numbers or codes`() {
        val message = "Debited USD 1234567.89 for order 2026 at AMAZON"
        assertEquals(message, AiPayloadRedactor.redact(message))
    }

    @Test
    fun `redaction is idempotent`() {
        val once = AiPayloadRedactor.redact("OTP 123456 card 4111111111111111 mail a@b.co +971501234567")
        assertEquals(once, AiPayloadRedactor.redact(once))
    }

    @Test
    fun `the prompt's own instructions pass through untouched`() {
        val prompt = AiTransactionPrompt.build("", listOf("Food", "Groceries"))
        assertEquals(prompt, AiPayloadRedactor.redact(prompt))
    }

    @Test
    fun `an amount after a code keyword is never mistaken for the code`() {
        val message = "482913 is your verification code for AED 250 at NOON"
        val out = AiPayloadRedactor.redact(message)
        assertFalse(out, out.contains("482913"))
        assertTrue(out, out.contains("AED 250"))
    }

    @Test
    fun `a code that follows the transaction details is masked and the amount kept`() {
        val out = AiPayloadRedactor.redact("OTP for transaction of AED 250 at NOON is 123456. Do not share.")
        assertFalse(out, out.contains("123456"))
        assertTrue(out, out.contains("AED 250"))
    }
}
