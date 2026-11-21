package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.SubscriptionLink
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

/**
 * Manual subscription links. A link records the user's say-so that one charge
 * belongs to one subscription row (by id), whatever merchant text the bank
 * printed; [SubscriptionRescan] then computes that row's count, last seen and
 * next due from its own linked charges. Nothing stored on the transaction changes.
 */
object SubscriptionLinks {

    /** Ids linked to the subscription row [subscriptionId]. */
    fun linkedTo(subscriptionId: Long, links: List<SubscriptionLink>): Set<Long> =
        links.filter { it.subscriptionId == subscriptionId }.map { it.transactionId }.toSet()

    /**
     * Debits the user could attach to [subscription]: real spending in the
     * subscription's currency, not already linked to any subscription, newest
     * first. Charges from the same merchant stay in the list: with several
     * subscriptions at one merchant only the user can say which one a charge is.
     */
    fun candidates(
        transactions: List<Transaction>,
        links: List<SubscriptionLink>,
        subscription: Subscription,
        limit: Int = 40,
        query: String = "",
        from: java.time.LocalDate? = null,
        to: java.time.LocalDate? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Transaction> {
        val linked = links.map { it.transactionId }.toSet()
        return transactions
            .asSequence()
            .filter { it.isLinkable() && it.id !in linked }
            .filter { it.currencyCode.equals(subscription.currencyCode, ignoreCase = true) }
            .filter { ChargeFilter.matches(it, query, from, to, zone) }
            .sortedByDescending { it.timestampMillis }
            .take(limit)
            .toList()
    }

    /**
     * "Link similar": every unlinked charge with [subscription]'s brand key and
     * currency whose amount is within 5% of its amount, oldest first.
     */
    fun similar(
        transactions: List<Transaction>,
        links: List<SubscriptionLink>,
        subscription: Subscription,
    ): List<Transaction> {
        val linked = links.map { it.transactionId }.toSet()
        val key = SubscriptionRescan.keyOf(subscription.merchant)
        val ref = subscription.averageAmount.toDouble()
        if (ref <= 0.0) return emptyList()
        return transactions
            .asSequence()
            .filter { it.isLinkable() && it.id !in linked }
            .filter { SubscriptionRescan.keyOf(it.merchant) == key }
            .filter { it.currencyCode.equals(subscription.currencyCode, ignoreCase = true) }
            .filter { kotlin.math.abs(it.amount.toDouble() - ref) / ref <= 0.05 }
            .sortedBy { it.timestampMillis }
            .toList()
    }

    /**
     * Purchases to start a subscription from: real spending not already linked
     * to a subscription, newest first. [query] searches merchant, SMS text, note
     * and amount, [from]/[to] bound the date. The search runs **before** [limit],
     * so an old match is found even when 100 newer charges exist.
     */
    fun pickable(
        transactions: List<Transaction>,
        links: List<SubscriptionLink>,
        query: String = "",
        limit: Int = 100,
        from: java.time.LocalDate? = null,
        to: java.time.LocalDate? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Transaction> {
        val linked = links.map { it.transactionId }.toSet()
        return transactions
            .asSequence()
            .filter { it.isLinkable() && it.id !in linked }
            .filter { ChargeFilter.matches(it, query, from, to, zone) }
            .sortedByDescending { it.timestampMillis }
            .take(limit)
            .toList()
    }

    /**
     * A manual subscription seeded from one charge ("New subscription from this
     * charge"): monthly at the charge's amount, next due a month after it. The
     * user's own edits to cadence come later; the link itself is saved separately.
     */
    fun manualFrom(charge: Transaction, zone: ZoneId = ZoneId.systemDefault()): Subscription {
        val day = Instant.ofEpochMilli(charge.timestampMillis).atZone(zone).toLocalDate()
        return Subscription(
            merchant = charge.merchant?.trim()?.takeIf { it.isNotBlank() } ?: "Subscription",
            averageAmount = charge.amount.setScale(2, RoundingMode.HALF_UP),
            currencyCode = charge.currencyCode.uppercase(),
            cadence = SubscriptionCadence.MONTHLY,
            nextDueDateMillis = day.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            lastSeenMillis = charge.timestampMillis,
            occurrenceCount = 1,
            isManual = true,
            merchantKey = SubscriptionRescan.keyOf(charge.merchant),
        )
    }

    /** True for a captured purchase that can meaningfully be a subscription charge. */
    fun Transaction.isLinkable(): Boolean =
        id != 0L && kind == TransactionKind.SPEND && !excludeFromStats && direction == TransactionDirection.DEBIT
}
