package com.xemmy.mmbank.domain.cashback

import java.math.BigDecimal

/** Encodes/decodes the per-category monthly spend map to a flat prefs string. */
object CashbackSpendCodec {

    fun encode(spend: Map<String, BigDecimal>): String =
        spend.filterValues { it.signum() > 0 }
            .entries
            .joinToString(";") { "${it.key}=${it.value.toPlainString()}" }

    fun decode(raw: String): Map<String, BigDecimal> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val key = part.substring(0, eq).trim()
            val amount = part.substring(eq + 1).trim().toBigDecimalOrNull() ?: return@mapNotNull null
            key to amount
        }.toMap()
    }
}
