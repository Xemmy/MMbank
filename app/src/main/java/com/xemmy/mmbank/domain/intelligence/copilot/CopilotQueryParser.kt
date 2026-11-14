package com.xemmy.mmbank.domain.intelligence.copilot

import com.xemmy.mmbank.domain.budget.CategoryHierarchy
import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.parser.MerchantNormalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Understands the common shapes of a money question on the phone, with no
 * model: what is asked ([CopilotIntent]), over which days ([CopilotRange]) and
 * about what ([CopilotSubject] -- one of the user's own categories or a
 * merchant from their own ledger).
 *
 * Deliberately conservative: [parse] returns null rather than guess, and the
 * Copilot then hands the question to the configured AI or offers examples.
 * A wrong number delivered confidently is worse than "ask me differently".
 * English only; pure and JVM-testable.
 */
object CopilotQueryParser {

    /**
     * Parses [question]. [merchants] are merchant names as stored on the
     * user's transactions (any spelling; duplicates are fine). Returns null
     * when no intent can be recognised.
     */
    fun parse(
        question: String,
        categories: List<Category>,
        merchants: Collection<String>,
        today: LocalDate,
    ): CopilotQuery? {
        val text = normalize(question)
        if (text.isBlank()) return null
        val subject = findSubject(text, categories, merchants)
        val intent = detectIntent(text) ?: if (subject != null) CopilotIntent.SPEND else return null
        val range = parseRange(question, today) ?: defaultRange(today)
        // A ranking question names a dimension, not a subject: "top merchants
        // in Dining" keeps Dining; "top categories" never narrows to one.
        val effectiveSubject = when (intent) {
            CopilotIntent.TOP_CATEGORIES -> subject as? CopilotSubject.MerchantSubject
            // "How much did Employer pay me" keeps the payer; the Income
            // category is the question itself, never a filter.
            CopilotIntent.INCOME -> subject as? CopilotSubject.MerchantSubject
            CopilotIntent.SAVINGS_RATE -> null
            else -> subject
        }
        return CopilotQuery(intent, range.window, range.label, effectiveSubject)
    }

    /** "This month so far" -- what an unqualified question is about. */
    fun defaultRange(today: LocalDate): CopilotRange =
        CopilotRange(DateWindow(today.withDayOfMonth(1), today), "this month")

    /**
     * The time range [question] mentions, or null when it names none (the
     * caller then picks a default). Weeks start on Monday.
     */
    fun parseRange(question: String, today: LocalDate): CopilotRange? {
        val text = normalize(question)

        Regex("""\b(?:last|past|previous)\s+(\d{1,4})\s+(days?|weeks?|months?)\b""").find(text)?.let { m ->
            val n = m.groupValues[1].toInt().coerceIn(1, 3650)
            val unit = m.groupValues[2]
            return when {
                unit.startsWith("day") -> CopilotRange(
                    DateWindow(today.minusDays(n - 1L), today),
                    if (n == 1) "today" else "in the last $n days",
                )
                unit.startsWith("week") -> CopilotRange(
                    DateWindow(today.minusDays(7L * n - 1), today),
                    if (n == 1) "in the last week" else "in the last $n weeks",
                )
                else -> CopilotRange(
                    DateWindow(today.minusMonths(n.toLong()).plusDays(1), today),
                    if (n == 1) "in the last month" else "in the last $n months",
                )
            }
        }
        if (has(text, "today")) return CopilotRange(DateWindow(today, today), "today")
        if (has(text, "yesterday")) {
            val y = today.minusDays(1)
            return CopilotRange(DateWindow(y, y), "yesterday")
        }

        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        // "The past week/month/year" is a rolling window ending today, not the
        // previous calendar period ("last week" is that).
        if (has(text, "past week")) {
            return CopilotRange(DateWindow(today.minusDays(6), today), "in the last week")
        }
        if (has(text, "past month")) {
            return CopilotRange(DateWindow(today.minusMonths(1).plusDays(1), today), "in the last month")
        }
        if (has(text, "past year")) {
            return CopilotRange(DateWindow(today.minusYears(1).plusDays(1), today), "in the last year")
        }
        if (has(text, "this week", "current week", "so far this week")) {
            return CopilotRange(DateWindow(weekStart, today), "this week")
        }
        if (has(text, "last week", "previous week")) {
            return CopilotRange(DateWindow(weekStart.minusDays(7), weekStart.minusDays(1)), "last week")
        }
        // Year phrases before the bare "so far": "so far this year" is the year.
        if (has(text, "this year", "current year", "year to date", "ytd")) {
            return CopilotRange(DateWindow(today.withDayOfYear(1), today), "this year")
        }
        if (has(text, "this month", "current month", "so far", "month to date", "mtd")) {
            return defaultRange(today)
        }
        if (has(text, "last month", "previous month")) {
            return monthRange(YearMonth.from(today).minusMonths(1), today)
        }
        if (has(text, "last year", "previous year")) {
            val y = today.year - 1
            return CopilotRange(DateWindow(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31)), "in $y")
        }

