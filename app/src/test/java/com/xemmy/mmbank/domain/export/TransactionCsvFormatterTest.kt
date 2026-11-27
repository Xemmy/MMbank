package com.xemmy.mmbank.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionCsvFormatterTest {

    private fun lines(csv: String) = csv.removePrefix(TransactionCsvFormatter.BOM).split("\r\n").filter { it.isNotEmpty() }

    @Test fun startsWithBomAndHeaderAndUsesCrlf() {
        val csv = TransactionCsvFormatter.format(emptyList(), UTC)
        assertTrue(csv.startsWith("﻿Date,Type,Direction,Amount,Currency,Amount in base currency,"))
        assertTrue(csv.endsWith("\r\n"))
        assertEquals(1, lines(csv).size)
    }

    @Test fun oneRowHasTheSameCellCountAsTheHeader() {
        val csv = TransactionCsvFormatter.format(listOf(testRow()), UTC)
        val row = lines(csv)[1]
        assertEquals(TransactionCsvFormatter.HEADER.size, row.split(",").size)
    }

    @Test fun rowShowsDateSignedAmountAndNames() {
        val row = lines(TransactionCsvFormatter.format(listOf(testRow(base = "-46.12")), UTC))[1]
        assertTrue(row, row.startsWith("2026-11-20 09:05,Spent,DEBIT,-46.12,AED,-46.12,Carrefour,"))
        assertTrue(row, row.endsWith(",1"))
    }

    @Test fun dateUsesTheGivenZone() {
        val dubai = java.time.ZoneId.of("Asia/Dubai")
        val row = lines(TransactionCsvFormatter.format(listOf(testRow()), dubai))[1]
        assertTrue(row, row.startsWith("2026-11-20 13:05,"))
    }

    @Test fun quotesCommasQuotesAndLineBreaks() {
        assertEquals("\"a,b\"", TransactionCsvFormatter.quote("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", TransactionCsvFormatter.quote("say \"hi\""))
        assertEquals("\"line1\nline2\"", TransactionCsvFormatter.quote("line1\nline2"))
        assertEquals("plain", TransactionCsvFormatter.quote("plain"))
    }

    @Test fun aMultiLineNoteStaysInOneCell() {
        val csv = TransactionCsvFormatter.format(listOf(testRow(note = "a\nb, \"c\"")), UTC)
        assertTrue(csv.contains("\"a\nb, \"\"c\"\"\""))
    }

    @Test fun formulaLookingTextIsDefused() {
        assertEquals("'=SUM(A1)", TransactionCsvFormatter.guard("=SUM(A1)"))
        assertEquals("'+971", TransactionCsvFormatter.guard("+971"))
        assertEquals("'-x", TransactionCsvFormatter.guard("-x"))
        assertEquals("'@cmd", TransactionCsvFormatter.guard("@cmd"))
        assertEquals("Carrefour", TransactionCsvFormatter.guard("Carrefour"))
        assertEquals("", TransactionCsvFormatter.guard(""))
    }

    @Test fun merchantIsGuardedButTheNegativeAmountIsNot() {
        val row = lines(TransactionCsvFormatter.format(listOf(testRow(amount = "-5.00", merchant = "=HYPERLINK(1)")), UTC))[1]
        assertTrue(row, row.contains(",-5.00,"))
        assertTrue(row, row.contains(",'=HYPERLINK(1),"))
    }

    @Test fun tagsAreJoinedWithSemicolons() {
        val row = lines(TransactionCsvFormatter.format(listOf(testRow(tags = listOf("Holiday", "Tax"))), UTC))[1]
        assertTrue(row, row.contains(",Holiday; Tax,"))
    }

    @Test fun arabicTextPassesThroughUntouched() {
        val csv = TransactionCsvFormatter.format(listOf(testRow(merchant = "سوبرماركت")), UTC)
        assertTrue(csv.contains(",سوبرماركت,"))
    }

    @Test fun orderIsKept() {
        val csv = TransactionCsvFormatter.format(listOf(testRow(id = 9), testRow(id = 3)), UTC)
        val ids = lines(csv).drop(1).map { it.substringAfterLast(",") }
        assertEquals(listOf("9", "3"), ids)
    }
}
