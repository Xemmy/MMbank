package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.IngestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLogFormatterTest {

    @Test fun outcomeLabelsEachResult() {
        assertEquals("SAVED tx#5", CaptureLogFormatter.outcome(IngestResult.Inserted(5), true))
        assertEquals("DUPLICATE of tx#7", CaptureLogFormatter.outcome(IngestResult.Duplicate(7), true))
        assertTrue(CaptureLogFormatter.outcome(IngestResult.Ignored, true)!!.startsWith("IGNORED"))
        assertTrue(CaptureLogFormatter.outcome(IngestResult.NoMatch, true)!!.startsWith("NO MATCH"))
    }

    @Test fun digitFreeNoMatchIsNotLogged() {
        assertNull(CaptureLogFormatter.outcome(IngestResult.NoMatch, false))
    }

    @Test fun lineRedactsCardNumbersAndTruncates() {
        val body = "Card 1234 5678 9012 3456 spent " + "x".repeat(200)
        val line = CaptureLogFormatter.line("SMS", "BANK", null, body, "NO MATCH", 0L)
        assertTrue(line.contains("**** **** **** 3456"))
        assertFalse(line.contains("1234 5678"))
        assertTrue(line.contains("…"))
        assertTrue(line.contains("SMS BANK -> NO MATCH"))
    }

    @Test fun lineIncludesTitleAndCollapsesWhitespace() {
        val line = CaptureLogFormatter.line("PUSH", "com.bank", "Alert", "a\n\n  b", "SAVED tx#1", 0L)
        assertTrue(line.contains("\"Alert | a b\""))
    }

    @Test fun prependPutsNewestFirstAndTrimsOldestAtLineBoundary() {
        val e1 = "[01] a\n    \"1\"\n"
        val e2 = "[02] b\n    \"2\"\n"
        val e3 = "[03] c\n    \"3\"\n"
        assertEquals(e2 + e1, CaptureLogFormatter.prepend(e1, e2))
        val trimmed = CaptureLogFormatter.prepend(e2 + e1, e3, e3.length + e2.length + 2)
        assertTrue(trimmed.startsWith(e3))
        assertFalse(trimmed.contains("[01]"))
        assertTrue(trimmed.endsWith("\n"))
    }

    @Test fun looksFinancialNeedsANumberNextToACurrency() {
        assertTrue(CaptureLogFormatter.looksFinancial("Spent AED70.00 at shop"))
        assertTrue(CaptureLogFormatter.looksFinancial("تم خصم 50 درهم"))
        assertFalse(CaptureLogFormatter.looksFinancial("Your code is 482913"))
        assertFalse(CaptureLogFormatter.looksFinancial("See you at 7"))
    }

    @Test fun lineMasksShortCodesButKeepsAmounts() {
        val line = CaptureLogFormatter.line("PUSH", "app", null, "Code 482913 for AED 5000 and 1,250.50", "NO MATCH", 0L)
        assertFalse(line.contains("482913"))
        assertTrue(line.contains("******"))
        assertTrue(line.contains("AED 5000"))
        assertTrue(line.contains("1,250.50"))
    }
}