        monthMention(text, today)?.let { return monthRange(it, today) }

        Regex("""\b(?:in|for|during)\s+(\d{4})\b""").find(text)?.let { m ->
            val y = m.groupValues[1].toInt()
            if (y in 1970..today.year) {
                return if (y == today.year) {
                    CopilotRange(DateWindow(today.withDayOfYear(1), today), "this year")
                } else {
                    CopilotRange(DateWindow(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31)), "in $y")
                }
            }
        }
        if (has(text, "all time", "ever", "overall", "in total", "since the start")) {
            return CopilotRange(DateWindow(LocalDate.of(1970, 1, 1), today), "in total")
        }
        return null
    }

    // ---------------------------------------------------------------- intent

    private val CURRENCY_WORDS = arrayOf(
        "dirham", "dirhams", "aed", "dollar", "dollars", "usd", "euro", "euros", "eur",
        "pound", "pounds", "gbp", "rupee", "rupees", "inr", "bucks",
    )

    private fun detectIntent(text: String): CopilotIntent? {
        val biggestWord = has(text, "biggest", "largest", "highest", "most expensive", "priciest", "single biggest")
        val itemNoun = has(
            text, "expense", "expenses", "purchase", "purchases", "transaction", "transactions",
            "payment", "payments", "charge", "charges", "item", "thing", "spend", "buy", "bought",
        )
        val categoryWord = Regex("""\bcategor\w*""").containsMatchIn(text)
        val merchantWord = has(text, "merchant", "merchants", "store", "stores", "shop", "shops", "place", "places", "vendor", "vendors")
        val rankWord = has(text, "top", "most", "biggest", "largest", "main", "which", "breakdown", "by", "highest", "frequent", "often")

        return when {
            has(
                text, "compare", "compared", "comparison", "vs", "versus", "than last", "than the previous",
                "than previous", "difference", "more or less", "up or down", "trend",
            ) -> CopilotIntent.COMPARE
            has(text, "savings rate", "saving rate", "save", "saved", "saving", "savings", "kept", "keep") ->
                CopilotIntent.SAVINGS_RATE
            biggestWord && itemNoun && !categoryWord && !merchantWord -> CopilotIntent.BIGGEST_EXPENSE
            categoryWord && rankWord -> CopilotIntent.TOP_CATEGORIES
            has(
                text, "money go", "money goes", "money went", "spend the most on", "spend most on",
                "spending the most on", "spent the most on", "spent most on",
            ) -> CopilotIntent.TOP_CATEGORIES
            merchantWord && rankWord -> CopilotIntent.TOP_MERCHANTS
            has(text, "where") && has(text, "most") -> CopilotIntent.TOP_MERCHANTS
            has(text, "who do i pay", "who did i pay", "whom do i pay") -> CopilotIntent.TOP_MERCHANTS
            // "How many dirhams did I spend" asks for an amount, not a count.
            has(text, "how many", "number of", "count") && !has(text, *CURRENCY_WORDS) ->
                CopilotIntent.TRANSACTION_COUNT
            has(text, "average", "avg", "per day", "a day", "daily", "each day") -> CopilotIntent.AVERAGE_PER_DAY
            has(
                text, "income", "earn", "earned", "earnings", "salary", "received", "receive", "got paid",
                "deposits", "deposited", "pay me", "paid me", "pays me", "how much did i make", "how much have i made", "money in",
            ) -> CopilotIntent.INCOME
            has(
                text, "spend", "spent", "spending", "cost", "costs", "paid", "pay", "expenses", "expense",
                "outflow", "how much", "total",
            ) -> CopilotIntent.SPEND
            else -> null
        }
    }

    // --------------------------------------------------------------- subject

    /**
     * Words that phrase a question rather than name a subject. A subject match
     * must include at least one word outside this set, so a merchant called
     * "Payment" or "Transfer" can't hijack "how much payment...".
     */
    private val GRAMMAR_WORDS: Set<String> = (
        "a an the my me i im ive id did do does done is was were are be been being have has had " +
            "how much many what whats which who whom where when why on at in into for of to from with and or " +
            "vs versus than this that these those last past previous current next so far month months week " +
            "weeks year years day days today yesterday total spend spent spending spends cost costs paid pay " +
            "payment payments expense expenses purchase purchases transaction transactions charge charges " +
            "money compare compared comparison income earn earned earnings salary received receive save saved " +
            "saving savings rate average avg daily per biggest largest highest top most main category " +
            "categories merchant merchants store stores shop shops place places all time ever overall number " +
            "count please show tell give list breakdown more less up down change changed go goes went " +
            "single expensive thing item kept keep make made get got buy bought by can could would should " +
            "you your it its there any some just only about around ytd mtd"
        ).split(" ").filter { it.isNotBlank() }.toSet() +
        Month.entries.flatMap { listOf(it.name.lowercase(), it.name.lowercase().take(3)) } + "sept"

    /**
     * Everyday words for the seeded category names. Used only when a category
     * of that name exists, and only after exact category and merchant matches.
     */
    private val CATEGORY_SYNONYMS: Map<String, Set<String>> = mapOf(
        "dining" to setOf("restaurant", "restaurants", "food", "eating", "eat", "lunch", "dinner", "breakfast", "cafe", "cafes", "coffee", "takeaway", "delivery"),
        "groceries" to setOf("grocery", "supermarket", "supermarkets"),
        "transport" to setOf("taxi", "taxis", "ride", "rides", "metro", "bus", "transportation", "commute", "parking", "salik", "uber", "careem"),
        "fuel" to setOf("petrol", "gas", "gasoline", "diesel"),
        "utilities" to setOf("bills", "electricity", "water", "internet", "phone", "mobile", "dewa"),
        "health" to setOf("medical", "pharmacy", "doctor", "doctors", "hospital", "clinic", "medicine"),
        "shopping" to setOf("clothes", "clothing", "fashion"),
        "subscriptions" to setOf("subscription", "streaming", "netflix", "spotify"),
        "entertainment" to setOf("movies", "movie", "cinema", "games", "gaming", "fun"),
        "travel" to setOf("flight", "flights", "hotel", "hotels", "trip", "trips", "holiday", "vacation"),
        "education" to setOf("school", "tuition", "course", "courses", "university"),
    )

    private data class Match(val score: Int, val length: Int, val subject: CopilotSubject)

    private const val SCORE_EXACT = 3
    private const val SCORE_SYNONYM = 2
    private const val SCORE_FUZZY = 1

    private fun findSubject(
        text: String,
        categories: List<Category>,
        merchants: Collection<String>,
    ): CopilotSubject? {
        val words = text.split(" ").filter { it.isNotEmpty() }
        val stems = words.map(::stem)
        val content = words.filter { it !in GRAMMAR_WORDS && !it.all(Char::isDigit) }
        if (content.isEmpty()) return null

        val category = bestCategory(stems, content, categories)
        val merchant = bestMerchant(stems, content, merchants)
        val categoryExact = category?.score == SCORE_EXACT
        val merchantExact = merchant?.score == SCORE_EXACT
        return when {
            // Both named verbatim: the longer phrase is the more specific one
            // ("dining hall" the merchant over "Dining" the category).
            categoryExact && merchantExact -> if (merchant!!.length > category!!.length) merchant.subject else category.subject
            categoryExact -> category!!.subject
            merchantExact -> merchant!!.subject
            // Then a category synonym or near-spelling, then a merchant's.
            category != null -> category.subject
            else -> merchant?.subject
        }
    }

    private fun bestCategory(stems: List<String>, content: List<String>, categories: List<Category>): Match? {
        var best: Match? = null
        for (c in categories) {
            val nameWords = normalize(c.name).split(" ").filter { it.isNotEmpty() && it != "and" }
            if (nameWords.isEmpty()) continue
            val subject = CopilotSubject.CategorySubject(
                categoryId = c.id,
                label = c.name,
                categoryIds = setOf(c.id) + CategoryHierarchy.descendantIds(categories, c.id),
            )
            val nameStems = nameWords.map(::stem)
            val score = when {
                nameWords.any { it !in GRAMMAR_WORDS } && containsSequence(stems, nameStems) -> SCORE_EXACT
                // "Food & Dining" matched by "dining" alone.
                nameWords.size > 1 && nameWords.any { w -> w !in GRAMMAR_WORDS && w.length >= 4 && stem(w) in stems } -> SCORE_EXACT
                CATEGORY_SYNONYMS[normalize(c.name)].orEmpty().any { syn -> syn in content } -> SCORE_SYNONYM
                nameWords.any { w -> w.length >= 5 && content.any { t -> t.length >= 5 && withinOneEdit(stem(t), stem(w)) } } -> SCORE_FUZZY
                else -> 0
            }
            if (score == 0) continue
            val candidate = Match(score, c.name.length, subject)
            if (best == null || score > best.score || (score == best.score && candidate.length > best.length)) {
                best = candidate
            }
        }
        return best
    }

    private fun bestMerchant(stems: List<String>, content: List<String>, merchants: Collection<String>): Match? {
        var best: Match? = null
        for (raw in merchants.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            val key = MerchantNormalizer.key(raw)
            val phrases = listOf(normalize(raw), key)
                .map { p -> p.split(" ").filter { it.isNotEmpty() } }
                .filter { ws -> ws.joinToString(" ").length >= 3 && ws.any { it !in GRAMMAR_WORDS } }
                .distinct()
            val label = MerchantNormalizer.displayName(raw)?.takeIf { it.isNotBlank() } ?: raw

            val exact = phrases.filter { ws -> containsSequence(stems, ws.map(::stem)) }
                .maxByOrNull { it.joinToString(" ").length }
            val match = if (exact != null) {
                val phrase = exact.joinToString(" ")
                Match(SCORE_EXACT, phrase.length, CopilotSubject.MerchantSubject(label, phrase, key.ifBlank { phrase }))
            } else {
                val first = phrases.firstNotNullOfOrNull { ws -> ws.firstOrNull { it.length >= 5 && it !in GRAMMAR_WORDS } }
                if (first != null && content.any { t -> t.length >= 5 && withinOneEdit(t, first) }) {
                    Match(SCORE_FUZZY, first.length, CopilotSubject.MerchantSubject(label, first, key.ifBlank { first }))
                } else {
                    null
                }
            } ?: continue
            if (best == null || match.score > best.score || (match.score == best.score && match.length > best.length)) {
                best = match
            }
        }
        return best
    }

    // ----------------------------------------------------------------- dates

    private val MONTH_WORDS: Map<String, Month> = buildMap {
        Month.entries.forEach { m ->
            put(m.name.lowercase(), m)
            put(m.name.lowercase().take(3), m)
        }
        put("sept", Month.SEPTEMBER)
    }

    /**
     * A month named in [text]: "october", "oct 2025", "in may". Bare "may"
     * needs a preposition before it or a year after it -- "may I know..." is
     * not a month. Without a year, the most recent such month that isn't in
     * the future.
     */
    private fun monthMention(text: String, today: LocalDate): YearMonth? {
        val words = text.split(" ").filter { it.isNotEmpty() }
        for ((i, w) in words.withIndex()) {
            val month = MONTH_WORDS[w] ?: continue
            val year = words.getOrNull(i + 1)?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt()
            if (w == "may" && year == null && words.getOrNull(i - 1) !in setOf("in", "for", "during", "of", "since", "from")) continue
            if (year != null) return YearMonth.of(year, month)
            val thisYear = YearMonth.of(today.year, month)
            return if (thisYear.isAfter(YearMonth.from(today))) thisYear.minusYears(1) else thisYear
        }
        return null
    }

    private fun monthRange(month: YearMonth, today: LocalDate): CopilotRange {
        val current = YearMonth.from(today)
        val name = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return when {
            month == current -> defaultRange(today)
            else -> CopilotRange(
                DateWindow(month.atDay(1), month.atEndOfMonth()),
                if (month.year == today.year) "in $name" else "in $name ${month.year}",
            )
        }
    }

    // --------------------------------------------------------------- helpers

    /** Lowercase, apostrophes dropped ("what's" -> "whats"), everything else non-alphanumeric -> space. */
    internal fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace("'", "").replace("’", "")
            .replace(Regex("""[^\p{L}\p{Nd}]+"""), " ")
            .trim()

    private fun has(text: String, vararg phrases: String): Boolean {
        val padded = " $text "
        return phrases.any { padded.contains(" $it ") }
    }

    /** Crude singular form: "groceries" -> "grocery", "rides" -> "ride". Never below 3 letters. */
    private fun stem(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    private fun containsSequence(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return (0..haystack.size - needle.size).any { start ->
            needle.indices.all { haystack[start + it] == needle[it] }
        }
    }

    /** True when [a] and [b] are equal or one insertion, deletion or substitution apart. */
    internal fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++; j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
