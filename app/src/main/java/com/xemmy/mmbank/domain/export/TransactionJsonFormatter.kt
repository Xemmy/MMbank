package com.xemmy.mmbank.domain.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders [ExportRow]s as one JSON document meant for feeding to an AI: named fields, real
 * numbers and booleans, tags as an array, and a header saying what the filters were and how
 * to read the amounts. Empty fields are left out to keep it short. One transaction per line.
 * Hand-written (no library) so it stays pure and its output is exactly what the tests see.
 */
object TransactionJsonFormatter {

    const val AMOUNT_NOTE =
        "amount is signed in the transaction's own currency: negative = money out, positive = money in. " +
            "amountInBase is the same figure converted to baseCurrency when a rate was known. " +
            "excludedFromStats rows are not counted in the app's spending totals."

    fun format(
        rows: List<ExportRow>,
        baseCurrency: String,
        filterSummary: String,
        exportedAtMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val out = StringBuilder()
        out.append("{\n")
        out.append("  \"app\": \"MMbank\",\n")
        out.append("  \"exportedAt\": ${str(iso(exportedAtMillis, zone))},\n")
        out.append("  \"baseCurrency\": ${str(baseCurrency)},\n")
        out.append("  \"filters\": ${str(filterSummary)},\n")
        out.append("  \"notes\": ${str(AMOUNT_NOTE)},\n")
        out.append("  \"count\": ${rows.size},\n")
        out.append("  \"transactions\": [")
        rows.forEachIndexed { i, r ->
            out.append(if (i == 0) "\n" else ",\n")
            out.append("    ").append(row(r, zone))
        }
        out.append(if (rows.isEmpty()) "]\n" else "\n  ]\n")
        out.append("}\n")
        return out.toString()
    }

    private fun row(r: ExportRow, zone: ZoneId): String {
        val fields = mutableListOf<String>()
        fun add(key: String, rawJson: String) { fields += "${str(key)}: $rawJson" }
        add("id", r.id.toString())
        add("date", str(iso(r.timestampMillis, zone)))
        add("kind", str(r.kind.label))
        add("direction", str(r.direction.name))
        add("amount", r.amount.toPlainString())
        add("currency", str(r.currency))
        r.amountInBase?.let { add("amountInBase", it.toPlainString()) }
        r.merchant?.let { add("merchant", str(it)) }
        r.category?.let { add("category", str(it)) }
        r.parentCategory?.let { add("parentCategory", str(it)) }
        r.account?.let { add("account", str(it)) }
        r.person?.let { add("person", str(it)) }
        if (r.tags.isNotEmpty()) add("tags", r.tags.joinToString(", ", "[", "]") { str(it) })
        r.note?.let { add("note", str(it)) }
        add("source", str(r.source))
        r.sender?.let { add("sender", str(it)) }
        r.billRef?.let { add("billRef", str(it)) }
        if (r.excludedFromStats) add("excludedFromStats", "true")
        if (r.historyOnly) add("historyOnly", "true")
        r.linkedTransactionId?.let { add("linkedTransactionId", it.toString()) }
        r.refundOfId?.let { add("refundOfId", it.toString()) }
        r.reportedBalance?.let { add("reportedBalance", it.toPlainString()) }
        return fields.joinToString(", ", "{", "}")
    }

    private fun iso(millis: Long, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(zone).toOffsetDateTime())

    /** A JSON string literal: quotes, backslashes and control characters escaped; other text kept as is. */
    internal fun str(value: String): String {
        val sb = StringBuilder(value.length + 2).append('"')
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
