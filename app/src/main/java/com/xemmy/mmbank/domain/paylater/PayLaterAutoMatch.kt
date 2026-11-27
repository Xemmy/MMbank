package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.parser.MerchantNormalizer
import kotlin.math.abs

/**
 * When every Tabby installment arrives as its own SMS, a new debit that fits the next unpaid
 * installment of an open plan *is* that payment: mark it paid instead of recording another one,
 * so the money is not counted twice. Pure.
 */
object PayLaterAutoMatch {

    data class Match(val plan: PayLaterPlan, val installment: PayLaterInstallment)

    private const val AMOUNT_TOLERANCE = 0.01
    private const val WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

    private fun key(name: String?): String =
        name?.let { MerchantNormalizer.key(it).ifEmpty { it.trim().lowercase() } }.orEmpty()

    fun match(plans: List<PayLaterPlan>, installments: List<PayLaterInstallment>, tx: Transaction): Match? {
        if (tx.direction != TransactionDirection.DEBIT || tx.kind != TransactionKind.SPEND || tx.excludeFromStats) return null
        if (tx.id != 0L && installments.any { it.paymentTransactionId == tx.id }) return null
        val txKey = key(tx.merchant)
        // The card's charge names Tabby however its bank words it ("at Tabby", "TABBY FZ LLC"),
        // even when the parser found no merchant -- the paying card can be any bank's.
        val paidProvider = PayLaterProviders.providerOf(tx.merchant, tx.rawMessage, tx.sender)?.let(::key)
        if (txKey.isEmpty() && paidProvider == null) return null
        val candidates = plans.mapNotNull { plan ->
            if (plan.sourceTransactionId == tx.id && tx.id != 0L) return@mapNotNull null
            if (!plan.currencyCode.equals(tx.currencyCode, ignoreCase = true)) return@mapNotNull null
            val sameName = txKey.isNotEmpty() && (txKey == key(plan.merchant) || txKey == key(plan.provider))
            if (!sameName && paidProvider != key(plan.provider)) return@mapNotNull null
            val next = installments.filter { it.planId == plan.id && !it.isPaid }.minByOrNull { it.number }
                ?: return@mapNotNull null
            val expected = next.amount.toDouble()
            if (expected <= 0.0 || abs(tx.amount.toDouble() - expected) / expected > AMOUNT_TOLERANCE) return@mapNotNull null
            val distance = abs(tx.timestampMillis - next.dueDateMillis)
            if (distance > WINDOW_MILLIS) return@mapNotNull null
            Triple(plan, next, distance)
        }
        return candidates.minByOrNull { it.third }?.let { Match(it.first, it.second) }
    }
}
