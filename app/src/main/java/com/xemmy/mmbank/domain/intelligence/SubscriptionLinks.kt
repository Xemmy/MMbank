package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.SubscriptionLink
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.parser.MerchantNormalizer
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

/**
 * Manual subscription links. The radar groups charges by merchant text, so a
 * charge the bank prints differently ("APPLE.COM/BILL" vs "Apple") can never
 * join an existing series. A link records the user's say-so; [apply] then
 * renames the linked transactions to the subscription's merchant before
 * detection, so they count toward occurrences, average, last seen and next due
 * like any other charge. Nothing stored on the transaction changes.
 */
object SubscriptionLinks {

    /** [transactions] with each linked one's merchant replaced by its link's merchant. */
    fun apply(transactions: List<Transaction>, links: List<SubscriptionLink>): List<Transaction> {
        if (links.isEmpty()) return transactions
        val byTransaction = links.associate { it.transactionId to it.merchant }
        return transactions.map { tx ->
            val merchant = byTransaction[tx.id]
            if (merchant == null || tx.merchant == merchant) tx else tx.copy(merchant = merchant)
        }
    }

    /** Ids linked to [merchant]'s subscription. */
    fun linkedTo(merchant: String, links: List<SubscriptionLink>): Set<Long> =
        links.filter { it.merchant == merchant }.map { it.transactionId }.toSet()

    /**
     * Debits the user could attach to [subscription]: real spending in the
     * subscription's currency, not already linked to anything, newest first.
     * Charges the radar already groups under this merchant are left out, since
     * linking them would change nothing.
     */
    fun candidates(
        transactions: List<Transaction>,
        links: List<SubscriptionLink>,
        subscription: Subscription,
        limit: Int = 40,
    ): List<Transaction> {
        val linked = links.map { it.transactionId }.toSet()
        val ownKey = keyOf(subscription.merchant)
        return transactions
            .asSequence()
            .filter { it.isLinkable() && it.id !in linked }
            .filter { it.currencyCode.equals(subscription.currencyCode, ignoreCase = true) }
            .filter { tx -> tx.merchant.isNullOrBlank() || keyOf(tx.merchant) != ownKey }
            .sortedByDescending { it.timestampMillis }
            .take(limit)
            .toList()
    }

    /**
     * Purchases to start a subscription from: real spending not already linked
     * to a subscription, newest first. [query] filters by merchant text.
     */
    fun pickable(
        transactions: List<Transaction>,
        links: List<SubscriptionLink>,
        query: String = "",
        limit: Int = 100,
    ): List<Transaction> {
        val linked = links.map { it.transactionId }.toSet()
        val needle = query.trim().lowercase()
        return transactions
            .asSequence()
            .filter { it.isLinkable() && it.id !in linked }
            .filter { needle.isEmpty() || it.merchant?.lowercase()?.contains(needle) == true }
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
        )
    }

    /** True for a captured purchase that can meaningfully be a subscription charge. */
    fun Transaction.isLinkable(): Boolean =
        id != 0L && kind == TransactionKind.SPEND && !excludeFromStats && direction == TransactionDirection.DEBIT

    private fun keyOf(merchant: String?): String =
        merchant?.let { MerchantNormalizer.key(it).ifEmpty { it.trim().lowercase() } }.orEmpty()
}
