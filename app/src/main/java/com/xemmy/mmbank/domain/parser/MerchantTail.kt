package com.xemmy.mmbank.domain.parser

/**
 * Cuts the trailing sentence off a greedily captured merchant. The built-in
 * merchant groups run up to 40 characters, so "AMAZON on card 1234" or an
 * Arabic "نون حساب رقم XXX810" would otherwise keep the tail (and the account
 * number inside it) as part of the name.
 */
object MerchantTail {
    private val STOP = Regex(
        "\\s+(?:on|with|using|via|ref|avl|avail|available|balance|bal|card|acct|account|a/c|" +
            "حساب|حسابك|رصيد|الرصيد|بطاقة|بطاقتك|رقم|بتاريخ)\\b.*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun trim(raw: String): String {
        val cut = raw.replace(STOP, "").trim()
        // Never trim a name away entirely (e.g. a merchant literally starting "Card ...").
        return if (cut.length >= 2) cut else raw
    }
}
