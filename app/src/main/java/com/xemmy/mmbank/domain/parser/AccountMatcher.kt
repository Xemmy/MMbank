package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.MerchantAccountMapping

/**
 * Resolves a merchant to a learned account id — the account counterpart to
 * [CategoryMatcher].
 *
 * Two tiers, exact then normalized key, and deliberately **no regex tier**: a
 * learned account rule is always written from a concrete merchant the user
 * corrected, never hand-authored as a pattern, so there is nothing for a regex
 * tier to do but add a way to be surprised.
 *
 * Requiring a non-empty key on both sides is what stops two unkeyable
 * merchants from colliding on the empty string — a wrong account here silently
 * moves money between balances, which is a sharper failure than a wrong
 * category, so this errs toward matching nothing.
 */
object AccountMatcher {
    fun match(merchant: String?, mappings: List<MerchantAccountMapping>): Long? {
        if (merchant.isNullOrBlank()) return null

        mappings.firstOrNull { it.merchantPattern.equals(merchant, ignoreCase = true) }
            ?.let { return it.accountId }

        val key = MerchantNormalizer.key(merchant)
        if (key.isEmpty()) return null
        return mappings.firstOrNull { MerchantNormalizer.key(it.merchantPattern) == key }?.accountId
    }
}
