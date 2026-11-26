package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterCadence
import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import java.time.Instant
import java.time.ZoneId

/**
 * Turns a reviewed [TabbySplitDetector.Candidate] into the plan to save and the changes to its rows.
 * Pure; the repository applies the [Result] in one database transaction.
 *
 * Two ways to count the purchase, chosen per split in the review prompt:
 *  - [Mode.PER_PAYMENT]: each installment counts as spending when it is paid; the full-price purchase
 *    row is kept for reference but hidden from spending ([PayLaterPlan.sourceIsInstallment], so
 *    later payments matched by [PayLaterAutoMatch] stay counted -- [PayLaterPayment.shapeOf]).
 *  - [Mode.FULL_PRICE]: the purchase counts once, on its day; every installment charge is hidden from
 *    spending (still on its card's balance), as [PayLaterPayment.matchedShape] does for later ones.
 *
 * Installments are monthly on the purchase day. A found charge pays its installment and is linked to
 * it; a past installment with no charge is paid without a link when [markMissingPaid] (Tabby debits the
 * card by itself -- the SMS was most likely just not captured); the rest stay open for
 * [PayLaterAutoMatch] to settle as their SMS arrive.
 */
object TabbySplitPlan {

    enum class Mode { PER_PAYMENT, FULL_PRICE }

    data class Result(
        val plan: PayLaterPlan,
        val installments: List<PayLaterInstallment>,
        /** Rows to hide from spending (the purchase, or the installment charges). */
        val excludeIds: List<Long>,
        /** Pre-0.142 receipts (+credit) that are the only record of a payment: become that payment's debit. */
        val convertReceiptIds: List<Long>,
        /** Receipts that repeat a charge already linked. */
        val deleteIds: List<Long>,
        /** Names for rows that have none: the purchase gets the shop, each payment the provider. */
        val nameIfBlank: Map<Long, String>,
    )

    fun build(
        candidate: TabbySplitDetector.Candidate,
        count: Int = candidate.count,
        mode: Mode = Mode.PER_PAYMENT,
        markMissingPaid: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Result {
        require(count in PayLaterSchedule.MIN_COUNT..PayLaterSchedule.MAX_COUNT) { "count out of range" }
        val purchase = candidate.purchase
        val match = TabbySplitDetector.matchFor(candidate, count, zone)
        val firstDay = Instant.ofEpochMilli(purchase.timestampMillis).atZone(zone).toLocalDate()
        val schedule = PayLaterSchedule.generate(purchase.amount, count, firstDay, PayLaterCadence.MONTHLY, zone)
        val installments = schedule.mapIndexed { i, inst ->
            val payment = match.payments.getOrNull(i)
            when {
                payment != null -> PayLaterPayment.markPaid(inst, payment.id, payment.timestampMillis)
                markMissingPaid && isOverdue(inst, nowMillis) -> PayLaterPayment.markPaid(inst, null, inst.dueDateMillis)
                else -> inst
            }
        }
        val paymentIds = match.payments.mapNotNull { it?.id }
        val plan = PayLaterPlan(
            merchant = candidate.merchant,
            provider = candidate.provider,
            totalAmount = purchase.amount,
            currencyCode = purchase.currencyCode,
            installmentCount = count,
            cadence = PayLaterCadence.MONTHLY,
            payFromAccountId = match.payments.firstNotNullOfOrNull { it?.accountId },
            sourceTransactionId = purchase.id,
            categoryId = purchase.categoryId,
            createdAt = nowMillis,
            isDemo = purchase.isDemo,
            sourceIsInstallment = mode == Mode.PER_PAYMENT,
        )
        val names = buildMap {
            if (purchase.merchant.isNullOrBlank()) put(purchase.id, candidate.merchant)
            paymentIds.forEach { put(it, candidate.provider) }
        }
        return Result(
            plan = plan,
            installments = installments,
            excludeIds = when (mode) {
                Mode.PER_PAYMENT -> listOf(purchase.id)
                Mode.FULL_PRICE -> paymentIds
            },
            convertReceiptIds = match.legacyReceiptPayments.map { it.id },
            deleteIds = match.duplicateReceipts.map { it.id },
            nameIfBlank = names,
        )
    }

    /** Past its due date by more than the matching window: its SMS is not coming any more. */
    fun isOverdue(installment: PayLaterInstallment, nowMillis: Long): Boolean =
        installment.dueDateMillis + TabbySplitDetector.INSTALLMENT_WINDOW_MILLIS < nowMillis
}
