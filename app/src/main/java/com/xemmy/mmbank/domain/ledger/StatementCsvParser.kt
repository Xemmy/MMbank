package com.xemmy.mmbank.domain.ledger

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of a bank statement. [amount] is signed from the account's side: negative = money out. */
data class StatementLine(val date: LocalDate, val description: String, val amount: BigDecimal)

/**
 * Reads a pasted bank-statement CSV for [StatementReconciler] (backlog B.5 #3).
 * Banks all export slightly different CSVs, so this is tolerant rather than
 * strict: comma, semicolon or tab separated; quoted fields; a header row (or
 * none); one signed amount column or separate debit/credit columns; amounts as
 * `-1,234.50`, `(1,234.50)`, `1234.50 DR/CR`; dates as `2026-11-01`,
 * `01/11/2026` (day first, as UAE banks print), `01-Nov-2026`, `1 Nov 2026`.
 *
 * Lines that can't be read are reported in [Result.skipped] rather than
 * dropped silently, so the user sees what the comparison did not cover.
 * Pure and Android-free.
 */
object StatementCsvParser {

    data class Result(val lines: List<StatementLine>, val skipped: List<String>)

    private val DATE_FORMATS: List<DateTimeFormatter> = listOf(
        "yyyy-MM-dd", "yyyy/MM/dd", "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy",
        "d MMM yyyy", "dd-MMM-yyyy", "dd-MMM-yy", "d-MMM-yyyy", "dd/MM/yy", "MM/dd/yyyy",
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    private val DATE_HEADERS = listOf("date", "posting", "posted", "value date", "transaction date", "txn date")
    private val TEXT_HEADERS = listOf("description", "details", "narration", "narrative", "merchant", "particulars", "reference", "memo")
    private val AMOUNT_HEADERS = listOf("amount")
    private val DEBIT_HEADERS = listOf("debit", "withdrawal", "withdrawals", "money out", "paid out", "dr")
    private val CREDIT_HEADERS = listOf("credit", "deposit", "deposits", "money in", "paid in", "cr")

    fun parse(text: String): Result {
        val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return Result(emptyList(), emptyList())
        val delimiter = detectDelimiter(rows.first())
        val cells = rows.map { splitRow(it, delimiter) }

        // The header is the first row with at least two header-like cells and no date; rows above it
        // (a bank's title or account lines) are reported as skipped, not misread as data.
        val headerIndex = cells.indexOfFirst { row -> row.none { parseDate(it) != null } && headerCells(row) >= 2 }
        val header = cells.getOrNull(headerIndex)
        val layout = header?.let(::layoutFromHeader)
        val firstBody = if (headerIndex >= 0) headerIndex + 1 else 0

        val lines = mutableListOf<StatementLine>()
        val skipped = mutableListOf<String>()
        if (headerIndex > 0) cells.take(headerIndex).forEach { skipped += it.joinToString(delimiter.toString()) }
        for (row in cells.drop(firstBody)) {
            val line = readRow(row, layout)
            if (line != null) lines += line else skipped += row.joinToString(delimiter.toString())
        }
        return Result(lines, skipped)
    }

    private class Layout(val date: Int?, val text: Int?, val amount: Int?, val debit: Int?, val credit: Int?)

    /** How many cells of [row] name a known statement column. One- and two-letter names ("dr", "cr") must be the whole cell. */
    private fun headerCells(row: List<String>): Int = row.count { cell ->
        val c = cell.trim().lowercase()
        (DATE_HEADERS + AMOUNT_HEADERS + DEBIT_HEADERS + CREDIT_HEADERS + TEXT_HEADERS)
            .any { if (it.length <= 2) c == it else c.contains(it) }
    }

    private fun layoutFromHeader(row: List<String>): Layout {
        fun find(names: List<String>, exact: Boolean = false) = row.indexOfFirst { cell ->
            val c = cell.trim().lowercase()
            names.any { if (exact) c == it else c.contains(it) }
        }.takeIf { it >= 0 }
        return Layout(
            date = find(DATE_HEADERS),
            text = find(TEXT_HEADERS),
            // A single signed column: not a balance, and not "Debit Amount" / "Credit Amount", which are the split columns.
            amount = row.indexOfFirst { cell ->
                val c = cell.trim().lowercase()
                AMOUNT_HEADERS.any { c.contains(it) } && !c.contains("balance") &&
                    DEBIT_HEADERS.filter { it.length > 2 }.none { c.contains(it) } &&
                    CREDIT_HEADERS.filter { it.length > 2 }.none { c.contains(it) }
            }.takeIf { it >= 0 },
            // Short names ("dr", "cr") only count as a whole cell, or "description" would match "cr"... and "address" "dr".
            debit = find(DEBIT_HEADERS.filter { it.length > 2 }) ?: find(listOf("dr"), exact = true),
            credit = find(CREDIT_HEADERS.filter { it.length > 2 }) ?: find(listOf("cr"), exact = true),
        )
    }

    private fun readRow(row: List<String>, layout: Layout?): StatementLine? {
        val dateIndex = layout?.date ?: row.indexOfFirst { parseDate(it) != null }
        val date = row.getOrNull(dateIndex)?.let(::parseDate) ?: return null

        val amount: BigDecimal? = when {
            layout != null && (layout.debit != null || layout.credit != null) && layout.amount == null -> {
                val debit = layout.debit?.let { row.getOrNull(it) }?.let(::parseAmount)?.takeIf { it.signum() != 0 }
                val credit = layout.credit?.let { row.getOrNull(it) }?.let(::parseAmount)?.takeIf { it.signum() != 0 }
                when {
                    debit != null -> debit.abs().negate()
                    credit != null -> credit.abs()
                    else -> null
                }
            }
            layout?.amount != null -> row.getOrNull(layout.amount)?.let(::parseAmount)
            // No header: the first number after the date. A trailing running balance comes later, so it is never mistaken for the amount.
            else -> row.indices.firstNotNullOfOrNull { i ->
                if (i == dateIndex || parseDate(row[i]) != null) null else row[i].takeIf { it.any(Char::isDigit) }?.let(::parseAmount)
            }
        }
        if (amount == null || amount.signum() == 0) return null

        val description = layout?.text?.let { row.getOrNull(it) }
            ?: row.filterIndexed { i, c -> i != dateIndex && parseAmount(c) == null && c.isNotBlank() }.maxByOrNull { it.length }
            ?: ""
        return StatementLine(date, description.trim(), amount)
    }

    internal fun parseDate(raw: String): LocalDate? {
        val s = raw.trim().trim('"')
        if (s.length < 6 || s.none(Char::isDigit)) return null
        for (format in DATE_FORMATS) {
            runCatching { return LocalDate.parse(s.take(11).trim(), format) }
            runCatching { return LocalDate.parse(s, format) }
        }
        return null
    }

    private const val CURRENCY = "(?:AED|USD|EUR|GBP|SAR|QAR|KWD|BHD|OMR|EGP|INR|PKR|JOD|[$€£])"
    private val LEADING_CURRENCY = Regex("^$CURRENCY\\s?", RegexOption.IGNORE_CASE)
    private val TRAILING_CURRENCY = Regex("\\s?$CURRENCY$", RegexOption.IGNORE_CASE)
    private val PLAIN_NUMBER = Regex("^\\d+(\\.\\d+)?$")

    /** Signed amount, or null when [raw] isn't a number. Money out is negative. */
    internal fun parseAmount(raw: String): BigDecimal? {
        var s = raw.trim().trim('"')
        if (s.isEmpty()) return null
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1)
        }
        val upper = s.uppercase()
        if (upper.endsWith("DR")) {
            negative = true
            s = s.dropLast(2)
        } else if (upper.endsWith("CR")) {
            s = s.dropLast(2)
        }
        s = s.trim()
        if (s.startsWith("-")) {
            negative = true
            s = s.substring(1)
        } else if (s.startsWith("+")) {
            s = s.substring(1)
        }
        // Only a real currency code or symbol is dropped, never an arbitrary word:
        // "ATM 1234" is a description, not the amount 1234.
        s = s.trim().replace(LEADING_CURRENCY, "").replace(TRAILING_CURRENCY, "").replace(",", "").replace(" ", "")
        if (!PLAIN_NUMBER.matches(s)) return null
        val value = s.toBigDecimal()
        return if (negative) value.negate() else value
    }

    private fun detectDelimiter(firstRow: String): Char {
        val candidates = listOf(',', ';', '\t')
        return candidates.maxByOrNull { d -> splitRow(firstRow, d).size } ?: ','
    }

    /** Splits one CSV row, honouring double-quoted fields that contain the delimiter. */
    internal fun splitRow(row: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                c == '"' && inQuotes && row.getOrNull(i + 1) == '"' -> { cur.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == delimiter && !inQuotes -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString().trim()
        return out
    }
}
