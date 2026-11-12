package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.TransactionSource

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
    )

    fun isSameTransaction(existing: Side, incoming: Side): Boolean {
        if (endingsConflict(existing.cardEnding, incoming.cardEnding)) return false
        if (existing.source == incoming.source) {
            val a = existing.rawMessage?.trim()
            val b = incoming.rawMessage?.trim()
            return !a.isNullOrEmpty() && a == b
        }
        return true
    }

    /** True when both endings are known (digits) and neither is a suffix of the other. */
    internal fun endingsConflict(a: String?, b: String?): Boolean {
        val x = a?.filter(Char::isDigit).orEmpty()
        val y = b?.filter(Char::isDigit).orEmpty()
        if (x.isEmpty() || y.isEmpty()) return false
        return !(x.endsWith(y) || y.endsWith(x))
    }
}
