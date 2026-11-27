package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterCadence
import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.parser.BuiltInRules
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Finds Tabby split-in-N checkouts in the ledger that have no Pay Later plan yet, for the
 * "Tabby split found -> Review" prompt. Pure; nothing is created until the user confirms
 * (see [TabbySplitPlan]).
 *
 * One checkout leaves up to three rows: Tabby's purchase ("Your AED 924.00 purchase at Shory is
 * confirmed", the full price), the paying card's charge for each installment (any bank: found by
 * [PayLaterProviders.providerOf]) and Tabby's receipt for it ("We received your first payment of
 * AED 231.00"). Rows captured before 0.142.0 hold that receipt as a +credit; it is evidence of the
 * payment when the card's charge is missing, and a duplicate to remove when it is there.
 *
 * The whole history is searched, so earlier splits are found too. Installment k (1-based) is due k-1
 * months after the purchase, on the same day; each charge belongs to one installment of one split.
 */
object TabbySplitDetector {

    const val DEFAULT_COUNT = 4
    const val MIN_COUNT = 2
    const val MAX_COUNT = 12

    private const val HOUR = 60L * 60 * 1000
    private const val DAY = 24 * HOUR

    /** The first payment is taken at checkout: its charge lands within a day of the purchase. */
    const val FIRST_PAYMENT_WINDOW_MILLIS = DAY

    /** Later installments: the same window [PayLaterAutoMatch] uses around a due date. */
    const val INSTALLMENT_WINDOW_MILLIS = 7 * DAY

    /** A receipt and the charge it confirms arrive together. */
    const val RECEIPT_WINDOW_MILLIS = DAY

    /** A plan whose first installment is this close to a purchase of the same total already covers it. */
    private const val COVERED_WINDOW_MILLIS = 2 * DAY

    private const val AMOUNT_TOLERANCE = 0.01

    /** What was found for one checkout, for one installment count. */
    data class Match(
        /** The row that paid installment k, at index k-1; null where none was captured. */
        val payments: List<Transaction?>,
        /** Receipts that repeat a payment already in [payments]; removed when the plan is created. */
        val duplicateReceipts: List<Transaction>,
    ) {
        val foundCount: Int get() = payments.count { it != null }

        /** Pre-0.142 receipts stored as +credits that stand in for a missing charge (turned into the debit). */
        val legacyReceiptPayments: List<Transaction>
            get() = payments.filterNotNull().filter { it.direction == TransactionDirection.CREDIT }
    }

    data class Candidate(
        val purchase: Transaction,
        /** The shop ("Shory"): the row's merchant, else the name in Tabby's text. */
        val merchant: String,
        val provider: String,
        /** Installments, from the first payment's share of the total; [DEFAULT_COUNT] when none was seen. */
        val count: Int,
        val match: Match,
        /** Rows this checkout may claim, for re-matching with another count ([matchFor]). */
        val evidence: List<Transaction>,
    )

