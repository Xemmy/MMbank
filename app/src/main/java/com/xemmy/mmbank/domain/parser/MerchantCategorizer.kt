package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.SpendingCategory

/**
 * Best-effort spending-category guesser. Runs at capture time on the parsed
 * merchant (falling back to the raw message text) and is a pure function, so
 * it is cheap and unit-testable. A miss returns [SpendingCategory.UNCATEGORIZED]
 * — the caller only ever fills categories that are still unset, so an explicit
 * user choice is never overwritten.
 *
 * Keyword lists lean on the UAE merchants this app sees most. Matching is
 * case-insensitive substring so it degrades gracefully elsewhere -- except for
 * short, ambiguous keywords, which must stand as a whole word (see
 * [WHOLE_WORD_KEYWORDS]) so "Palace" is not shopping and "Smart" is not a
 * grocery store.
 */
object MerchantCategorizer {

    // Order matters: the first bucket whose keywords hit wins, so put the more
    // specific buckets (fuel, transport) ahead of broader ones (shopping).
    private val rules: List<Pair<SpendingCategory, List<String>>> = listOf(
        SpendingCategory.GROCERIES to listOf(
            "carrefour", "lulu", "spinneys", "grocery", "groceries", "supermarket",
            "hypermarket", "waitrose", "choithram", "union coop", "coop", "baqala",
            "west zone", "viva", "grandiose", "aswaaq", "mart", "nesto",
        ),
        SpendingCategory.FUEL to listOf(
            "adnoc", "enoc", "eppco", "emarat", "petrol", "fuel", "gas station",
        ),
        SpendingCategory.TRANSPORT to listOf(
            "careem", "uber", "taxi", "salik", "rta", "metro", "parking", "bolt",
            "yango", "hala", "nol", "darb",
        ),
        SpendingCategory.DINING to listOf(
            "talabat", "deliveroo", "zomato", "noon food", "restaurant", "cafe",
            "coffee", "starbucks", "mcdonald", "kfc", "burger", "pizza", "dining",
            "bakery", "eatery", "shawarma", "costa", "tim horton",
        ),
        SpendingCategory.ENTERTAINMENT to listOf(
            "netflix", "spotify", "youtube", "apple.com", "apple.com/bill", "itunes",
            "google play", "playstation", "xbox", "steam", "cinema", "vox", "reel",
            "disney", "shahid", "osn", "anghami", "prime video", "twitch",
        ),
        SpendingCategory.UTILITIES to listOf(
            "dewa", "sewa", "addc", "fewa", "etisalat", "du ", "du.", "utility",
            "electricity", "water bill", "internet", "telecom", "mobile bill",
        ),
        SpendingCategory.HEALTH to listOf(
            "pharmacy", "aster", "medcare", "clinic", "hospital", "medical", "dental",
            "dentist", "life pharmacy", "bin sina", "nmc", "healthhub", "labs",
        ),
        SpendingCategory.TRAVEL to listOf(
            "emirates", "etihad", "flydubai", "air arabia", "booking.com", "airbnb",
            "hotel", "agoda", "expedia", "airline", "airways", "wizz", "qatar airways",
        ),
        SpendingCategory.SHOPPING to listOf(
            "amazon", "noon", "namshi", "sharaf dg", "ikea", "h&m", "zara", "centrepoint",
            "shein", "ace", "apple store", "mall", "store", "shop", "fashion", "nike",
            "adidas", "electronics", "jumbo",
        ),
        SpendingCategory.FEES to listOf(
            "fee", "fees", "charge", "vat", "interest", "profit", "penalty", "commission",
        ),
        SpendingCategory.TRANSFERS to listOf(
            "transfer", "remittance", "western union", "exchange", "atm", "cash withdrawal",
            "iban", "sent to", "received from",
        ),
    )

    /**
     * Brands shorter than this are never collapsed onto. "kfc", "nol", "rta",
     * "osn", "vox", "ace" are real brands but also common letter runs, and a
     * wrong merge is far worse than a missed one -- they still categorize fine.
     */
    internal const val MIN_BRAND_LENGTH = 4

