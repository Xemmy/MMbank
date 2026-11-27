package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.parser.UnmatchedExplainer.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnmatchedExplainerTest {

    private fun explain(body: String, title: String? = null) = UnmatchedExplainer.explain(title, body)

    @Test
    fun `blank text has nothing to explain`() {
        assertNull(explain("   "))
        assertNull(explain("", title = " "))
    }

    @Test
    fun `declined and one-time-code messages are explained as skipped on purpose`() {
        assertEquals(Reason.NOT_A_PAYMENT, explain("Your card payment of AED 250.00 was declined")!!.reason)
        assertEquals(Reason.NOT_A_PAYMENT, explain("OTP 482913 to approve AED 99.00 at NOON. Do not share.")!!.reason)
    }

    @Test
    fun `an unknown currency code is named`() {
        val e = explain("Spent THB 1,500.00 at Phuket Cafe")!!
        assertEquals(Reason.UNKNOWN_CURRENCY, e.reason)
        assertTrue(e.headline, e.headline.contains("THB"))
    }

    @Test
    fun `a known currency is never reported as unknown`() {
        assertEquals(Reason.NO_RULE_MATCHES, explain("Spent AED 1,500.00 somewhere odd")!!.reason)
    }

    @Test
    fun `no amount next to a currency is reported`() {
        assertEquals(Reason.NO_AMOUNT, explain("Your statement is ready, balance 5000")!!.reason)
        assertEquals(Reason.NO_AMOUNT, explain("Welcome to your new card")!!.reason)
    }

    @Test
    fun `a clear amount with no matching rule says so and shows the amount`() {
        val e = explain("Hello. AED 120.50 Noon order confirmation")!!
        assertEquals(Reason.NO_RULE_MATCHES, e.reason)
        assertTrue(e.headline, e.headline.contains("AED 120.50"))
        assertTrue(e.headline, e.headline.contains("create a rule"))
    }

    @Test
    fun `the direction cue is mentioned when the wording gives one`() {
        assertTrue(explain("You have paid AED 40.00 for parking")!!.headline.contains("money going out"))
        assertTrue(explain("You have received AED 40.00 from a friend")!!.headline.contains("money coming in"))
        assertTrue(!explain("AED 40.00 parking")!!.headline.contains("money"))
    }

    @Test
    fun `the title is part of the text that is read`() {
        assertEquals(Reason.NO_RULE_MATCHES, explain(body = "Order confirmed", title = "AED 75.00 Noon")!!.reason)
    }

    @Test
    fun `the capture log line carries the reason, redacted`() {
        val line = CaptureLogFormatter.outcome(
            com.xemmy.mmbank.domain.model.IngestResult.NoMatch,
            looksFinancial = true,
            why = "found \"ABC 4111111111111111\" but ABC is not a currency",
        )!!
        assertTrue(line, line.startsWith("NO MATCH (no rule parsed it): found"))
        assertTrue(line, !line.contains("4111111111111111"))
    }

    @Test
    fun `a no-match without a reason or that is not financial keeps the old wording`() {
        val ingest = com.xemmy.mmbank.domain.model.IngestResult.NoMatch
        assertEquals("NO MATCH (no rule parsed it)", CaptureLogFormatter.outcome(ingest, true))
        assertEquals("NO MATCH (no rule parsed it)", CaptureLogFormatter.outcome(ingest, true, why = "  "))
        assertNull(CaptureLogFormatter.outcome(ingest, false, why = "anything"))
    }
}
