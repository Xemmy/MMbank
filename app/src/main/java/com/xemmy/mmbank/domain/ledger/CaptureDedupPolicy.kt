package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.intelligence.BillDetector
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.paylater.PayLaterProviders
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
 *
 * [isDuplicate] is the single entry point the repository uses: within
 * [NEAR_WINDOW_MILLIS] the rules above apply; further apart only a bill
 * payment's bank SMS + provider receipt ([isBillPair], up to
 * [BILL_WINDOW_MILLIS]), a Tabby installment's receipt + card charge
 * ([isPayLaterReceiptPair], up to [MIRROR_WINDOW_MILLIS]) or an SMS and the
 * messaging app's notification copy of that same SMS ([isSmsMirror], up to
 * [MIRROR_WINDOW_MILLIS]) can merge.
 */
object CaptureDedupPolicy {

    /** ±2 minutes: two channels reporting one purchase. */
    const val NEAR_WINDOW_MILLIS: Long = 2 * 60 * 1000

    /** How far apart a bank deduction SMS and the provider's receipt for one bill payment may arrive. */
    const val BILL_WINDOW_MILLIS: Long = 24 * 60 * 60 * 1000

    /**
     * How late the SMS app's notification may trail its SMS. The SMS keeps the carrier's
     * send time; the notification carries the delivery time, so an SMS held up by no signal
     * or flight mode arrives with the two clocks hours apart.
     */
    const val MIRROR_WINDOW_MILLIS: Long = 12 * 60 * 60 * 1000

    data class Side(
        val source: TransactionSource,
        val rawMessage: String?,
        val cardEnding: String?,
        val merchant: String? = null,
        /** Bill line already stored on the row (transactions.billRef), if any. */
        val billRef: String? = null,
        /** SMS sender or notifying app package, when known. */
        val sender: String? = null,
    )

    /**
     * Whether [existing] (captured at [existingAt]) and [incoming] (at [incomingAt]),
     * already equal in amount, currency and direction, are one payment.
     */
    fun isDuplicate(existing: Side, existingAt: Long, incoming: Side, incomingAt: Long): Boolean {
        val gap = kotlin.math.abs(existingAt - incomingAt)
        if (gap <= NEAR_WINDOW_MILLIS) return isSameTransaction(existing, incoming)
        if (gap <= BILL_WINDOW_MILLIS && isBillPair(existing, incoming)) return true
        if (gap <= MIRROR_WINDOW_MILLIS && isPayLaterReceiptPair(existing, incoming)) return true
        return isSmsMirror(existing, existingAt, incoming, incomingAt)
    }

    fun isSameTransaction(existing: Side, incoming: Side): Boolean {
        if (endingsConflict(existing.cardEnding, incoming.cardEnding)) return false
        // A telecom / utility payment arrives as the bank's deduction SMS AND the provider's
        // receipt SMS: same channel, different text, same payment -- unless they name different lines.
        if (isBillPair(existing, incoming)) return true
        if (isPayLaterReceiptPair(existing, incoming)) return true
        if (existing.source == incoming.source) {
            val a = existing.rawMessage?.trim()
            val b = incoming.rawMessage?.trim()
            return !a.isNullOrEmpty() && a == b
        }
        return true
    }

    /**
     * Both messages are the same provider's payment, exactly one names a line (or both name
     * the same line), and nothing says they are two payments:
     *  - different known card endings are two cards;
     *  - a number-less bank SMS that already took a receipt's line (billRef stored, its own
     *    text names none) is already paired, so another number-less SMS is another line's payment;
     *  - two messages that both name the line and come from the same sender are two payments
     *    (one receipt per payment).
     */
    fun isBillPair(existing: Side, incoming: Side): Boolean {
        val a = BillDetector.providerKey(existing.merchant) ?: return false
        if (a != BillDetector.providerKey(incoming.merchant)) return false
        if (endingsConflict(existing.cardEnding, incoming.cardEnding)) return false
        val existingTextRef = BillDetector.accountRef(existing.rawMessage)
        val existingRef = existing.billRef ?: existingTextRef
        val incomingRef = BillDetector.accountRef(incoming.rawMessage)
        if (existingRef != null && existingTextRef == null && incomingRef == null) return false
        if (existingRef != null && incomingRef != null) {
            return existingRef == incomingRef &&
                existing.rawMessage?.trim() != incoming.rawMessage?.trim() &&
                !(existingTextRef != null && sameSender(existing, incoming))
        }
        // Two number-less bank SMS are two payments (two lines); one numbered + one plain is the pair.
        return existingRef != null || incomingRef != null
    }

