package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection

/**
 * The set of card endings the parser trusts enough to route on, and the
 * direction each implies. This is the gate for the card-ending routing tier
 * (see [TransactionParser]): a message that carries only a card ending — no
 * verb keyword, no signature — is captured **only** if its ending is listed
 * here, so a stray "••1234" from an unknown card never fabricates a transaction.
 *
 * The registry deliberately holds **no account id**. The Tabby account is bound
 * downstream by the existing suffix→account lookup
 * (`TransactionRepositoryImpl.detectAccountByNumber`): the router copies the
 * ending onto `ParsedTransaction.accountNumberSuffix`, and account resolution
 * happens against the live accounts table. That keeps this a pure,
 * JVM-testable domain object with no database coupling.
 */
object CardEndingRegistry {

    /** How a matched card ending should be recorded. */
    data class CardRoute(
        val direction: TransactionDirection,
        /** Written to `ParsedTransaction.matchedRuleName` for provenance. */
        val label: String,
        /** Used only when the message text itself carries no currency token. */
        val defaultCurrency: String? = null,
        val category: TransactionCategory = TransactionCategory.NORMAL,
    )

    // Tabby Cash Card ••4543: merchant-side spend notifications ("AED27.60 with
    // Tabby Cash Card ••4543") carry no keyword, so they route here as a DEBIT.
    private val routes: Map<String, CardRoute> = mapOf(
        "4543" to CardRoute(
            direction = TransactionDirection.DEBIT,
            label = "cardending.tabby.cash",
            defaultCurrency = "AED",
        ),
    )

    /** The route for a card ending, or null when the ending isn't trusted/known. */
    fun lookup(ending: String?): CardRoute? = ending?.let { routes[it] }
}
