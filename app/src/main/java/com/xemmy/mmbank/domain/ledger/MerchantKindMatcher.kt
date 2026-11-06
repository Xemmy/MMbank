package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.MerchantKindMapping
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.parser.MerchantNormalizer

/**
 * Resolves a merchant to the kind the user taught for it ("ADCB CC PAYMENT"
 * is a card payment, "Mum" is a transfer...). Same two tiers as
 * [com.xemmy.mmbank.domain.parser.AccountMatcher] -- exact, then the
 * normalized key, never regex -- and the same refusal to match two
 * unkeyable merchants on the empty key.
 */
object MerchantKindMatcher {
    fun match(merchant: String?, mappings: List<MerchantKindMapping>): TransactionKind? {
        if (merchant.isNullOrBlank()) return null
        mappings.firstOrNull { it.merchantPattern.equals(merchant.trim(), ignoreCase = true) }?.let { return it.kind }
        val key = MerchantNormalizer.key(merchant)
        if (key.isEmpty()) return null
        return mappings.firstOrNull { MerchantNormalizer.key(it.merchantPattern) == key }?.kind
    }
}