    /**
     * A Tabby installment arrives as Tabby's receipt ("We received your first payment of AED
     * 231.00") AND the paying card's charge ("... AED231.00 ... at Tabby"), from whichever bank
     * the card belongs to. One payment when:
     *  - exactly one side is Tabby's receipt (two receipts, or two bank charges, are two payments);
     *  - the other side paid Tabby ([PayLaterProviders.providerOf]);
     *  - the card endings don't conflict;
     *  - a kept receipt has not already absorbed a charge: the repository then copies the card's
     *    ending onto it, and a receipt's own text never names one.
     */
    fun isPayLaterReceiptPair(existing: Side, incoming: Side): Boolean {
        val existingReceipt = PayLaterProviders.isReceipt(existing.rawMessage, existing.sender)
        val incomingReceipt = PayLaterProviders.isReceipt(incoming.rawMessage, incoming.sender)
        if (existingReceipt == incomingReceipt) return false
        val charge = if (existingReceipt) incoming else existing
        if (PayLaterProviders.providerOf(charge.merchant, charge.rawMessage, charge.sender) == null) return false
        if (existingReceipt && !existing.cardEnding.isNullOrBlank()) return false
        return !endingsConflict(existing.cardEnding, incoming.cardEnding)
    }

    /**
     * An SMS and the notification the SMS app posted for it: one is [TransactionSource.SMS], the
     * other [TransactionSource.PUSH_NOTIFICATION], the text is identical (whitespace aside), and the
     * notification is not earlier than the SMS was sent (it can only be later) nor more than
     * [MIRROR_WINDOW_MILLIS] after it.
     */
    fun isSmsMirror(existing: Side, existingAt: Long, incoming: Side, incomingAt: Long): Boolean {
        val (sms, smsAt, push, pushAt) = when {
            existing.source == TransactionSource.SMS && incoming.source == TransactionSource.PUSH_NOTIFICATION ->
                Quad(existing, existingAt, incoming, incomingAt)
            incoming.source == TransactionSource.SMS && existing.source == TransactionSource.PUSH_NOTIFICATION ->
                Quad(incoming, incomingAt, existing, existingAt)
            else -> return false
        }
        val lag = pushAt - smsAt
        if (lag < -NEAR_WINDOW_MILLIS || lag > MIRROR_WINDOW_MILLIS) return false
        if (endingsConflict(sms.cardEnding, push.cardEnding)) return false
        val a = normalized(sms.rawMessage)
        return a.isNotEmpty() && a == normalized(push.rawMessage)
    }

    private data class Quad(val sms: Side, val smsAt: Long, val push: Side, val pushAt: Long)

    private val WHITESPACE = Regex("\\s+")

    private fun normalized(text: String?): String =
        text.orEmpty().replace(' ', ' ').replace(WHITESPACE, " ").trim()

    private fun sameSender(a: Side, b: Side): Boolean {
        val x = a.sender?.trim().orEmpty()
        val y = b.sender?.trim().orEmpty()
        return x.isNotEmpty() && x.equals(y, ignoreCase = true)
    }

    /** True when both endings are known (digits) and neither is a suffix of the other. */
    internal fun endingsConflict(a: String?, b: String?): Boolean {
        val x = Digits.only(a)
        val y = Digits.only(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return !(x.endsWith(y) || y.endsWith(x))
    }
}
