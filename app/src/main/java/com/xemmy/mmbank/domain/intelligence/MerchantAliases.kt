package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.MerchantAlias

/** The user's "also rename future messages" rules: a captured merchant name is swapped for the chosen one. */
object MerchantAliases {

    /** Alias key of [name]: trimmed and lower-cased, so spelling case and padding never matter. */
    fun keyOf(name: String): String = name.trim().lowercase()

    /** [name] renamed by the matching alias (one hop), or unchanged when none matches or [name] is blank. */
    fun apply(name: String?, aliases: List<MerchantAlias>): String? {
        if (name.isNullOrBlank() || aliases.isEmpty()) return name
        val key = keyOf(name)
        return aliases.firstOrNull { it.fromKey == key }?.toName ?: name
    }
}
