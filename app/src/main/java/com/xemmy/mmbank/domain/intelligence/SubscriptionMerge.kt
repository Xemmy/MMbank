package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription

/**
 * Prepares a fresh scan's results for storage. REPLACE on the unique merchant
 * index deletes the old row and inserts a new one with a new id, and the
 * reminder key is built from the id, so every rescan made an already-announced
 * renewal look new. Reusing the stored id keeps the key stable; the dismissed
 * flag is carried over for the same reason.
 */
object SubscriptionMerge {
    fun merge(existing: List<Subscription>, detected: List<Subscription>): List<Subscription> {
        val byMerchant = existing.associateBy { it.merchant }
        return detected.map { sub ->
            val stored = byMerchant[sub.merchant]
            if (stored == null) sub else sub.copy(id = stored.id, dismissed = stored.dismissed)
        }
    }
}
