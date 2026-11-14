package com.xemmy.mmbank.domain.parser

/**
 * Decides whether a captured message is "the same kind of message" as one the
 * user has already marked as noise (see
 * [com.xemmy.mmbank.domain.model.IgnoredMessagePattern]).
 *
 * The messages worth ignoring -- promos, OTPs, marketing blasts -- arrive as
 * the same template over and over with only the numbers changed: a balance, a
 * code, an expiry. So the comparison key is the message's *shape*:
 * [fingerprint] runs the text through [TextNormalizer] (Arabic-Indic digits,
 * bidi controls, whitespace), lowercases it, collapses every digit run to a
 * single '#', and drops punctuation. Two messages cut from one template share
 * a fingerprint exactly.
 *
 * Templates that also vary a word or two (a branch, a merchant) won't match
 * exactly, so [isSimilar] falls back to token overlap (Jaccard) and treats
 * anything at or above [DEFAULT_THRESHOLD] as the same kind of message. That
 * fuzzy path is gated on [MIN_FUZZY_TOKENS]: a three-word message overlaps
 * three-word messages far too easily, and a false ignore silently drops a real
 * transaction, which is worse than letting noise through.
 */
object MessageSimilarity {

    /** Token overlap at or above this reads as the same kind of message. */
    const val DEFAULT_THRESHOLD = 0.75

    /** Below this many tokens only an exact fingerprint match counts. */
    const val MIN_FUZZY_TOKENS = 4

    private val DIGIT_RUN = Regex("\\d+")

    // Keep ASCII letters/digits, the '#' digit placeholder, and Arabic letters
    // (bank messages here are routinely bilingual); everything else is noise.
    private val NON_WORD = Regex("[^a-z0-9#\\u0600-\\u06ff ]")
    private val WHITESPACE = Regex("\\s+")

    /** The message's template shape: lowercase, digit runs as '#', no punctuation. */
    fun fingerprint(text: String): String =
        TextNormalizer.normalize(text)
            .lowercase()
            .replace(DIGIT_RUN, "#")
            .replace(NON_WORD, " ")
            .replace(WHITESPACE, " ")
            .trim()

    /** [fingerprint]'s distinct words. */
    fun tokens(text: String): Set<String> =
        fingerprint(text).split(' ').filterTo(mutableSetOf()) { it.isNotEmpty() }

    /** Jaccard overlap (0..1) of the two texts' [tokens]; 0 when either side has none. */
    fun similarity(a: String, b: String): Double {
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val shared = left.count { it in right }
        return shared.toDouble() / (left.size + right.size - shared)
    }

    /** True when [text] should be treated as another copy of [sample]'s template. */
    fun isSimilar(text: String, sample: String, threshold: Double = DEFAULT_THRESHOLD): Boolean {
        val textPrint = fingerprint(text)
        val samplePrint = fingerprint(sample)
        if (textPrint.isEmpty() || samplePrint.isEmpty()) return false
        if (textPrint == samplePrint) return true
        // Short messages only ever match exactly -- see MIN_FUZZY_TOKENS.
        if (tokens(text).size < MIN_FUZZY_TOKENS || tokens(sample).size < MIN_FUZZY_TOKENS) return false
        return similarity(text, sample) >= threshold
    }

    /** True when [text] matches any of [samples]. */
    fun matchesAny(text: String, samples: List<String>, threshold: Double = DEFAULT_THRESHOLD): Boolean =
        samples.any { isSimilar(text, it, threshold) }
}
