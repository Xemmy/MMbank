package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.TransactionSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiParseGateTest {

    private fun message(
        body: String,
        source: TransactionSource = TransactionSource.SMS,
        sender: String? = "ADCB",
    ) = CapturedMessage(source, sender, null, body, 0L)

    @Test
    fun `unavailable engine blocks everything`() {
        assertFalse(AiParseGate.shouldAttempt(message("Purchase of AED 46.12 at CARREFOUR"), aiAvailable = false))
    }

    @Test
    fun `money-looking unmatched message passes`() {
        assertTrue(AiParseGate.shouldAttempt(message("Purchase of AED 46.12 at CARREFOUR"), aiAvailable = true))
    }

    @Test
    fun `bare numbers without an adjacent currency are not money`() {
        assertFalse(AiParseGate.shouldAttempt(message("Your OTP code is 123456"), aiAvailable = true))
        assertFalse(AiParseGate.shouldAttempt(message("Meeting moved to 10.30 tomorrow"), aiAvailable = true))
    }

    @Test
    fun `social push notifications never reach the model`() {
        val social = message(
            "Channel: BTC just paid 500 USD level!",
            source = TransactionSource.PUSH_NOTIFICATION,
            sender = "org.telegram.messenger",
        )
        assertFalse(AiParseGate.shouldAttempt(social, aiAvailable = true))
    }

    @Test
    fun `non-social push notifications with money pass`() {
        val bankApp = message(
            "AED229.59 with Binance Mastercard ••2957",
            source = TransactionSource.PUSH_NOTIFICATION,
            sender = "com.binance.dev",
        )
        assertTrue(AiParseGate.shouldAttempt(bankApp, aiAvailable = true))
    }

    @Test
    fun `arabic-digit amounts are recognized after normalization`() {
        assertTrue(AiParseGate.shouldAttempt(message("تم خصم ٤٦٫١٢ درهم من بطاقتك"), aiAvailable = true))
    }
}
