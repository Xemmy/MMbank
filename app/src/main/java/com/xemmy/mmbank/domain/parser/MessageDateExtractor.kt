package com.xemmy.mmbank.domain.parser

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pulls the event date (and time, when the message carries one) out of a bank
 * message's own text.
 *
 * Only the pasted-import path needs this: a live capture already knows exactly
 * when it arrived, but a message pasted from a backlog would otherwise land in
 * the ledger dated today. Reading the date the bank itself printed puts those
 * rows where they belong.
 *
 * Deliberately conservative, because a wrong date is worse than no date:
 *  - a date needs all three components, so a card expiry ("exp 12/26") can't be
 *    mistaken for one;
 *  - day-first (12/06/2025 = 12 June) is the default, matching the UAE bank
 *    messages this app parses, unless the second component rules it out;
 *  - anything in the future or absurdly far back is rejected outright, and the
 *    caller falls back to import time.
 *
 * [Extracted.hasTimeOfDay] is what tells the importer whether it knows the real
 * moment of the transaction or only the day -- see
 * [com.xemmy.mmbank.domain.usecase.ImportPastedMessagesUseCase], which only
 * lets the sliding-window duplicate check run when the clock time is real.
 */
object MessageDateExtractor {

    /** A resolved timestamp plus whether the message actually stated a clock time. */
    data class Extracted(val epochMillis: Long, val hasTimeOfDay: Boolean)

    /** Rejects anything further back than this; a bank SMS this old isn't worth importing. */
    private const val MAX_AGE_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000

    /** Small grace for clock skew between the device and the bank's timestamp. */
    private const val MAX_FUTURE_MILLIS = 24L * 60 * 60 * 1000

    private val NUMERIC_DATE = Regex("(\\d{1,4})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})")
    private val NAMED_DATE = Regex("(\\d{1,2})[\\s\\-]([A-Za-z]{3,9})[\\s\\-](\\d{2,4})")
    private val TIME = Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*([AaPp][Mm])?")

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    /**
     * The event timestamp stated in [text], or null when it states none that is
     * plausible relative to [now].
     */
    fun extract(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Extracted? {
        val normalized = TextNormalizer.normalize(text)
        val date = findDate(normalized) ?: return null
        val time = findTime(normalized)
        val instant = date.atTime(time ?: LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()
        if (instant > now + MAX_FUTURE_MILLIS) return null
        if (now - instant > MAX_AGE_MILLIS) return null
        return Extracted(epochMillis = instant, hasTimeOfDay = time != null)
    }

    /** First plausible date in the text; malformed candidates are skipped, not fatal. */
    private fun findDate(text: String): LocalDate? =
        NUMERIC_DATE.findAll(text).firstNotNullOfOrNull { numericDate(it) }
            ?: NAMED_DATE.findAll(text).firstNotNullOfOrNull { namedDate(it) }

    private fun numericDate(match: MatchResult): LocalDate? {
        val (first, second, third) = match.destructured
        // A 4-digit leading component can only be an ISO year (2025-06-12).
        return if (first.length == 4) {
            date(year = first.toInt(), month = second.toInt(), day = third.toInt())
        } else {
            val a = first.toInt()
            val b = second.toInt()
            // Day-first unless the numbers themselves say otherwise: a second
            // component over 12 can only be a day, so a US-style 06/25/2025
            // still reads correctly as 25 June.
            val (day, month) = if (b > 12) b to a else a to b
            date(year = fullYear(third), month = month, day = day)
        }
    }

    private fun namedDate(match: MatchResult): LocalDate? {
        val (day, monthName, year) = match.destructured
        val month = MONTHS[monthName.take(3).lowercase()] ?: return null
        return date(year = fullYear(year), month = month, day = day.toInt())
    }

    /** "25" -> 2025. Two-digit years in a bank message are always this century. */
    private fun fullYear(raw: String): Int = raw.toInt().let { if (raw.length <= 2) 2000 + it else it }

    private fun date(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }

    private fun findTime(text: String): LocalTime? =
        TIME.findAll(text).firstNotNullOfOrNull { match ->
            val (rawHour, minute, second, meridiem) = match.destructured
            val hour = when {
                meridiem.isEmpty() -> rawHour.toInt()
                meridiem.lowercase() == "pm" -> if (rawHour.toInt() == 12) 12 else rawHour.toInt() + 12
                else -> if (rawHour.toInt() == 12) 0 else rawHour.toInt()
            }
            try {
                LocalTime.of(hour, minute.toInt(), second.ifEmpty { "0" }.toInt())
            } catch (_: DateTimeException) {
                null
            }
        }
}
