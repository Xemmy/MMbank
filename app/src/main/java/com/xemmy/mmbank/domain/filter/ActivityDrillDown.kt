package com.xemmy.mmbank.domain.filter

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * A request to open Activity already narrowed to some transactions -- what a
 * Copilot answer's "See transactions" carries. Encoded as one compact string
 * (`cat=12,13;from=2026-10-01;to=2026-10-31;q=careem`) so it travels as a
 * single navigation argument; the caller still URI-encodes the whole string.
 */
data class ActivityDrillDown(
    val categoryIds: Set<Long> = emptySet(),
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val query: String = "",
) {
    val isEmpty: Boolean get() = categoryIds.isEmpty() && (from == null || to == null) && query.isBlank()

    fun encode(): String = buildList {
        if (categoryIds.isNotEmpty()) add("cat=" + categoryIds.sorted().joinToString(","))
        if (from != null && to != null) {
            add("from=$from")
            add("to=$to")
        }
        if (query.isNotBlank()) add("q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name()))
    }.joinToString(";")

    /**
     * Activity filters showing exactly this drill-down. Starts from [base]
     * (normally the defaults) so earlier filters don't silently narrow it.
     */
    fun applyTo(base: ActivityFilters = ActivityFilters()): ActivityFilters = base.copy(
        dateRange = if (from != null && to != null) DateRangeFilter.Custom(from, to) else base.dateRange,
        query = query.trim().ifEmpty { base.query },
        categoryIds = categoryIds.ifEmpty { null } ?: base.categoryIds,
    )

    companion object {
        /** Parses [encoded]; unknown keys and malformed values are skipped. Null when nothing usable remains. */
        fun parse(encoded: String?): ActivityDrillDown? {
            if (encoded.isNullOrBlank()) return null
            var categories = emptySet<Long>()
            var from: LocalDate? = null
            var to: LocalDate? = null
            var query = ""
            encoded.split(';').forEach { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) return@forEach
                val key = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                when (key) {
                    "cat" -> categories = value.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
                    "from" -> from = runCatching { LocalDate.parse(value) }.getOrNull()
                    "to" -> to = runCatching { LocalDate.parse(value) }.getOrNull()
                    "q" -> query = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault("")
                }
            }
            if (from != null && to != null && from!! > to!!) {
                val swap = from
                from = to
                to = swap
            }
            return ActivityDrillDown(categories, from, to, query).takeUnless { it.isEmpty }
        }
    }
}
