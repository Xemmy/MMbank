package com.xemmy.mmbank.domain.parser

/**
 * Turns the noisy merchant string a bank prints into something two spellings of
 * the same shop can be compared by.
 *
 * Captured merchants arrive as whatever the parser's regex clause grabbed, only
 * trimmed: `POS PURCHASE CARREFOUR HYPERMARKET DXB *4543` and
 * `CARREFOUR MALL OF THE EMIRATES` are, as strings, entirely unrelated. That is
 * why a category learned for one of them never applied to the other, and why
 * teaching the app anything by merchant underperformed.
 *
 * [key] produces a comparison key; [displayName] produces something readable.
 * Neither ever rewrites what is stored -- `Transaction.extractedMerchant` keeps
 * the raw capture, and both of these are computed at match time so that
 * changing the rules here re-keys existing data instead of orphaning it.
 *
 * ## Why it under-merges on purpose
 *
 * Every ambiguous branch resolves toward "leave it alone". Under-merging costs
 * the user one extra correction; over-merging files one merchant's rules onto
 * another, and a balance or a budget quietly attributes itself to the wrong
 * place. The guards below all exist to make the second failure hard:
 *
 *  - only curated proper nouns are collapsible (see
 *    [MerchantCategorizer.BRAND_TOKENS]) -- never generic words like "store";
 *  - brands match as whole tokens, so `ACE` cannot match inside `PALACE` and
 *    `SMART DUBAI` cannot collapse to `mart`;
 *  - brands under [MerchantCategorizer.MIN_BRAND_LENGTH] characters are not
 *    collapsible at all;
 *  - two *different* brands in one name never collapse (this is what stops
 *    `CARREFOUR MALL OF THE EMIRATES` from becoming an airline);
 *  - a brand that neither leads the name nor sits in a short one never
 *    collapses, so `MALL OF THE EMIRATES PARKING` stays as it is.
 */
object MerchantNormalizer {

    /**
     * Brands that are also ordinary parts of unrelated venue names, and so must
     * never be collapsed onto even though [MerchantCategorizer] matches them.
     *
     * "emirates" is an airline, a mall, a bank and a district; "noon" is a
     * retailer and a time of day. Collapsing either would merge genuinely
     * different merchants.
     */
    internal val COLLAPSE_BLOCKLIST = setOf("emirates", "noon", "apple store", "prime video")

    /** Payment-rail noise banks prefix a merchant with. Dropped from the front only. */
    private val PROCESSOR_PREFIXES = setOf(
        "POS", "PURCHASE", "PURCH", "CARD", "ECOM", "ECOMM", "TFR", "PMT",
        "PAYMENT", "DEBIT", "CREDIT", "VISA", "MASTERCARD", "MC", "WWW",
        "HTTP", "HTTPS", "ONLINE", "TXN", "TRX",
    )

    /** Place names banks suffix a merchant with. Dropped from the end only. */
    private val LOCATION_SUFFIXES = setOf(
        "DUBAI", "DXB", "AUH", "ABU", "DHABI", "ABUDHABI", "SHARJAH", "SHJ",
        "AJMAN", "RAK", "ALAIN", "FUJAIRAH", "UAQ", "AE", "UAE", "ARE",
    )

    private val COLLAPSIBLE_BRANDS: List<List<String>> by lazy {
        MerchantCategorizer.BRAND_TOKENS
            .filter { it !in COLLAPSE_BLOCKLIST }
            .map { brand -> brand.uppercase().split(" ").filter { it.isNotBlank() } }
            // Longest first (in tokens, then characters) so "union coop" beats
            // "coop" and "noon food" beats "noon".
            .sortedWith(compareByDescending<List<String>> { it.size }.thenByDescending { it.joinToString("").length })
    }

    /** A name this short after cleaning is not worth collapsing or keying on. */
    private const val MIN_KEY_LENGTH = 3

    /** Beyond this many tokens, a brand must LEAD the name to be collapsible. */
    private const val SHORT_NAME_TOKENS = 3

    /**
     * A stable comparison key for [raw].
     *
     * Returns `""` when there is nothing usable to compare. Callers **must**
     * treat an empty key as "no key", never as a value that can match -- two
     * unkeyable merchants are not the same merchant.
     */
    fun key(raw: String?): String {
        val tokens = meaningfulTokens(raw).map { it.uppercase() }
        if (tokens.isEmpty()) return ""
        val cleaned = tokens.joinToString(" ").lowercase()
        if (cleaned.length < MIN_KEY_LENGTH) return ""
        return collapseToBrand(tokens) ?: cleaned
    }

