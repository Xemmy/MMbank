package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.util.Digits

/**
 * What the card-ending routing tier (see [TransactionParser]) trusts. A message that
 * carries only a card ending -- no verb keyword, no signature -- is captured **only**
 * when the ending is one the user already owns ([trustedEndings], read from their card
 * accounts) or the text itself names a cash / pay-later card ([MARKER]); a stray
 * "••1234" from an unknown card never fabricates a transaction.
 *
 * No digits are hard-coded: the account is bound downstream by the suffix->account
 * lookup (`TransactionRepositoryImpl.detectAccountByNumber`), and an unknown card is
 * offered to the user by `UnknownAccountSuggestions`. Pure and JVM-testable.
 */
object CardEndingRegistry {

    /** How a trusted card ending is recorded. */
    data class CardRoute(
        val direction: TransactionDirection,
        /** Written to `ParsedTransaction.matchedRuleName` for provenance. */
        val label: String,
        val category: TransactionCategory = TransactionCategory.NORMAL,
    )

    /** "Tabby Cash Card", "Tamara cash card", "Spotii Card"... : a pay-later / cash card named in the text. */
    private val MARKER = Regex("""(?i)\b(?:tabby|tamara|postpay|spotii)\b.{0,12}\bcard\b|\bcash\s*card\b""")

    /**
     * The route for [ending] found in [text], or null when the ending isn't trusted: it is
     * (a suffix of / ends with) one of [trustedEndings], or [text] carries the card marker.
     */
    fun lookup(ending: String?, text: String, trustedEndings: Set<String>): CardRoute? {
        val digits = Digits.only(ending)
        if (digits.isEmpty()) return null
        if (trustedEndings.any { it.isNotEmpty() && (it.endsWith(digits) || digits.endsWith(it)) }) {
            return CardRoute(TransactionDirection.DEBIT, "cardending.account")
        }
        if (MARKER.containsMatchIn(text)) return CardRoute(TransactionDirection.DEBIT, "cardending.marker")
        return null
    }
}
