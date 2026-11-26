package com.xemmy.mmbank.domain.paylater

/**
 * Recognises Tabby's own messages and the bank charges that pay Tabby. Pure.
 *
 * One Tabby split-in-4 checkout produces three messages:
 *  - Tabby: "Your AED 924.00 purchase at Shory is confirmed. ..." -- the purchase ([isSplitPurchase]);
 *  - Tabby: "We received your first payment of AED 231.00. ..." -- a receipt ([isReceipt]);
 *  - the bank of whichever card was used: "... AED231.00 ... at Tabby ..." -- the charge
 *    ([providerOf] is "Tabby"). The bank and its wording vary with the card, so the charge is
 *    recognised by the word "Tabby" in its merchant or, failing that, its text.
 */
object PayLaterProviders {

    const val TABBY = "Tabby"

    // "Tabby" naming who was paid -- not "Tabby Card" / "Tabby Cash" (a purchase made WITH
    // Tabby's own card at some other shop) and not "Tabby+" (its subscription).
    private val TABBY_PAYEE = Regex("\\btabby\\b(?!\\s*(?:cash|card|plus)\\b)(?!\\s*\\+)", RegexOption.IGNORE_CASE)
    private val TABBY_SENDER = Regex("\\btabby\\b", RegexOption.IGNORE_CASE)

    private val RECEIPT = Regex(
        "\\bwe\\s+(?:have\\s+)?received\\s+your\\s+(?:\\w+\\s+){0,2}?payment\\s+of\\b",
        RegexOption.IGNORE_CASE,
    )

    private val SPLIT_PURCHASE = Regex(
        "\\byour\\s+\\S+?\\s?[\\d.,]+\\s+purchase\\s+at\\s+(?<merchant>[^.,;\\n*]{2,40}?)\\s+is\\s+confirmed\\b",
        RegexOption.IGNORE_CASE,
    )

    /** The message came from Tabby itself (SMS sender "Tabby", app package "com.tabby.customer"). */
    fun isProviderSender(sender: String?): Boolean = sender != null && TABBY_SENDER.containsMatchIn(sender)

    /** Tabby confirming an installment the user paid ("We received your first payment of AED 231.00"). */
    fun isReceipt(rawMessage: String?, sender: String?): Boolean =
        rawMessage != null && isProviderSender(sender) && RECEIPT.containsMatchIn(rawMessage)

    /** Tabby's split checkout ("Your AED 924.00 purchase at Shory is confirmed"). */
    fun isSplitPurchase(rawMessage: String?, sender: String?): Boolean =
        rawMessage != null && (sender.isNullOrBlank() || isProviderSender(sender)) &&
            SPLIT_PURCHASE.containsMatchIn(rawMessage)

    /** The shop named in a split checkout's text ("Shory"), or null. */
    fun splitPurchaseMerchant(rawMessage: String?): String? =
        rawMessage?.let { SPLIT_PURCHASE.find(it) }
            ?.let { (it.groups as? MatchNamedGroupCollection)?.get("merchant") }
            ?.value?.trim()?.takeIf { it.isNotBlank() }

    /**
     * "Tabby" when this row paid Tabby: its merchant names Tabby, or it has no merchant and a
     * bank's text names Tabby as the payee. Tabby's own messages never count through their text
     * (their checkout names Tabby too) -- only the receipt, whose merchant the parser sets.
     */
    fun providerOf(merchant: String?, rawMessage: String?, sender: String?): String? {
        if (!merchant.isNullOrBlank()) return if (TABBY_PAYEE.containsMatchIn(merchant)) TABBY else null
        if (isProviderSender(sender)) return null
        return if (rawMessage != null && TABBY_PAYEE.containsMatchIn(rawMessage)) TABBY else null
    }
}
