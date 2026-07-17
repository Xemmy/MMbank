package com.xemmy.mmbank.domain.parser

/**
 * Pulls a card/account **ending** (the last 3–4 digits) out of a notification
 * body, for the card-ending routing tier in [TransactionParser].
 *
 * Some issuers post a merchant-side spend notification that carries no verb
 * keyword and no stable signature — only the card it was charged to, e.g.
 * "AED27.60 with Tabby Cash Card ••4543". [GenericExtractors.extractAccountSuffix]
 * already scrapes such suffixes for signature-routed messages, but this tier
 * needs a focused, marker-anchored extractor so that a random 4-digit run
 * elsewhere in the text (a year, an amount) is never mistaken for a card.
 *
 * Recognized markers (case-insensitive), each immediately followed by 3–4
 * digits: bullet runs ("••4543", "•4543"), asterisks ("*4543"), and the words
 * "ending"/"ending in" ("ending 4543"). Expects [TextNormalizer] output, which
 * preserves the bullet/asterisk markers and message case.
 */
object CardEndingExtractor {

    private val CARD_ENDING = Regex(
        "(?:[•·]+|\\*+|\\bending(?:\\s+in)?\\s+)\\s?(?<ending>\\d{3,4})\\b",
        RegexOption.IGNORE_CASE,
    )

    /** The card ending ("4543"), or null when the text carries no card marker. */
    fun extract(text: String): String? =
        CARD_ENDING.find(text)?.groupOrNull("ending")?.takeIf { it.isNotBlank() }
}