    /**
     * The subset of [rules]' vocabulary that is a proper-noun BRAND, curated by
     * hand.
     *
     * The keyword lists above are half brands ("carrefour", "adnoc") and half
     * generic category words ("mart", "store", "mall", "coop", "parking",
     * "hotel", "cafe"). Both are fine for guessing a *category* -- a place with
     * "store" in its name probably is shopping. Neither is fine for collapsing
     * two merchant names onto one identity, which is what
     * [MerchantNormalizer] uses this for: collapsing on "store" would file
     * every unrelated shop in the ledger under one merchant.
     *
     * Entries that are also common substrings of unrelated venue names are
     * deliberately absent -- see [MerchantNormalizer.COLLAPSE_BLOCKLIST] for
     * the ones that had to be excluded explicitly rather than simply not
     * listed ("emirates" is an airline AND a mall AND a bank).
     *
     * Names containing a dot ("apple.com", "booking.com") are also absent: the
     * normalizer splits on punctuation, so such a token could never match.
     */
    private val BRAND_TOKEN_SET = setOf(
        // Groceries
        "carrefour", "lulu", "spinneys", "waitrose", "choithram", "union coop",
        "west zone", "grandiose", "aswaaq", "nesto",
        // Fuel
        "adnoc", "enoc", "eppco", "emarat",
        // Transport
        "careem", "uber", "salik", "yango",
        // Dining
        "talabat", "deliveroo", "zomato", "noon food", "starbucks", "mcdonald",
        "tim horton",
        // Entertainment
        "netflix", "spotify", "youtube", "itunes", "google play", "playstation",
        "xbox", "disney", "shahid", "anghami", "twitch",
        // Utilities
        "dewa", "sewa", "addc", "fewa", "etisalat",
        // Health
        "medcare", "healthhub", "life pharmacy", "bin sina",
        // Travel
        "etihad", "flydubai", "air arabia", "airbnb", "agoda", "expedia", "wizz",
        "qatar airways",
        // Shopping
        "amazon", "namshi", "sharaf dg", "ikea", "zara", "centrepoint", "shein",
        "nike", "adidas", "jumbo",
        // Transfers
        "western union",
    )

    /**
     * Brand tokens [MerchantNormalizer] may collapse a merchant key onto,
     * longest first so "union coop" is tried before "coop" and "noon food"
     * before "noon".
     *
     * Derived by filtering [rules] rather than duplicating it, so this can only
     * ever contain vocabulary [categorize] also knows about -- and
     * `MerchantCategorizerBrandsTest` fails if an entry in [BRAND_TOKEN_SET]
     * stops matching anything, which is what would otherwise let a typo here
     * silently shrink the list.
     */
    val BRAND_TOKENS: List<String> = rules
        .flatMap { it.second }
        .filter { it in BRAND_TOKEN_SET && it.length >= MIN_BRAND_LENGTH }
        .distinct()
        .sortedByDescending { it.length }

    /** Everything [categorize] matches on, for cross-checking [BRAND_TOKEN_SET]. */
    internal val keywordVocabulary: Set<String> = rules.flatMap { it.second }.toSet()

    /** Curated brands, for the same cross-check. */
    internal val curatedBrandTokens: Set<String> = BRAND_TOKEN_SET

    fun categorize(merchant: String?, rawText: String? = null): SpendingCategory {
        val haystack = buildString {
            merchant?.let { append(it).append(' ') }
            rawText?.let { append(it) }
        }.lowercase()
        if (haystack.isBlank()) return SpendingCategory.UNCATEGORIZED

        for ((category, keywords) in rules) {
            if (keywords.any { matches(haystack, it) }) return category
        }
        return SpendingCategory.UNCATEGORIZED
    }

    /**
     * Keywords that are real brands but also short runs of letters inside
     * unrelated words -- "ace" in Palace/Space/Acer, "mart" in Smart/Martini,
     * "hala" in Halal, "vat" in Private -- so they must stand as a whole word.
     * Every keyword of three letters or fewer is treated this way too.
     * Everything else stays a plain substring match: "carrefour" is meant to
     * match inside "CARREFOUR-MAF".
     */
    private val WHOLE_WORD_KEYWORDS = setOf("mart", "hala", "reel", "viva", "bolt", "labs")

    /** Compound prefixes a whole-word keyword may still follow: "supermart", "dmart". */
    private val COMPOUND_PREFIXES = mapOf("mart" to listOf("super", "hyper", "mini", "mega", "d"))

    private const val SHORT_KEYWORD = 3

    private fun matches(haystack: String, keyword: String): Boolean {
        if (keyword.length > SHORT_KEYWORD && keyword !in WHOLE_WORD_KEYWORDS) return haystack.contains(keyword)
        var from = 0
        while (true) {
            val at = haystack.indexOf(keyword, from)
            if (at < 0) return false
            if (standsAlone(haystack, at, keyword)) return true
            from = at + 1
        }
    }

    private fun standsAlone(haystack: String, at: Int, keyword: String): Boolean {
        val before = haystack.getOrNull(at - 1)
        val leftOk = before == null || !before.isLetterOrDigit() ||
            COMPOUND_PREFIXES[keyword].orEmpty().any { haystack.substring(0, at).endsWith(it) }
        if (!leftOk) return false
        // A keyword that ends in a space or dot ("du ") carries its own right edge.
        if (!keyword.last().isLetterOrDigit()) return true
        val after = haystack.getOrNull(at + keyword.length)
        return after == null || !after.isLetterOrDigit()
    }
}
