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
            when {
                stored == null -> sub
                stored.isManual -> keepManual(stored, sub)
                else -> sub.copy(id = stored.id, dismissed = stored.dismissed)
            }
        }
    }

    /**
     * A hand-added row keeps what the user typed (amount, currency, cadence,
     * price history); the scan only contributes what it observed: the last
     * charge and how many there were. The next due date follows the scan only
     * when it read the same cadence, otherwise the typed schedule wins.
     */
    private fun keepManual(stored: Subscription, scanned: Subscription): Subscription = stored.copy(
        lastSeenMillis = maxOf(stored.lastSeenMillis, scanned.lastSeenMillis),
        occurrenceCount = scanned.occurrenceCount,
        nextDueDateMillis = if (scanned.cadence == stored.cadence) scanned.nextDueDateMillis else stored.nextDueDateMillis,
    )
}