    /**
     * A readable name for [raw]: the same noise stripped, but **never**
     * collapsed to a brand -- "Carrefour Market Jumeirah" is a place the user
     * recognises, and showing it as "Carrefour" would be a worse label even
     * though both key the same.
     */
    fun displayName(raw: String?): String? {
        val tokens = meaningfulTokens(raw)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { token ->
            // Banks shout. Title-case the shouting, but leave 3-character
            // acronyms and marks (KFC, H&M, OSN) as they are -- title-casing
            // those reads as a typo rather than a name.
            if (token.length > 3 && token.none { it.isLowerCase() }) {
                token.first() + token.drop(1).lowercase()
            } else {
                token
            }
        }.ifBlank { null }
    }

    /** True only when both sides produce the same NON-EMPTY key. */
    fun sameMerchant(a: String?, b: String?): Boolean {
        val keyA = key(a)
        return keyA.isNotEmpty() && keyA == key(b)
    }

    /**
     * [raw] reduced to the tokens that actually identify the merchant, with the
     * original casing preserved so [displayName] can use it too.
     */
    private fun meaningfulTokens(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        // TextNormalizer first: Arabic-Indic digits, bidi controls and
        // whitespace collapsing are already solved there.
        val normalized = TextNormalizer.normalize(raw)
        // '&' survives so "H&M" stays one token.
        var tokens = normalized
            .map { if (it.isLetterOrDigit() || it == '&') it else ' ' }
            .joinToString("")
            .split(" ")
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        // Noise first: store numbers, card tails and reference blobs. It has to
        // come before the suffix strip, or a trailing "*4543" would shield the
        // "DXB" in front of it from ever being stripped. Never removes the last
        // survivor, so a merchant that is only a number still has something.
        val withoutNoise = tokens.filterNot { isNoise(it) }
        tokens = if (withoutNoise.isEmpty()) tokens.take(1) else withoutNoise

        // Front only: a merchant genuinely named "Card Factory" keeps its name
        // as long as something follows the stripped token.
        while (tokens.size > 1 && tokens.first().uppercase() in PROCESSOR_PREFIXES) {
            tokens = tokens.drop(1)
        }
        // Back only, and one token at a time, so "ABU DHABI" falls out as
        // DHABI then ABU without needing a two-word rule.
        while (tokens.size > 1 && tokens.last().uppercase() in LOCATION_SUFFIXES) {
            tokens = tokens.dropLast(1)
        }
        return tokens
    }

    /** Store numbers (`#1234`, `0421`) and reference codes (`TXN123456`, `XXX810001`). */
    private fun isNoise(token: String): Boolean {
        if (token.all { it.isDigit() }) return true
        val digits = token.count { it.isDigit() }
        val letters = token.count { it.isLetter() }
        // A short letter prefix on a long digit run is a reference, not a name.
        return digits >= 3 && letters <= 3
    }

    /**
     * The single brand [tokens] unambiguously names, or null to leave the key
     * uncollapsed. See the class KDoc for why every ambiguity lands here.
     */
    private fun collapseToBrand(tokens: List<String>): String? {
        val matches = COLLAPSIBLE_BRANDS.mapNotNull { brand ->
            indexOfRun(tokens, brand)?.let { start -> Match(brand, start, start + brand.size) }
        }
        if (matches.isEmpty()) return null

        // Drop a match fully contained in a longer one, so "noon" inside
        // "noon food" is one brand rather than two competing ones.
        val outermost = matches.filterNot { inner ->
            matches.any { outer -> outer !== inner && inner.start >= outer.start && inner.end <= outer.end }
        }
        val distinct = outermost.distinctBy { it.brand }
        // Two genuinely different brands in one name: no idea which one owns it.
        if (distinct.size != 1) return null

        val match = distinct.single()
        // Either the brand opens the name, or the name is short enough that it
        // can't be "<venue> ... <brand> ... <department>".
        if (match.start != 0 && tokens.size > SHORT_NAME_TOKENS) return null

        val collapsed = match.brand.joinToString(" ").lowercase()
        return collapsed.takeIf { it.length >= MIN_KEY_LENGTH }
    }

    private data class Match(val brand: List<String>, val start: Int, val end: Int)

    /** Index where [run] appears in [tokens] as a contiguous whole-token run. */
    private fun indexOfRun(tokens: List<String>, run: List<String>): Int? {
        if (run.isEmpty() || run.size > tokens.size) return null
        for (start in 0..tokens.size - run.size) {
            if ((run.indices).all { tokens[start + it] == run[it] }) return start
        }
        return null
    }
}
