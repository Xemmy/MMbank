package com.xemmy.mmbank.domain.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders [ExportRow]s as CSV for spreadsheets: UTF-8 byte-order mark (so Excel reads Arabic
 * and accents), RFC 4180 quoting with CRLF line ends, and a guard that stops text starting
 * with `=`, `+`, `-` or `@` from running as a formula. Pure, so it is JVM-testable.
 */
object TransactionCsvFormatter {

    const val BOM = "﻿"

    val HEADER = listOf(
        "Date", "Type", "Direction", "Amount", "Currency", "Amount in base currency",
        "Merchant", "Category", "Parent category", "Account", "Person", "Tags", "Note",
        "Source", "Sender", "Bill ref", "Excluded from stats", "History only",
        "Linked transaction id", "Refund of id", "Reported balance", "Id",
    )

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    fun format(rows: List<ExportRow>, zone: ZoneId = ZoneId.systemDefault()): String {
        val out = StringBuilder(BOM)
        out.append(HEADER.joinToString(",") { quote(it) }).append("\r\n")
        for (r in rows) {
            val cells = listOf(
                quote(DATE_FORMAT.format(Instant.ofEpochMilli(r.timestampMillis).atZone(zone))),
                text(r.kind.label),
                text(r.direction.name),
                quote(r.amount.toPlainString()),
                text(r.currency),
                quote(r.amountInBase?.toPlainString().orEmpty()),
                text(r.merchant.orEmpty()),
                text(r.category.orEmpty()),
                text(r.parentCategory.orEmpty()),
                text(r.account.orEmpty()),
                text(r.person.orEmpty()),
                text(r.tags.joinToString("; ")),
                text(r.note.orEmpty()),
                text(r.source),
                text(r.sender.orEmpty()),
                text(r.billRef.orEmpty()),
                quote(if (r.excludedFromStats) "yes" else "no"),
                quote(if (r.historyOnly) "yes" else "no"),
                quote(r.linkedTransactionId?.toString().orEmpty()),
                quote(r.refundOfId?.toString().orEmpty()),
                quote(r.reportedBalance?.toPlainString().orEmpty()),
                quote(r.id.toString()),
            )
            out.append(cells.joinToString(",")).append("\r\n")
        }
        return out.toString()
    }

    /** A free-text cell: formula-guarded, then quoted when needed. */
    internal fun text(value: String): String = quote(guard(value))

    /** Prefixes an apostrophe when a spreadsheet would read [value] as a formula. */
    internal fun guard(value: String): String =
        if (value.isNotEmpty() && value[0] in FORMULA_STARTS) "'$value" else value

    /** RFC 4180: wrap in quotes (doubling inner ones) when it holds a comma, quote or line break. */
    internal fun quote(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')
}
