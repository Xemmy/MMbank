package com.xemmy.mmbank.domain.parser

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDateExtractorTest {

    private val utc = ZoneId.of("UTC")

    /** A fixed "now" so plausibility checks are deterministic: 2026-01-15T12:00 UTC. */
    private val now = LocalDateTime.of(2026, 1, 15, 12, 0).atZone(utc).toInstant().toEpochMilli()

    private fun millis(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0) =
        LocalDateTime.of(year, month, day, hour, minute, second).atZone(utc).toInstant().toEpochMilli()

    private fun extract(text: String) = MessageDateExtractor.extract(text, now = now, zone = utc)

    // --- numeric dates ---

    @Test
    fun `slash date is read day-first`() {
        val result = extract("AED 25.00 spent at Costa on 12/06/2025")
        assertEquals(millis(2025, 6, 12), result?.epochMillis)
        assertFalse(result!!.hasTimeOfDay)
    }

    @Test
    fun `dashes and dots work like slashes`() {
        assertEquals(millis(2025, 6, 12), extract("purchase on 12-06-2025")?.epochMillis)
        assertEquals(millis(2025, 6, 12), extract("purchase on 12.06.2025")?.epochMillis)
    }

    @Test
    fun `a leading four-digit component reads as an ISO date`() {
        assertEquals(millis(2025, 6, 12), extract("posted 2025-06-12")?.epochMillis)
    }

    @Test
    fun `a second component over twelve forces month-first`() {
        // 06/25/2025 can only be 25 June, whichever convention the bank used.
        assertEquals(millis(2025, 6, 25), extract("posted 06/25/2025")?.epochMillis)
    }

    @Test
    fun `two-digit years are this century`() {
        assertEquals(millis(2025, 6, 12), extract("purchase on 12/06/25")?.epochMillis)
    }

    // --- named-month dates ---

    @Test
    fun `named months are read, spelled short or long`() {
        assertEquals(millis(2025, 6, 12), extract("value date 12 Jun 2025")?.epochMillis)
        assertEquals(millis(2025, 6, 12), extract("value date 12-JUNE-2025")?.epochMillis)
    }

    // --- times ---

    @Test
    fun `a clock time in the message is used and flagged`() {
        val result = extract("AED 25.00 spent at 14:23 on 12/06/2025")
        assertEquals(millis(2025, 6, 12, 14, 23), result?.epochMillis)
        assertTrue(result!!.hasTimeOfDay)
    }

    @Test
    fun `seconds are kept when present`() {
        assertEquals(millis(2025, 6, 12, 14, 23, 45), extract("12/06/2025 14:23:45")?.epochMillis)
    }

    @Test
    fun `pm and am are applied`() {
        assertEquals(millis(2025, 6, 12, 14, 5), extract("12/06/2025 2:05 PM")?.epochMillis)
        assertEquals(millis(2025, 6, 12, 0, 5), extract("12/06/2025 12:05 AM")?.epochMillis)
        assertEquals(millis(2025, 6, 12, 12, 5), extract("12/06/2025 12:05 PM")?.epochMillis)
    }

    @Test
    fun `a date with no time lands at midnight and says so`() {
        val result = extract("statement dated 12/06/2025")
        assertEquals(millis(2025, 6, 12), result?.epochMillis)
        assertFalse(result!!.hasTimeOfDay)
    }

    // --- the conservative rejections ---

    @Test
    fun `a two-part number like a card expiry is not a date`() {
        assertNull(extract("card ending 1234 exp 12/26 was charged AED 20"))
    }

    @Test
    fun `messages with no date at all return null`() {
        assertNull(extract("AED 25.00 spent at Costa"))
        assertNull(extract(""))
    }

    @Test
    fun `future dates are rejected`() {
        assertNull(extract("scheduled for 01/01/2030"))
    }

    @Test
    fun `absurdly old dates are rejected`() {
        assertNull(extract("archived 01/01/2001"))
    }

    @Test
    fun `an impossible date is skipped rather than crashing`() {
        assertNull(extract("ref 45/45/2025"))
        assertNull(extract("ref 31/02/2025"))
    }

    @Test
    fun `a malformed candidate does not hide a real date later in the text`() {
        // 31/02 is impossible, so the scan moves on to the real value date.
        assertEquals(millis(2025, 6, 12), extract("ref 31/02/2025, value date 12/06/2025")?.epochMillis)
    }

    @Test
    fun `arabic-indic digits are read like ascii ones`() {
        assertEquals(millis(2025, 6, 12), extract("بتاريخ ١٢/٠٦/٢٠٢٥")?.epochMillis)
    }

    @Test
    fun `a merchant starting with AM is not read as a meridiem`() {
        val result = extract("Spent AED 10 on 12/06/2025 12:05 AMAZON")
        assertEquals(millis(2025, 6, 12, 12, 5), result?.epochMillis)
    }

    @Test
    fun `a real pm suffix still applies`() {
        val result = extract("Spent AED 10 on 12/06/2025 1:05 PM at Costa")
        assertEquals(millis(2025, 6, 12, 13, 5), result?.epochMillis)
    }
}