    /** Every unplanned, undismissed checkout, newest first. */
    fun find(
        transactions: List<Transaction>,
        plans: List<PayLaterPlan>,
        installments: List<PayLaterInstallment>,
        dismissedIds: Set<Long>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Candidate> {
        val linkedIds = installments.mapNotNull { it.paymentTransactionId }.toSet() +
            plans.mapNotNull { it.sourceTransactionId }
        val purchases = transactions
            .filter { isPurchase(it) && it.id != 0L && it.id !in dismissedIds && it.id !in linkedIds }
            .filterNot { covered(it, plans, installments) }
            .sortedBy { it.timestampMillis }
        val purchaseIds = purchases.map { it.id }.toSet()
        val pool = transactions.filter { isEvidence(it) && it.id !in linkedIds && it.id !in purchaseIds }

        // Oldest first: a checkout claims its charges before a later one can.
        val used = mutableSetOf<Long>()
        val found = purchases.map { purchase ->
            val evidence = pool.filter { it.id !in used && inSpan(purchase, it) }
            val count = detectCount(purchase, evidence)
            val match = assign(purchase, count, evidence, zone)
            used += match.payments.mapNotNull { it?.id }
            used += match.duplicateReceipts.map { it.id }
            Candidate(
                purchase = purchase,
                merchant = merchantOf(purchase),
                provider = PayLaterProviders.TABBY,
                count = count,
                match = match,
                evidence = evidence,
            )
        }
        return found.sortedByDescending { it.purchase.timestampMillis }
    }

    /** The payments of [candidate] for [count] installments (the user changed the detected count). */
    fun matchFor(candidate: Candidate, count: Int, zone: ZoneId = ZoneId.systemDefault()): Match =
        if (count == candidate.count) candidate.match else assign(candidate.purchase, count, candidate.evidence, zone)

    /** Tabby's checkout message: the 0.142 rule, or (rows captured earlier) its wording. */
    fun isPurchase(tx: Transaction): Boolean =
        tx.direction == TransactionDirection.DEBIT && !tx.isDemo &&
            (tx.matchedRuleName == BuiltInRules.PAY_LATER_PURCHASE_RULE ||
                PayLaterProviders.isSplitPurchase(tx.rawMessage, tx.sender))

    private fun merchantOf(purchase: Transaction): String =
        purchase.merchant?.trim()?.takeIf { it.isNotEmpty() }
            ?: PayLaterProviders.splitPurchaseMerchant(purchase.rawMessage)
            ?: PayLaterProviders.TABBY

    /** A charge that paid Tabby, or Tabby's receipt for one (either direction: old receipts are credits). */
    private fun isEvidence(tx: Transaction): Boolean {
        if (tx.isDemo || tx.linkedTransactionId != null) return false
        return when (tx.direction) {
            TransactionDirection.DEBIT -> PayLaterProviders.providerOf(tx.merchant, tx.rawMessage, tx.sender) != null
            TransactionDirection.CREDIT -> isReceipt(tx)
        }
    }

    private fun isReceipt(tx: Transaction): Boolean = PayLaterProviders.isReceipt(tx.rawMessage, tx.sender)

    private fun inSpan(purchase: Transaction, tx: Transaction): Boolean {
        val from = purchase.timestampMillis - FIRST_PAYMENT_WINDOW_MILLIS
        val to = purchase.timestampMillis + (MAX_COUNT * 31L) * DAY + INSTALLMENT_WINDOW_MILLIS
        return tx.timestampMillis in from..to && tx.currencyCode.equals(purchase.currencyCode, ignoreCase = true)
    }

    /** A plan of the same total whose first installment falls on the purchase day already splits it. */
    private fun covered(purchase: Transaction, plans: List<PayLaterPlan>, installments: List<PayLaterInstallment>): Boolean =
        plans.any { plan ->
            if (!plan.currencyCode.equals(purchase.currencyCode, ignoreCase = true)) return@any false
            if (!near(plan.totalAmount, purchase.amount)) return@any false
            val first = installments.filter { it.planId == plan.id }.minByOrNull { it.number } ?: return@any false
            abs(first.dueDateMillis - purchase.timestampMillis) <= COVERED_WINDOW_MILLIS
        }

    /** The count the first payment implies (231 of 924 -> 4), else [DEFAULT_COUNT]. */
    private fun detectCount(purchase: Transaction, evidence: List<Transaction>): Int =
        evidence
            .filter { abs(it.timestampMillis - purchase.timestampMillis) <= FIRST_PAYMENT_WINDOW_MILLIS }
            .sortedWith(preference(purchase.timestampMillis))
            .firstNotNullOfOrNull { countImplied(purchase.amount, it.amount) }
            ?: DEFAULT_COUNT

    internal fun countImplied(total: BigDecimal, part: BigDecimal): Int? {
        if (part.signum() <= 0 || part >= total) return null
        val k = total.divide(part, 0, RoundingMode.HALF_UP).toInt()
        if (k !in MIN_COUNT..MAX_COUNT) return null
        return k.takeIf { near(part.multiply(BigDecimal(k)), total) }
    }

    private fun assign(purchase: Transaction, count: Int, evidence: List<Transaction>, zone: ZoneId): Match {
        val firstDay = Instant.ofEpochMilli(purchase.timestampMillis).atZone(zone).toLocalDate()
        val amounts = PayLaterSchedule.splitAmounts(purchase.amount, count)
        val taken = mutableSetOf<Long>()
        val duplicates = mutableListOf<Transaction>()
        val payments = (0 until count).map { i ->
            // The first payment is taken at checkout; later ones on the same day of later months.
            val (anchor, window) = if (i == 0) {
                purchase.timestampMillis to FIRST_PAYMENT_WINDOW_MILLIS
            } else {
                PayLaterSchedule.millisOf(PayLaterSchedule.dueDate(firstDay, PayLaterCadence.MONTHLY, i), zone) to
                    INSTALLMENT_WINDOW_MILLIS
            }
            val payment = evidence
                .filter { it.id !in taken && near(it.amount, amounts[i]) && abs(it.timestampMillis - anchor) <= window }
                .sortedWith(preference(anchor))
                .firstOrNull()
            if (payment != null) {
                taken += payment.id
                // Tabby's receipt for the same payment, captured as its own row.
                evidence
                    .filter { it.id !in taken && isReceipt(it) && near(it.amount, payment.amount) }
                    .filter { abs(it.timestampMillis - payment.timestampMillis) <= RECEIPT_WINDOW_MILLIS }
                    .forEach { taken += it.id; duplicates += it }
            }
            payment
        }
        return Match(payments, duplicates)
    }

    /** The card's own charge before Tabby's receipt (it carries the account), then the nearest in time. */
    private fun preference(anchor: Long): Comparator<Transaction> =
        compareBy<Transaction> { if (isReceipt(it)) 1 else 0 }
            .thenBy { if (it.accountId == null) 1 else 0 }
            .thenBy { abs(it.timestampMillis - anchor) }

    private fun near(a: BigDecimal, b: BigDecimal): Boolean {
        val expected = b.toDouble()
        if (expected <= 0.0) return false
        return abs(a.toDouble() - expected) / expected <= AMOUNT_TOLERANCE
    }
}
