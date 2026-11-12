package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.IngestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSenderStatsTest {

    private fun entry(channel: String, sender: String?, outcome: String) =
        CaptureLogFormatter.line(channel, sender, null, "Spent AED 10 at X", outcome, nowMillis = 1_700_000_000_000L)

    private fun log(vararg entries: String) = entries.joinToString("")

    @Test
    fun `counts each outcome per sender`() {
        val stats = CaptureSenderStats.fromLog(
            log(
                entry("SMS", "ADCB", "SAVED tx#1"),
                entry("SMS", "ADCB", "SAVED tx#2"),
                entry("SMS", "ADCB", "DUPLICATE of tx#1"),
                entry("SMS", "ADCB", "NO MATCH (no rule parsed it)"),
                entry("SMS", "ENBD", "IGNORED (muted template)"),
                entry("SMS", "ENBD", "ERROR IllegalStateException"),
            ),
        )
        val adcb = stats.single { it.sender == "ADCB" }
        assertEquals(listOf(2, 1, 0, 1, 0), listOf(adcb.saved, adcb.duplicate, adcb.ignored, adcb.unmatched, adcb.errors))
        assertEquals(4, adcb.total)
        val enbd = stats.single { it.sender == "ENBD" }
        assertEquals(listOf(0, 0, 1, 0, 1), listOf(enbd.saved, enbd.duplicate, enbd.ignored, enbd.unmatched, enbd.errors))
    }

    @Test
    fun `an unmatched reason after NO MATCH still counts as unmatched`() {
        val stats = CaptureSenderStats.fromLog(
            entry("PUSH", "com.bank.app", CaptureLogFormatter.outcome(IngestResult.NoMatch, true, "no amount next to a currency was found")!!),
        )
        assertEquals(1, stats.single().unmatched)
    }

    @Test
    fun `senders with the most unmatched messages come first`() {
        val stats = CaptureSenderStats.fromLog(
            log(
                entry("SMS", "BIG", "SAVED tx#1"), entry("SMS", "BIG", "SAVED tx#2"), entry("SMS", "BIG", "SAVED tx#3"),
                entry("SMS", "BAD", "NO MATCH (no rule parsed it)"), entry("SMS", "BAD", "NO MATCH (no rule parsed it)"),
                entry("SMS", "OK", "SAVED tx#4"),
            ),
        )
        assertEquals(listOf("BAD", "BIG", "OK"), stats.map { it.sender })
    }

    @Test
    fun `a missing sender is grouped under one label and message snippets are ignored`() {
        val stats = CaptureSenderStats.fromLog(entry("SMS", null, "SAVED tx#1") + entry("SMS", "", "SAVED tx#2"))
        assertEquals(CaptureSenderStats.UNKNOWN_SENDER, stats.single().sender)
        assertEquals(2, stats.single().saved)
    }

    @Test
    fun `unmatched share is a fraction of that sender's messages`() {
        val stat = CaptureSenderStats.fromLog(
            log(entry("SMS", "X", "SAVED tx#1"), entry("SMS", "X", "NO MATCH (no rule parsed it)")),
        ).single()
        assertEquals(0.5, stat.unmatchedShare, 1e-9)
    }

    @Test
    fun `describe lists only the non-zero counts`() {
        val stat = CaptureSenderStats.SenderStat("ADCB", saved = 212, duplicate = 0, ignored = 0, unmatched = 3, errors = 0)
        assertEquals("ADCB — 212 saved · 3 unmatched", CaptureSenderStats.describe(stat))
    }

    @Test
    fun `empty or unrelated text gives nothing`() {
        assertTrue(CaptureSenderStats.fromLog("").isEmpty())
        assertTrue(CaptureSenderStats.fromLog("just some words\n    \"snippet\"\n").isEmpty())
    }
}
