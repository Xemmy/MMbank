package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.intelligence.BillDetector
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.util.Digits

/**
 * Decides whether two captures inside the dedup window are the SAME purchase
 * (Improvement Plan B4). The amount/currency/direction/time match is done by
 * the DAO; this pure policy then vetoes the false positives:
 *
 *  - Dedup is for the *same purchase arriving over two channels* (SMS and a
 *    bank-app push). Two messages from the SAME channel are two purchases
 *    (two AED 4 Salik gates a minute apart) unless their text is identical,
 *    which is a redelivery of one message.
 *  - Two captures whose card endings are both known and differ belong to
 *    different cards, so they are never the same purchase.
 */
object CaptureDedupPolicy {

    data class Side(
        val source: TransactionSource,
        val rawMessage: String?,
        val cardEnding: String?,
        val merchant: String? = null,
        /** Bill line already stored on the row (transactions.billRef), if any. */
        val billRef: String? = null,
    )

    fun isSameTransaction(existing: Side, incoming: Side): Boolean {
        if (endingsConflict(existing.cardEnding, incoming.cardEnding)) return false
        // A telecom / utility payment arrives as the bank's deduction SMS AND the provider's
        // receipt SMS: same channel, different text, same payment -- unless they name different lines.
        if (isBillPair(existing, incoming)) return true
        if (existing.source == incoming.source) {
            val a = existing.rawMessage?.trim()
            val b = incoming.rawMessage?.trim()
            return !a.isNullOrEmpty() && a == b
        }
        return true
    }

    /** Both messages are the same provider's payment, exactly one names a line, and they don't name two different lines. */
    fun isBillPair(existing: Side, incoming: Side): Boolean {
        val a = BillDetector.providerKey(existing.merchant) ?: return false
        if (a != BillDetector.providerKey(incoming.merchant)) return false
        val existingRef = existing.billRef ?: BillDetector.accountRef(existing.rawMessage)
        val incomingRef = BillDetector.accountRef(incoming.rawMessage)
        if (existingRef != null && incomingRef != null) return existingRef == incomingRef && existing.rawMessage?.trim() != incoming.rawMessage?.trim()
        // Two number-less bank SMS are two payments (two lines); one numbered + one plain is the pair.
        return existingRef != null || incomingRef != null
    }

    /** True when both endings are known (digits) and neither is a suffix of the other. */
    internal fun endingsConflict(a: String?, b: String?): Boolean {
        val x = Digits.only(a)
        val y = Digits.only(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return !(x.endsWith(y) || y.endsWith(x))
    }
}
